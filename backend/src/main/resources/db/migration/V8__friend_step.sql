-- Bước nhận diện bạn bè trên thiết bị lạ (F-04, ADR-005): sau OTP hoặc 2FA, người dùng chọn đúng 3 bạn trong 15 người.
-- Đáp án chỉ lưu bản băm; mỗi bộ hiệu lực 2 phút, dùng một lần; sai 3 lần thì khoá bước này 15 phút theo tài khoản.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS friend_step_locked_until TIMESTAMPTZ;

CREATE TABLE public.friend_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    auth_session_id UUID NOT NULL REFERENCES public.auth_sessions(id) ON DELETE CASCADE,
    answer_hash VARCHAR(64) NOT NULL,
    attempts_used INTEGER NOT NULL DEFAULT 0 CHECK (attempts_used >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_friend_challenges_session ON public.friend_challenges(auth_session_id);
ALTER TABLE public.friend_challenges ENABLE ROW LEVEL SECURITY;
