// Run: node backend/scripts/test-schema.mjs <temporary-node_modules-directory>
// Test-only dependencies: @electric-sql/pglite@0.3.7 and bcryptjs@3.0.2 (not Android/runtime deps).
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
if (!process.argv[2]) throw new Error('Pass the temporary node_modules directory; see SUPABASE_SETUP.md');
const require = createRequire(resolve(process.argv[2], '../package.json'));
const { PGlite } = require('@electric-sql/pglite');
const bcrypt = require('bcryptjs');
const read = p => readFileSync(resolve(root, p), 'utf8');
const ddl = read('backend/src/main/resources/db/migration/V1__initial_schema.sql');
const template = read('backend/src/main/resources/db/demo/V2__seed_demo_data.sql');
const verify = read('backend/scripts/verify-schema.sql');
const password = randomBytes(24).toString('hex');
const hash = bcrypt.hashSync(password, 10);
const seed = template.replaceAll('${demoPasswordHash}', hash);
const alice = '11111111-1111-1111-1111-111111111111';
const bob = '22222222-2222-2222-2222-222222222222';
const david = '44444444-4444-4444-4444-444444444444';
const eve = '55555555-5555-5555-5555-555555555555';
const direct = 'aaaaaaaa-1111-2222-0000-000000000001';
const group = 'bbbbbbbb-1111-3333-0000-000000000001';
const db = new PGlite();
let passed = 0;
async function test(name, fn) {
    await fn();
    passed++;
    console.log('PASS: ' + name);
}
async function fails(sql, code) {
    await assert.rejects(db.exec(sql), error => error.code === code, 'Expected SQLSTATE ' + code);
}
try {
    await test('production migration executes with exactly 13 core tables and no demo data', async () => {
        await db.exec(ddl);
        const result = await db.query("SELECT count(*)::int AS n FROM pg_tables WHERE schemaname = 'public'");
        assert.equal(result.rows[0].n, 13);
        assert.equal((await db.query('SELECT count(*)::int AS n FROM public.users')).rows[0].n, 0);
        await db.exec(verify);
    });
    await test('demo refuses an absent BCrypt placeholder', async () => {
        await fails(template, 'P0001');
        assert.equal((await db.query("SELECT to_regclass('public.demo_sms_outbox') AS t")).rows[0].t, null);
    });
    await test('demo migration creates the 14th table and valid BCrypt accounts', async () => {
        await db.exec(seed);
        await db.exec("SET utechat.verify_demo = 'true'");
        await db.exec(verify);
        const users = await db.query('SELECT password_hash FROM public.users');
        assert.equal(users.rows.length, 5);
        for (const row of users.rows) assert(bcrypt.compareSync(password, row.password_hash));
        const json = await db.query("SELECT payload_json FROM public.sync_events WHERE event_type = 'MESSAGE_CREATED'");
        assert.equal(json.rows.length, 12);
        for (const row of json.rows) {
            for (const key of ['id','clientId','conversationId','senderId','type','text','createdAt','call']) {
                assert(Object.hasOwn(row.payload_json, key));
            }
        }
    });
    await test('seed can be repeated without duplicate messages/events or regressing receipts', async () => {
        await db.exec("UPDATE public.conversation_members SET last_read_message_id = last_delivered_message_id WHERE user_id = '" + bob + "'");
        const receipts = await db.query('SELECT conversation_id, user_id, last_read_message_id FROM public.conversation_members ORDER BY conversation_id, user_id');
        const before = await db.query('SELECT (SELECT count(*)::int FROM public.messages) AS m, (SELECT count(*)::int FROM public.sync_events) AS e');
        await db.exec(seed);
        const after = await db.query('SELECT (SELECT count(*)::int FROM public.messages) AS m, (SELECT count(*)::int FROM public.sync_events) AS e');
        assert.deepEqual(after.rows, before.rows);
        assert.deepEqual((await db.query('SELECT conversation_id, user_id, last_read_message_id FROM public.conversation_members ORDER BY conversation_id, user_id')).rows, receipts.rows);
    });
    await test('phone format and self-contact checks reject invalid input', async () => {
        await fails("INSERT INTO public.users(phone_e164,display_name,password_hash) VALUES ('bad','invalid','hash')", '23514');
        await fails("INSERT INTO public.contact_requests(requester_id,addressee_id) VALUES ('" + alice + "','" + alice + "')", '23514');
        await fails("INSERT INTO public.contacts(owner_id,contact_user_id) VALUES ('" + alice + "','" + alice + "')", '23514');
    });
    await test('pending pair index rejects both duplicate and reversed invitations', async () => {
        await fails("INSERT INTO public.contact_requests(requester_id,addressee_id) VALUES ('" + bob + "','" + david + "')", '23505');
        await fails("INSERT INTO public.contact_requests(requester_id,addressee_id) VALUES ('" + david + "','" + bob + "')", '23505');
    });
    await test('PHONE_DEVICE scope and uniqueness work', async () => {
        const sql = "INSERT INTO public.auth_throttles(scope_type,scope_key_hash,purpose) VALUES ('PHONE_DEVICE','test','PASSWORD_LOGIN')";
        await db.exec(sql);
        await fails(sql, '23505');
        await fails("INSERT INTO public.auth_throttles(scope_type,scope_key_hash,purpose) VALUES ('WRONG','test','PASSWORD_LOGIN')", '23514');
    });
    await test('watermarks reject READ above DELIVERED', async () => {
        await fails("UPDATE public.conversation_members SET last_read_message_id = last_delivered_message_id + 1 WHERE user_id = '" + bob + "'", '23514');
    });
    await test('message IDs advance after seeding and clientId deduplicates', async () => {
        const before = await db.query('SELECT max(id)::int AS n FROM public.messages');
        const insert = "INSERT INTO public.messages(conversation_id,sender_id,client_id,type,text) VALUES ('" + direct + "','" + alice + "','ffffffff-0099-0000-0000-000000000099','TEXT','sequence test') RETURNING id";
        const result = await db.query(insert);
        assert(Number(result.rows[0].id) > before.rows[0].n);
        await fails(insert, '23505');
    });
    await test('attachment can belong to only one message', async () => {
        const result = await db.query("INSERT INTO public.attachments(uploader_id,kind,mime,size_bytes,storage_path) VALUES ('" + alice + "','IMAGE','image/png',1,'test-only.png') RETURNING id");
        const id = result.rows[0].id;
        const make = n => "INSERT INTO public.messages(conversation_id,sender_id,client_id,type,attachment_id) VALUES ('" + direct + "','" + alice + "','ffffffff-00" + n + "-0000-0000-0000000000" + n + "','IMAGE','" + id + "')";
        await db.exec(make('91'));
        await fails(make('92'), '23505');
    });
    await test('foreign keys reject unknown senders', async () => {
        await fails("INSERT INTO public.messages(conversation_id,sender_id,client_id,type,text) VALUES ('" + direct + "','99999999-9999-9999-9999-999999999999',gen_random_uuid(),'TEXT','bad FK')", '23503');
    });
    await test('anon and authenticated see zero rows on every demo table and cannot insert', async () => {
        const tables = await db.query("SELECT tablename FROM pg_tables WHERE schemaname = 'public'");
        for (const role of ['utechat_test_anon','utechat_test_authenticated']) {
            await db.exec('CREATE ROLE ' + role + ' NOLOGIN; GRANT USAGE ON SCHEMA public TO ' + role + '; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ' + role);
            await db.exec('SET ROLE ' + role);
            try {
                for (const {tablename} of tables.rows) {
                    const r = await db.query('SELECT count(*)::int AS n FROM public.' + tablename);
                    assert.equal(r.rows[0].n, 0, role + ': ' + tablename);
                }
                await fails("INSERT INTO public.users(id,phone_e164,display_name,password_hash) VALUES (gen_random_uuid(),'+84906666666','anon','hash')", '42501');
            } finally { await db.exec('RESET ROLE'); }
        }
    });
    for (const [name, change] of [
        ['missing index', 'DROP INDEX public.idx_messages_conversation_id_desc'],
        ['disabled RLS', 'ALTER TABLE public.users DISABLE ROW LEVEL SECURITY'],
        ['leaked policy', 'CREATE POLICY test_leak ON public.users FOR SELECT USING (true)']
    ]) {
        await test('verification fails on ' + name, async () => {
            await db.exec('BEGIN');
            try { await db.exec(change); await fails(verify, 'P0001'); }
            finally { await db.exec('ROLLBACK'); }
        });
    }
    await test('production verification rejects a demo database', async () => {
        await db.exec("SET utechat.verify_demo = 'false'");
        await fails(verify, 'P0001');
        await db.exec("SET utechat.verify_demo = 'true'");
        await db.exec(verify);
    });
    console.log('PASS: ' + passed + ' PostgreSQL WASM checks; Supabase cloud still requires live verification');
} finally {
    await db.close();
}
