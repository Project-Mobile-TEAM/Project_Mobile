-- Tách quan hệ bạn bè khỏi hành vi chặn để hai người cùng chặn rồi bỏ chặn không mất tình bạn.
-- Cột tạm nullable để nhận diện đúng dữ liệu cũ, không ghi đè cờ đã được service Phase 4 cập nhật.
ALTER TABLE public.contacts ADD COLUMN IF NOT EXISTS is_friend BOOLEAN;

-- Chuyển các dòng bạn bè cũ/demo sang cờ mới; dòng blocked cũ được coi là block-only theo schema trước Phase 4.
UPDATE public.contacts SET is_friend = (NOT blocked) WHERE is_friend IS NULL;

-- Dòng mới mặc định chưa là bạn; sau backfill mọi dòng phải có giá trị rõ ràng, không còn NULL.
ALTER TABLE public.contacts ALTER COLUMN is_friend SET DEFAULT FALSE;
ALTER TABLE public.contacts ALTER COLUMN is_friend SET NOT NULL;

-- Ghi rõ ý nghĩa để người xem bảng trên Supabase không suy ra tình bạn chỉ từ blocked.
COMMENT ON COLUMN public.contacts.is_friend IS 'Quan hệ bạn bè đã chấp nhận; độc lập với blocked, chỉ hủy kết bạn mới xóa cờ';
