-- UTEChat V1 core schema. PostgreSQL 15+; gen_random_uuid() is built in.
-- Demo-only tables and data live in db/demo, outside production Flyway locations.
CREATE TABLE public.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_e164 VARCHAR(20) UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(500),
    password_hash VARCHAR(255) NOT NULL,
    discoverable BOOLEAN NOT NULL DEFAULT TRUE,
    allow_messages_from_non_contacts BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETED')),
    CONSTRAINT chk_users_phone_format CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{7,14}$')
);
CREATE TABLE public.user_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    device_id_hash VARCHAR(64) NOT NULL,
    device_name VARCHAR(100),
    platform VARCHAR(20) NOT NULL DEFAULT 'ANDROID',
    trusted BOOLEAN NOT NULL DEFAULT FALSE,
    token_valid_after TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_user_device UNIQUE (user_id, device_id_hash)
);
CREATE TABLE public.otp_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_e164 VARCHAR(20) NOT NULL,
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('REGISTER', 'LOGIN', 'PASSWORD_RESET')),
    device_id_hash VARCHAR(64) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE public.auth_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES public.users(id) ON DELETE CASCADE,
    device_id_hash VARCHAR(64) NOT NULL,
    method VARCHAR(20) NOT NULL CHECK (method IN ('PASSWORD', 'OTP', 'RESET')),
    state VARCHAR(20) NOT NULL CHECK (state IN ('NEED_OTP', 'COMPLETE', 'FAILED', 'LOCKED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE public.auth_throttles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type VARCHAR(20) NOT NULL CHECK (scope_type IN ('PHONE', 'DEVICE', 'PHONE_DEVICE')),
    scope_key_hash VARCHAR(64) NOT NULL,
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('PASSWORD_LOGIN', 'OTP_VERIFY', 'PASSWORD_RESET')),
    failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT idx_auth_throttles_scope UNIQUE (scope_type, scope_key_hash, purpose)
);
CREATE TABLE public.contact_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    addressee_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ,
    CONSTRAINT chk_contact_requests_no_self CHECK (requester_id <> addressee_id)
);
CREATE TABLE public.contacts (
    owner_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    contact_user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_contacts PRIMARY KEY (owner_id, contact_user_id),
    CONSTRAINT chk_contacts_no_self CHECK (owner_id <> contact_user_id)
);
CREATE TABLE public.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(20) NOT NULL CHECK (type IN ('DIRECT', 'GROUP')),
    title VARCHAR(100),
    avatar_url VARCHAR(500),
    direct_key VARCHAR(100) UNIQUE,
    created_by UUID REFERENCES public.users(id) ON DELETE SET NULL,
    last_message_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_conversation_type CHECK (
        (type = 'DIRECT' AND direct_key IS NOT NULL) OR
        (type = 'GROUP' AND direct_key IS NULL AND title IS NOT NULL)
    )
);
CREATE TABLE public.conversation_members (
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MEMBER')),
    visible_from_message_id BIGINT NOT NULL DEFAULT 0 CHECK (visible_from_message_id >= 0),
    last_delivered_message_id BIGINT NOT NULL DEFAULT 0,
    last_read_message_id BIGINT NOT NULL DEFAULT 0 CHECK (last_read_message_id >= 0),
    muted BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    CONSTRAINT pk_conversation_members PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT chk_watermarks_monotonic CHECK (last_delivered_message_id >= last_read_message_id)
);
CREATE TABLE public.attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    uploader_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('IMAGE', 'AUDIO', 'VIDEO', 'FILE')),
    mime VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    storage_path VARCHAR(500) NOT NULL,
    original_name VARCHAR(255),
    duration_ms INTEGER CHECK (duration_ms >= 0),
    width INTEGER CHECK (width > 0),
    height INTEGER CHECK (height > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE public.calls (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    caller_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    callee_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    media VARCHAR(20) NOT NULL DEFAULT 'VOICE' CHECK (media = 'VOICE'),
    state VARCHAR(20) NOT NULL DEFAULT 'RINGING' CHECK (state IN ('RINGING', 'ACTIVE', 'ENDED', 'MISSED', 'REJECTED', 'BUSY', 'FAILED')),
    end_reason VARCHAR(20) CHECK (end_reason IN ('HANGUP', 'TIMEOUT', 'REJECT', 'BUSY', 'OFFLINE', 'ERROR')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    CONSTRAINT chk_calls_no_self CHECK (caller_id <> callee_id)
);
CREATE TABLE public.messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    client_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'FILE', 'LOCATION', 'SYSTEM', 'CALL')),
    text VARCHAR(4000),
    attachment_id UUID REFERENCES public.attachments(id) ON DELETE SET NULL,
    call_id UUID REFERENCES public.calls(id) ON DELETE SET NULL,
    system_event VARCHAR(50),
    system_params TEXT,
    latitude DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_messages_client UNIQUE (sender_id, client_id),
    CONSTRAINT uq_messages_attachment UNIQUE (attachment_id)
);
-- Added after messages to avoid the conversations/messages dependency cycle.
ALTER TABLE public.conversations ADD CONSTRAINT fk_conversations_last_message
    FOREIGN KEY (last_message_id) REFERENCES public.messages(id) ON DELETE SET NULL;

