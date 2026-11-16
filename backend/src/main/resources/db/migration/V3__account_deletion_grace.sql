-- Xoá tài khoản kiểu "chờ 15 ngày" (F-08): khác NULL nghĩa là tài khoản đang chờ xoá vĩnh viễn vào thời điểm này.
-- Trong thời gian chờ, status vẫn ACTIVE để chủ tài khoản đăng nhập lại và huỷ yêu cầu xoá.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS deletion_scheduled_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_deletion_scheduled ON public.users (deletion_scheduled_at)
    WHERE deletion_scheduled_at IS NOT NULL AND status = 'ACTIVE';
