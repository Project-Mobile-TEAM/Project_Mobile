-- Phase 7: Gỡ tin nhắn 24 giờ, Xóa tin ở phía bạn, Cảm xúc 6 emoji và Kho nhãn dán (D-01..D-13).

-- 1) Đánh dấu thu hồi vĩnh viễn tệp media khi tin nhắn gốc bị gỡ (chặn tải lại kể cả người gửi - D-02).
ALTER TABLE public.attachments
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;

-- 2) Bảng kho bộ nhãn dán hệ thống (bật RLS, không tạo policy công khai theo ADR-007).
CREATE TABLE public.sticker_packs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    thumbnail_url VARCHAR(500) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3) Bảng danh sách từng nhãn dán thuộc bộ nhãn dán.
CREATE TABLE public.stickers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pack_id UUID NOT NULL REFERENCES public.sticker_packs(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    label VARCHAR(100) NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    mime VARCHAR(50) NOT NULL DEFAULT 'image/png',
    width INTEGER NOT NULL DEFAULT 256 CHECK (width > 0),
    height INTEGER NOT NULL DEFAULT 256 CHECK (height > 0),
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_stickers_pack_code UNIQUE (pack_id, code)
);
CREATE INDEX idx_stickers_pack_sort ON public.stickers(pack_id, sort_order ASC);

-- 4) Bảng đồng bộ trạng thái cài/gỡ bộ nhãn dán theo từng tài khoản (COALESCE(installed, is_default)).
CREATE TABLE public.user_sticker_packs (
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    pack_id UUID NOT NULL REFERENCES public.sticker_packs(id) ON DELETE CASCADE,
    installed BOOLEAN NOT NULL DEFAULT TRUE,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_sticker_packs PRIMARY KEY (user_id, pack_id)
);
CREATE INDEX idx_user_sticker_packs_user ON public.user_sticker_packs(user_id);

-- 5) Mở rộng bảng messages: thêm recalled_at, sticker_id và cho phép type = 'STICKER'.
ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS recalled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sticker_id UUID REFERENCES public.stickers(id) ON DELETE SET NULL;

ALTER TABLE public.messages DROP CONSTRAINT IF EXISTS messages_type_check;
ALTER TABLE public.messages ADD CONSTRAINT messages_type_check
    CHECK (type IN ('TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'FILE', 'LOCATION', 'SYSTEM', 'CALL', 'STICKER'));

-- 6) Bảng đánh dấu tin nhắn bị xóa ở phía người dùng (Delete for Me - D-03).
CREATE TABLE public.message_deletions (
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_deletions PRIMARY KEY (user_id, message_id)
);
CREATE INDEX idx_message_deletions_message ON public.message_deletions(message_id);

-- 7) Bảng cảm xúc tin nhắn: đúng 6 emoji cố định, tối đa 1 cảm xúc/người/tin (D-06).
CREATE TABLE public.message_reactions (
    message_id BIGINT NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    emoji VARCHAR(16) NOT NULL CHECK (emoji IN ('❤️', '😂', '👍', '😮', '😢', '😡')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_reactions PRIMARY KEY (message_id, user_id)
);
CREATE INDEX idx_message_reactions_msg_created ON public.message_reactions(message_id, created_at ASC);

-- 8) Mở rộng sync_events.event_type thêm 3 sự kiện đồng bộ Phase 7 (D-13).
ALTER TABLE public.sync_events DROP CONSTRAINT IF EXISTS sync_events_event_type_check;
ALTER TABLE public.sync_events ADD CONSTRAINT sync_events_event_type_check
    CHECK (event_type IN (
        'MESSAGE_CREATED', 'CONVERSATION_UPDATED', 'CONTACT_REQUEST_CREATED',
        'CONTACT_REQUEST_UPDATED', 'CONTACT_UPDATED', 'RECEIPT_UPDATED',
        'CALL_UPDATED', 'DEVICE_REVOKED',
        'MESSAGE_RECALLED', 'MESSAGE_DELETED_FOR_ME', 'MESSAGE_REACTION_UPDATED'
    ));