CREATE TABLE public.sync_events (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL CHECK (event_type IN (
        'MESSAGE_CREATED', 'CONVERSATION_UPDATED', 'CONTACT_REQUEST_CREATED',
        'CONTACT_REQUEST_UPDATED', 'CONTACT_UPDATED', 'RECEIPT_UPDATED', 'CALL_UPDATED', 'DEVICE_REVOKED'
    )),
    entity_type VARCHAR(50) NOT NULL CHECK (entity_type IN (
        'MESSAGE', 'CONVERSATION', 'CONTACT_REQUEST', 'CONTACT', 'RECEIPT', 'CALL', 'DEVICE'
    )),
    entity_id VARCHAR(100) NOT NULL,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Supports the backend's daily 30-day retention job (implemented in Phase 5).
COMMENT ON TABLE public.sync_events IS 'Per-user sync log; retain 30 days; backend schedules cleanup';

CREATE INDEX idx_users_phone ON public.users(phone_e164) WHERE phone_e164 IS NOT NULL;
CREATE INDEX idx_user_devices_user ON public.user_devices(user_id);
CREATE INDEX idx_otp_challenges_phone_created ON public.otp_challenges(phone_e164, created_at DESC);
CREATE UNIQUE INDEX idx_contact_requests_pending_pair
    ON public.contact_requests(LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id))
    WHERE status = 'PENDING';
CREATE INDEX idx_contact_requests_addressee ON public.contact_requests(addressee_id, status);
CREATE INDEX idx_contact_requests_requester ON public.contact_requests(requester_id, status);
CREATE INDEX idx_contacts_owner_blocked ON public.contacts(owner_id, blocked);
CREATE INDEX idx_contacts_target ON public.contacts(contact_user_id);
CREATE INDEX idx_conversations_last_msg ON public.conversations(last_message_id DESC NULLS LAST);
CREATE INDEX idx_conv_members_user ON public.conversation_members(user_id) WHERE left_at IS NULL;
CREATE INDEX idx_attachments_uploader ON public.attachments(uploader_id);
CREATE INDEX idx_calls_conversation ON public.calls(conversation_id, started_at DESC);
CREATE INDEX idx_calls_caller ON public.calls(caller_id);
CREATE INDEX idx_calls_callee ON public.calls(callee_id);
CREATE INDEX idx_messages_conversation_id_desc ON public.messages(conversation_id, id DESC);
CREATE INDEX idx_sync_events_user_cursor ON public.sync_events(user_id, id);
CREATE INDEX idx_sync_events_created ON public.sync_events(created_at);

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.otp_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auth_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auth_throttles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.contact_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_events ENABLE ROW LEVEL SECURITY;
