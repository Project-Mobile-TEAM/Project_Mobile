-- DEMO ONLY. Include db/demo in Flyway locations only on a dedicated demo database.
-- Flyway placeholder is a locally generated BCrypt hash, never a shared password.
DO $seed$
BEGIN
    IF '${demoPasswordHash}' !~ '^\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'A BCrypt demoPasswordHash with cost >= 10 is required';
    END IF;
END $seed$;
CREATE TABLE IF NOT EXISTS public.demo_sms_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_e164 VARCHAR(20) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_demo_sms_phone_created ON public.demo_sms_outbox(phone_e164, created_at DESC);
ALTER TABLE public.demo_sms_outbox ENABLE ROW LEVEL SECURITY;

INSERT INTO public.users(id, phone_e164, display_name, password_hash, allow_messages_from_non_contacts) VALUES
    ('11111111-1111-1111-1111-111111111111', '+84901111111', 'Alice Nguyễn', '${demoPasswordHash}', true),
    ('22222222-2222-2222-2222-222222222222', '+84902222222', 'Bob Trần', '${demoPasswordHash}', true),
    ('33333333-3333-3333-3333-333333333333', '+84903333333', 'Charlie Lê', '${demoPasswordHash}', true),
    ('44444444-4444-4444-4444-444444444444', '+84904444444', 'David Phạm', '${demoPasswordHash}', false),
    ('55555555-5555-5555-5555-555555555555', '+84905555555', 'Eve Hoàng', '${demoPasswordHash}', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.user_devices(id, user_id, device_id_hash, device_name, trusted) VALUES
    ('11111111-1111-1111-1111-111111111101', '11111111-1111-1111-1111-111111111111', encode(sha256(convert_to('utechat-demo-device-1', 'UTF8')), 'hex'), 'Pixel 7 Pro', true),
    ('22222222-2222-2222-2222-222222222201', '22222222-2222-2222-2222-222222222222', encode(sha256(convert_to('utechat-demo-device-2', 'UTF8')), 'hex'), 'Samsung Galaxy S23', true),
    ('33333333-3333-3333-3333-333333333301', '33333333-3333-3333-3333-333333333333', encode(sha256(convert_to('utechat-demo-device-3', 'UTF8')), 'hex'), 'Xiaomi 13', true),
    ('44444444-4444-4444-4444-444444444401', '44444444-4444-4444-4444-444444444444', encode(sha256(convert_to('utechat-demo-device-4', 'UTF8')), 'hex'), 'Oppo Find X6', true),
    ('55555555-5555-5555-5555-555555555501', '55555555-5555-5555-5555-555555555555', encode(sha256(convert_to('utechat-demo-device-5', 'UTF8')), 'hex'), 'Vivo X90', true)
ON CONFLICT (user_id, device_id_hash) DO NOTHING;

INSERT INTO public.contacts(owner_id, contact_user_id, blocked) VALUES
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', false),
    ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', false),
    ('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', false),
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', false),
    ('22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', false),
    ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', false),
    ('11111111-1111-1111-1111-111111111111', '55555555-5555-5555-5555-555555555555', true)
ON CONFLICT (owner_id, contact_user_id) DO NOTHING;

INSERT INTO public.contact_requests(id, requester_id, addressee_id, status, responded_at) VALUES
    ('cccccccc-0001-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', 'PENDING', NULL),
    ('cccccccc-0002-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'ACCEPTED', now()),
    ('cccccccc-0003-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 'ACCEPTED', now()),
    ('cccccccc-0004-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', 'ACCEPTED', now())
ON CONFLICT DO NOTHING;

INSERT INTO public.conversations(id, type, title, direct_key, created_by) VALUES
    ('aaaaaaaa-1111-2222-0000-000000000001', 'DIRECT', NULL, '11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111'),
    ('bbbbbbbb-1111-3333-0000-000000000001', 'GROUP', 'Nhóm Đồ Án Mobile UTEChat', NULL, '11111111-1111-1111-1111-111111111111')
ON CONFLICT (id) DO NOTHING;
INSERT INTO public.conversation_members(conversation_id, user_id, role) VALUES
    ('aaaaaaaa-1111-2222-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'MEMBER'),
    ('aaaaaaaa-1111-2222-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'MEMBER'),
    ('bbbbbbbb-1111-3333-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ADMIN'),
    ('bbbbbbbb-1111-3333-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'MEMBER'),
    ('bbbbbbbb-1111-3333-0000-000000000001', '33333333-3333-3333-3333-333333333333', 'MEMBER')
ON CONFLICT (conversation_id, user_id) DO NOTHING;

INSERT INTO public.calls(id, conversation_id, caller_id, callee_id, media, state, end_reason, started_at, answered_at, ended_at)
VALUES ('dddddddd-0001-0000-0000-000000000001', 'aaaaaaaa-1111-2222-0000-000000000001', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
    'VOICE', 'ENDED', 'HANGUP', now() - interval '30 minutes', now() - interval '29 minutes', now() - interval '25 minutes')
ON CONFLICT (id) DO NOTHING;

-- Let BIGSERIAL allocate IDs so fixtures never leave the message sequence behind.
INSERT INTO public.messages(conversation_id, sender_id, client_id, type, text, call_id, latitude, longitude) VALUES
    ('aaaaaaaa-1111-2222-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ffffffff-0001-0000-0000-000000000001', 'TEXT', 'Chào Bob! Mình chuẩn bị kiểm thử WebRTC call và đồng bộ tin nhắn nhé.', NULL, NULL, NULL),
    ('aaaaaaaa-1111-2222-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'ffffffff-0002-0000-0000-000000000002', 'TEXT', 'Chào Alice! Mình đã sẵn sàng rồi.', NULL, NULL, NULL),
    ('bbbbbbbb-1111-3333-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ffffffff-0003-0000-0000-000000000003', 'TEXT', 'Chào cả nhóm, hệ thống CSDL Supabase 13 bảng đã sẵn sàng cho Phase 2 Walking Skeleton!', NULL, NULL, NULL),
    ('aaaaaaaa-1111-2222-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ffffffff-0004-0000-0000-000000000004', 'CALL', NULL, 'dddddddd-0001-0000-0000-000000000001', NULL, NULL),
    ('bbbbbbbb-1111-3333-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ffffffff-0005-0000-0000-000000000005', 'LOCATION', NULL, NULL, 10.8506, 106.7717)
ON CONFLICT (sender_id, client_id) DO NOTHING;
UPDATE public.conversations c SET last_message_id = (
    SELECT max(m.id) FROM public.messages m WHERE m.conversation_id = c.id
) WHERE c.id IN ('aaaaaaaa-1111-2222-0000-000000000001', 'bbbbbbbb-1111-3333-0000-000000000001');
UPDATE public.conversation_members cm SET
    last_delivered_message_id = GREATEST(cm.last_delivered_message_id, c.last_message_id),
    last_read_message_id = GREATEST(cm.last_read_message_id, CASE WHEN cm.user_id = '11111111-1111-1111-1111-111111111111' THEN c.last_message_id ELSE 0 END)
FROM public.conversations c WHERE c.id = cm.conversation_id
    AND c.id IN ('aaaaaaaa-1111-2222-0000-000000000001', 'bbbbbbbb-1111-3333-0000-000000000001');

INSERT INTO public.sync_events(user_id, event_type, entity_type, entity_id, payload_json)
SELECT cm.user_id, 'MESSAGE_CREATED', 'MESSAGE', m.id::text,
    jsonb_build_object(
        'id', m.id, 'clientId', m.client_id, 'conversationId', m.conversation_id, 'senderId', m.sender_id,
        'type', m.type, 'text', m.text, 'mediaUrl', NULL, 'mime', NULL, 'sizeBytes', NULL,
        'fileName', NULL, 'durationMs', NULL, 'width', NULL, 'height', NULL,
        'systemEvent', m.system_event, 'systemParams', m.system_params,
        'call', CASE WHEN ca.id IS NULL THEN NULL ELSE jsonb_build_object('id', ca.id,
            'state', ca.state, 'media', ca.media, 'endReason', ca.end_reason) END,
        'latitude', m.latitude, 'longitude', m.longitude, 'createdAt', m.created_at)
FROM public.messages m JOIN public.conversation_members cm ON cm.conversation_id = m.conversation_id
LEFT JOIN public.calls ca ON ca.id = m.call_id
WHERE m.client_id::text LIKE 'ffffffff-%'
    AND NOT EXISTS (SELECT 1 FROM public.sync_events e WHERE e.user_id = cm.user_id
        AND e.event_type = 'MESSAGE_CREATED' AND e.entity_id = m.id::text)
ON CONFLICT DO NOTHING;

INSERT INTO public.sync_events(user_id, event_type, entity_type, entity_id, payload_json)
SELECT u.id, 'CALL_UPDATED', 'CALL', ca.id::text,
    jsonb_build_object('callId', ca.id, 'state', ca.state, 'endReason', ca.end_reason)
FROM public.calls ca JOIN public.users u ON u.id IN (ca.caller_id, ca.callee_id)
WHERE ca.id = 'dddddddd-0001-0000-0000-000000000001' AND NOT EXISTS (
    SELECT 1 FROM public.sync_events e WHERE e.user_id = u.id AND e.event_type = 'CALL_UPDATED' AND e.entity_id = ca.id::text)
ON CONFLICT DO NOTHING;