-- 9) Bật RLS mặc định từ chối mọi truy cập công khai cho toàn bộ 5 bảng mới (ADR-007).
ALTER TABLE public.sticker_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.stickers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_sticker_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_deletions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_reactions ENABLE ROW LEVEL SECURITY;

-- 10) Nạp sẵn 2 bộ nhãn dán mẫu (1 bộ mặc định ute_daily + 1 bộ cửa hàng ute_campus, mỗi bộ 6 nhãn dán - D-08).
INSERT INTO public.sticker_packs (id, code, name, description, thumbnail_url, is_default, sort_order)
VALUES
    ('11111111-1111-4111-8111-111111111101', 'ute_daily', 'UTE Thường Ngày', 'Nhãn dán giao tiếp hằng ngày cài sẵn cho tài khoản mới', '/api/stickers/assets/ute_daily/hi.png', TRUE, 1),
    ('11111111-1111-4111-8111-111111111102', 'ute_campus', 'UTE Học Đường', 'Bộ nhãn dán sinh viên UTE tại cửa hàng nhãn dán', '/api/stickers/assets/ute_campus/deadline.png', FALSE, 2)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.stickers (id, pack_id, code, label, image_url, mime, width, height, sort_order)
VALUES
    -- Bộ 1: ute_daily (mặc định)
    ('22222222-2222-4222-8222-222222220101', '11111111-1111-4111-8111-111111111101', 'hi', 'Xin chào', '/api/stickers/assets/ute_daily/hi.png', 'image/png', 256, 256, 1),
    ('22222222-2222-4222-8222-222222220102', '11111111-1111-4111-8111-111111111101', 'ok', 'OK luôn', '/api/stickers/assets/ute_daily/ok.png', 'image/png', 256, 256, 2),
    ('22222222-2222-4222-8222-222222220103', '11111111-1111-4111-8111-111111111101', 'thanks', 'Cảm ơn', '/api/stickers/assets/ute_daily/thanks.png', 'image/png', 256, 256, 3),
    ('22222222-2222-4222-8222-222222220104', '11111111-1111-4111-8111-111111111101', 'haha', 'Cười xỉu', '/api/stickers/assets/ute_daily/haha.png', 'image/png', 256, 256, 4),
    ('22222222-2222-4222-8222-222222220105', '11111111-1111-4111-8111-111111111101', 'love', 'Thả tim', '/api/stickers/assets/ute_daily/love.png', 'image/png', 256, 256, 5),
    ('22222222-2222-4222-8222-222222220106', '11111111-1111-4111-8111-111111111101', 'fighting', 'Cố lên', '/api/stickers/assets/ute_daily/fighting.png', 'image/png', 256, 256, 6),
    -- Bộ 2: ute_campus (cửa hàng)
    ('22222222-2222-4222-8222-222222220201', '11111111-1111-4111-8111-111111111102', 'deadline', 'Chạy deadline', '/api/stickers/assets/ute_campus/deadline.png', 'image/png', 256, 256, 1),
    ('22222222-2222-4222-8222-222222220202', '11111111-1111-4111-8111-111111111102', 'coffee', 'Cà phê thôi', '/api/stickers/assets/ute_campus/coffee.png', 'image/png', 256, 256, 2),
    ('22222222-2222-4222-8222-222222220203', '11111111-1111-4111-8111-111111111102', 'study', 'Học bài nào', '/api/stickers/assets/ute_campus/study.png', 'image/png', 256, 256, 3),
    ('22222222-2222-4222-8222-222222220204', '11111111-1111-4111-8111-111111111102', 'exam', 'Qua môn rồi', '/api/stickers/assets/ute_campus/exam.png', 'image/png', 256, 256, 4),
    ('22222222-2222-4222-8222-222222220205', '11111111-1111-4111-8111-111111111102', 'sleepy', 'Buồn ngủ quá', '/api/stickers/assets/ute_campus/sleepy.png', 'image/png', 256, 256, 5),
    ('22222222-2222-4222-8222-222222220206', '11111111-1111-4111-8111-111111111102', 'celebrate', 'Ăn mừng', '/api/stickers/assets/ute_campus/celebrate.png', 'image/png', 256, 256, 6)
ON CONFLICT (pack_id, code) DO NOTHING;
