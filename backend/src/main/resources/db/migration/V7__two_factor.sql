-- 2FA TOTP (ADR-004). Bật khi totp_credentials.confirmed_at khác NULL; khoá bí mật mã hoá AES-GCM, mã dự phòng chỉ lưu bản băm.
CREATE TABLE public.totp_credentials (
    user_id UUID PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    secret_enc TEXT NOT NULL,
    confirmed_at TIMESTAMPTZ,
    last_used_step BIGINT, -- Chống dùng lại cùng một mã trong cửa sổ ±1 nhịp.
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE public.backup_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    CONSTRAINT uq_backup_code UNIQUE (user_id, code_hash)
);
ALTER TABLE public.totp_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.backup_codes ENABLE ROW LEVEL SECURITY;

-- Khoá chống dò mã 2FA dùng chung ThrottleService với đăng nhập mật khẩu.
ALTER TABLE public.auth_throttles DROP CONSTRAINT IF EXISTS auth_throttles_purpose_check;
ALTER TABLE public.auth_throttles ADD CONSTRAINT auth_throttles_purpose_check
    CHECK (purpose IN ('PASSWORD_LOGIN', 'OTP_VERIFY', 'PASSWORD_RESET', 'TWO_FACTOR'));
