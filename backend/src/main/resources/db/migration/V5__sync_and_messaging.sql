-- Phase 5: Hỗ trợ đồng bộ không nhảy cóc (MVCC barrier_xid) và theo dõi ngưỡng lưu trữ 30 ngày cho sync_events.

-- Thêm cột barrier_xid để ghi nhận mốc giao dịch PostgreSQL tại thời điểm chèn sự kiện;
-- các dòng cũ có sẵn được gán '0'::xid8 để luôn hiển thị ngay cho mọi snapshot hiện tại.
ALTER TABLE public.sync_events
    ADD COLUMN IF NOT EXISTS barrier_xid xid8 NOT NULL DEFAULT '0'::xid8;

-- Đặt giá trị mặc định cho các lệnh INSERT sau này theo xmax của snapshot hiện hành.
ALTER TABLE public.sync_events
    ALTER COLUMN barrier_xid SET DEFAULT pg_snapshot_xmax(pg_current_snapshot());

-- Trigger ép cấp phát xid thật cho transaction hiện tại rồi lưu xmax snapshot vào barrier_xid,
-- bảo đảm /api/sync lọc barrier_xid <= pg_snapshot_xmin(pg_current_snapshot()) không bao giờ bỏ sót
-- sự kiện khi hai transaction commit ngược thứ tự sync_events.id.
CREATE OR REPLACE FUNCTION public.fn_sync_events_set_barrier()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Bảo đảm giao dịch hiện tại đã được gán xid thật trước khi đọc xmax snapshot.
    PERFORM pg_current_xact_id();
    NEW.barrier_xid := pg_snapshot_xmax(pg_current_snapshot());
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_events_set_barrier ON public.sync_events;
CREATE TRIGGER trg_sync_events_set_barrier
    BEFORE INSERT ON public.sync_events
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_sync_events_set_barrier();

-- Chỉ mục tổng hợp tăng tốc truy vấn phân trang /api/sync theo (user_id, id, barrier_xid).
CREATE INDEX IF NOT EXISTS idx_sync_events_barrier
    ON public.sync_events (user_id, id, barrier_xid);

-- Bảng đơn dòng lưu mốc con trỏ nhỏ nhất còn hiệu lực sau mỗi đợt dọn sync_events quá 30 ngày;
-- dùng để trả HTTP 410 SYNC_CURSOR_EXPIRED khi client offline quá lâu.
CREATE TABLE IF NOT EXISTS public.sync_retention_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    min_available_cursor BIGINT NOT NULL DEFAULT 0 CHECK (min_available_cursor >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Khởi tạo dòng cấu hình duy nhất với mốc 0 nếu chưa tồn tại.
INSERT INTO public.sync_retention_state (id, min_available_cursor)
VALUES (1, 0)
ON CONFLICT (id) DO NOTHING;

-- Bật Row Level Security mặc định từ chối mọi truy cập qua Supabase Public Data API (ADR-007).
ALTER TABLE public.sync_retention_state ENABLE ROW LEVEL SECURITY;
