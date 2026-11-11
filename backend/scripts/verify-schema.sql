-- Read-only assertions. psql: use -v ON_ERROR_STOP=1 so any FAIL is nonzero.
-- Default: production (13 core tables). For demo, SET utechat.verify_demo = 'true';
DO $verify$
DECLARE
    demo BOOLEAN := COALESCE(current_setting('utechat.verify_demo', true), 'false') = 'true';
    expected TEXT[] := ARRAY['users','user_devices','otp_challenges','auth_sessions','auth_throttles',
        'contact_requests','contacts','conversations','conversation_members','attachments','calls','messages','sync_events'];
    item TEXT;
    total INTEGER;
BEGIN
    IF demo THEN expected := array_append(expected, 'demo_sms_outbox'); END IF;

    -- 1. Required tables: a missing table cannot disappear from the result.
    FOREACH item IN ARRAY expected LOOP
        IF to_regclass('public.' || item) IS NULL THEN
            RAISE EXCEPTION 'FAIL: missing table %', item;
        END IF;
    END LOOP;
    SELECT count(*) INTO total FROM pg_tables WHERE schemaname = 'public' AND tablename = ANY(expected);
    IF total <> cardinality(expected) THEN RAISE EXCEPTION 'FAIL: table count'; END IF;
    IF NOT demo AND to_regclass('public.demo_sms_outbox') IS NOT NULL THEN
        RAISE EXCEPTION 'FAIL: demo_sms_outbox found in production';
    END IF;
    RAISE NOTICE 'PASS: table inventory';

    -- 2. RLS enabled on every expected table; owner bypass is intentional.
    FOREACH item IN ARRAY expected LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = item AND c.relrowsecurity AND NOT c.relforcerowsecurity) THEN
            RAISE EXCEPTION 'FAIL: RLS missing or forced on %', item;
        END IF;
    END LOOP;
    RAISE NOTICE 'PASS: relrowsecurity enabled';

    -- 3. No data-access policy on application tables.
    IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = ANY(expected)) THEN
        RAISE EXCEPTION 'FAIL: unexpected RLS policy on application table';
    END IF;
    RAISE NOTICE 'PASS: zero policies';

    -- 4. Performance and race-safety indexes.
    FOREACH item IN ARRAY ARRAY['idx_contact_requests_pending_pair','idx_messages_conversation_id_desc',
        'idx_sync_events_user_cursor','idx_users_phone','idx_auth_throttles_scope','uq_messages_attachment',
        'idx_sync_events_created'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = item) THEN
            RAISE EXCEPTION 'FAIL: missing index %', item;
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public'
        AND indexname = 'idx_contact_requests_pending_pair' AND indexdef LIKE 'CREATE UNIQUE INDEX%'
        AND indexdef LIKE '%LEAST%' AND indexdef LIKE '%GREATEST%' AND indexdef LIKE '%PENDING%') THEN
        -- pg_get_indexdef normalizes function names to lowercase on some versions.
        IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public'
            AND indexname = 'idx_contact_requests_pending_pair' AND indexdef LIKE 'CREATE UNIQUE INDEX%'
            AND lower(indexdef) LIKE '%least%' AND lower(indexdef) LIKE '%greatest%' AND indexdef LIKE '%PENDING%') THEN
            RAISE EXCEPTION 'FAIL: pending pair unique index has wrong definition';
        END IF;
    END IF;
    RAISE NOTICE 'PASS: indexes';

    -- 5. Privacy, event payload type, and essential CHECK/FK invariants.
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
        AND table_name = 'users' AND column_name = 'allow_messages_from_non_contacts'
        AND data_type = 'boolean' AND is_nullable = 'NO' AND column_default = 'true') THEN
        RAISE EXCEPTION 'FAIL: privacy column/default';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
        AND table_name = 'sync_events' AND column_name = 'payload_json'
        AND data_type = 'jsonb' AND is_nullable = 'NO') THEN
        RAISE EXCEPTION 'FAIL: sync_events JSONB';
    END IF;
    FOREACH item IN ARRAY ARRAY['chk_watermarks_monotonic','chk_contact_requests_no_self',
        'chk_contacts_no_self','chk_users_phone_format','fk_conversations_last_message'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE n.nspname = 'public' AND c.conname = item AND c.convalidated) THEN
            RAISE EXCEPTION 'FAIL: missing/invalid constraint %', item;
        END IF;
    END LOOP;
    RAISE NOTICE 'PASS: privacy, JSONB, constraints';

    -- 6. Demo fixtures are checked only on a demo database.
    IF demo THEN
        IF (SELECT count(*) FROM public.users WHERE id IN (
            '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
            '33333333-3333-3333-3333-333333333333', '44444444-4444-4444-4444-444444444444',
            '55555555-5555-5555-5555-555555555555')) <> 5 THEN
            RAISE EXCEPTION 'FAIL: missing demo users';
        END IF;
        IF (SELECT count(*) FROM public.contacts) < 7 OR
            (SELECT count(*) FROM public.contact_requests WHERE status = 'PENDING') < 1 OR
            (SELECT count(*) FROM public.conversations) < 2 OR
            (SELECT count(*) FROM public.conversation_members) < 5 OR
            (SELECT count(*) FROM public.messages) < 5 OR
            (SELECT count(*) FROM public.sync_events) < 3 THEN
            RAISE EXCEPTION 'FAIL: incomplete demo relationships/messages/events';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.users WHERE id = '44444444-4444-4444-4444-444444444444'
            AND NOT allow_messages_from_non_contacts) THEN
            RAISE EXCEPTION 'FAIL: David privacy fixture';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.contacts WHERE owner_id = '11111111-1111-1111-1111-111111111111'
            AND contact_user_id = '55555555-5555-5555-5555-555555555555' AND blocked) THEN
            RAISE EXCEPTION 'FAIL: Alice-Eve block fixture';
        END IF;
        RAISE NOTICE 'PASS: demo seed';
    END IF;
END $verify$;
SELECT 'PASS' AS status, 'Schema, RLS, indexes, constraints and selected profile verified' AS detail;
