---
phase: 01-database-supabase-infrastructure
plan: "03"
subsystem: database
status: complete
completed: 2026-11-11
execution_mode: inline
tasks_completed: 2
requirements-completed: []
requirements-addressed:
  - "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
key-files:
  created: ["backend/src/main/resources/db/demo/V2__seed_demo_data.sql"]
  modified: []
coverage:
  - id: P03-LOCAL
    description: "Seed demo riêng với BCrypt do người chạy cung cấp"
    requirement: "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
    verification:
      - kind: integration
        ref: "backend/scripts/test-schema.mjs — BCrypt, fixture, sequence, replay/receipt đều PASS"
        status: pass
    human_judgment: false
  - id: P03-CLOUD
    description: "Nghiệm thu trên project Supabase thật"
    verification: []
    human_judgment: true
    rationale: "Cần project và credentials cấu hình cục bộ; chưa có bằng chứng cloud."
---

# 01-03: Seed demo riêng với BCrypt do người chạy cung cấp

## Accomplishments

- 5 users/devices, 3 cặp bạn bè, Alice chặn Eve, Bob mời David và David tắt nhận tin người lạ.
- 2 hội thoại, 5 thành viên, 5 tin TEXT/CALL/LOCATION, cuộc gọi đã kết thúc và sync_events JSONB.
- Seed chạy lại không nhân bản message/event hoặc lùi mốc read/delivered.

## Task Commits

Tasks 01-03-01 và 01-03-02 được lưu cùng một commit theo deliverable: `fa6ec05`.
Đây là commit theo plan, không phải hai commit theo task.

## Deviations from Plan

- Rule 2: db/demo tách khỏi production locations; bỏ mật khẩu Password123! cố định, bắt buộc placeholder demoPasswordHash BCrypt cost>=10.
- Rule 1: để BIGSERIAL cấp ID, tránh seed ID cố định làm sequence va chạm; GREATEST bảo toàn watermark khi replay.
- Avatar NULL và không seed storage_path giả. Upload media thật/gọi WebRTC phải kiểm ở phase nghiệp vụ.

## Verification

backend/scripts/test-schema.mjs — BCrypt, fixture, sequence, replay/receipt đều PASS. Chạy ngày 2026-11-11 (Asia/Saigon).
Phần implementation local đã xong; status complete ở đây chỉ áp dụng các task local.
Yêu cầu hạ tầng tổng thể chưa được đánh dấu hoàn thành; xem 01-VERIFICATION.md.

## Threat Flags

Không có mật khẩu/hash chung trong source; demo database phải riêng, không bật demo locations ở production.

## Next Phase Readiness

Chờ migration, SQL assertions và Data API probe trên Supabase thật.
Không tự chuyển Phase 2.
