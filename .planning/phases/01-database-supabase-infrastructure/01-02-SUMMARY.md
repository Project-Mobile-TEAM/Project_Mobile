---
phase: 01-database-supabase-infrastructure
plan: "02"
subsystem: database
status: complete
completed: 2026-11-11
execution_mode: inline
tasks_completed: 2
requirements-completed: []
requirements-addressed:
  - "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
key-files:
  created: ["backend/src/main/resources/db/migration/V1__initial_schema.sql"]
  modified: []
coverage:
  - id: P02-LOCAL
    description: "Migration PostgreSQL cho đúng 13 bảng core V1"
    requirement: "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
    verification:
      - kind: integration
        ref: "backend/scripts/test-schema.mjs — migration, FK/CHECK/UNIQUE và role RLS đều PASS"
        status: pass
    human_judgment: false
  - id: P02-CLOUD
    description: "Nghiệm thu trên project Supabase thật"
    verification: []
    human_judgment: true
    rationale: "Cần project và credentials cấu hình cục bộ; chưa có bằng chứng cloud."
---

# 01-02: Migration PostgreSQL cho đúng 13 bảng core V1

## Accomplishments

- Tạo FK/CHECK/UNIQUE, khóa cặp lời mời PENDING không phân biệt chiều và PHONE_DEVICE throttles.
- Bật RLS trên 13 bảng, không policy/FORCE; giải quyết vòng FK conversations/messages.
- Chỉ mục lịch sử tin nhắn, cursor người dùng và retention timestamp.

## Task Commits

Tasks 01-02-01 và 01-02-02 được lưu cùng một commit theo deliverable: `1478255`.
Đây là commit theo plan, không phải hai commit theo task.

## Deviations from Plan

- Rule 2: chuyển demo_sms_outbox khỏi core sang db/demo, khớp ERD và tránh bảng OTP demo xuất hiện trong production.
- Dùng gen_random_uuid() tích hợp PostgreSQL 15+, không thêm pgcrypto.
- Chỉ tạo index/comment cho retention 30 ngày; scheduler thuộc Phase 5.

## Verification

backend/scripts/test-schema.mjs — migration, FK/CHECK/UNIQUE và role RLS đều PASS. Chạy ngày 2026-11-11 (Asia/Saigon).
Phần implementation local đã xong; status complete ở đây chỉ áp dụng các task local.
Yêu cầu hạ tầng tổng thể chưa được đánh dấu hoàn thành; xem 01-VERIFICATION.md.

## Threat Flags

RLS được kiểm bằng role không đặc quyền trên dữ liệu đã seed. Cấu hình Supabase thực tế còn chờ.

## Next Phase Readiness

Chờ migration, SQL assertions và Data API probe trên Supabase thật.
Không tự chuyển Phase 2.
