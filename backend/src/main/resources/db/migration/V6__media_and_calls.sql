-- V6: Bổ sung schema WebRTC Calls và Media Attachments Idempotency

-- 1. Bổ sung các cột định danh thiết bị và thời hạn cho cuộc gọi thoại WebRTC
ALTER TABLE public.calls
    ADD COLUMN IF NOT EXISTS caller_device_id_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS callee_device_id_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '45 seconds'),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS caller_alive_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS callee_alive_until TIMESTAMPTZ;

-- Backfill dữ liệu cho các cuộc gọi cũ nếu có
UPDATE public.calls
SET expires_at = started_at + interval '45 seconds'
WHERE expires_at <= started_at;

UPDATE public.calls
SET caller_alive_until = now() + interval '60 seconds',
    callee_alive_until = now() + interval '60 seconds'
WHERE state = 'ACTIVE' AND caller_alive_until IS NULL;

-- 2. Bổ sung upload_client_id vào attachments để hỗ trợ upload idempotent
ALTER TABLE public.attachments
    ADD COLUMN IF NOT EXISTS upload_client_id UUID;

-- 3. Các index ràng buộc nghiệp vụ (chống race condition ở tầng database)
-- Mỗi uploader chỉ có 1 attachment ứng với 1 upload_client_id
CREATE UNIQUE INDEX IF NOT EXISTS uq_attachments_upload_client
    ON public.attachments (uploader_id, upload_client_id)
    WHERE upload_client_id IS NOT NULL;

-- Người gọi chỉ được tham gia tối đa 1 cuộc gọi đang RINGING hoặc ACTIVE
CREATE UNIQUE INDEX IF NOT EXISTS uq_calls_active_caller
    ON public.calls (caller_id)
    WHERE state IN ('RINGING', 'ACTIVE');

-- Người nhận chỉ được tham gia tối đa 1 cuộc gọi đang RINGING hoặc ACTIVE
CREATE UNIQUE INDEX IF NOT EXISTS uq_calls_active_callee
    ON public.calls (callee_id)
    WHERE state IN ('RINGING', 'ACTIVE');

-- Index phục vụ background scheduler quét dọn cuộc gọi hết hạn 45s mà không ai nhấc máy
CREATE INDEX IF NOT EXISTS idx_calls_ringing_expires
    ON public.calls (expires_at)
    WHERE state = 'RINGING';
    
-- Mỗi cuộc gọi chỉ được gắn với đúng 1 message thông báo trong đoạn chat
CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_call_id
    ON public.messages (call_id)
    WHERE call_id IS NOT NULL;