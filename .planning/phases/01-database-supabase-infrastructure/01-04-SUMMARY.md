---
phase: 01-database-supabase-infrastructure
plan: "04"
subsystem: testing
status: complete
completed: 2026-11-11
execution_mode: inline
tasks_completed: 2
requirements-completed: []
requirements-addressed:
  - "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
key-files:
  created: ["backend/scripts/verify-schema.sql","backend/scripts/test-db-connection.ps1","backend/scripts/test-schema.mjs"]
  modified: ["docs/SUPABASE_SETUP.md"]
coverage:
  - id: P04-LOCAL
    description: "Kiểm chứng SQL và chẩn đoán cấu hình không báo PASS giả"
    requirement: "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
    verification:
      - kind: integration
        ref: "16/16 PostgreSQL checks + Quick PASS + 3 cấu hình sai bị từ chối đúng mã thoát"
        status: pass
    human_judgment: false
  - id: P04-CLOUD
    description: "Nghiệm thu trên project Supabase thật"
    verification: []
    human_judgment: true
    rationale: "Cần project và credentials cấu hình cục bộ; chưa có bằng chứng cloud."
---

# 01-04: Kiểm chứng SQL và chẩn đoán cấu hình không báo PASS giả

## Accomplishments

- SQL assertions: 13 core hoặc 14 demo, RLS/policy/index/constraint/privacy/fixture; lỗi trả exception.
- Quick offline; Full từ chối placeholder, probe TCP và anon Data API, không in thông tin nhạy cảm.
- 16 kiểm tra PostgreSQL WASM pass, kèm kiểm tra verifier phát hiện index/RLS/policy bị phá.

## Task Commits

Tasks 01-04-01 và 01-04-02 được lưu cùng một commit theo deliverable: `eaa6806`.
Đây là commit theo plan, không phải hai commit theo task.

## Deviations from Plan

- Rule 2: thêm PGlite/bcryptjs chỉ trong thư mục tạm để thực thi SQL khi không có PostgreSQL local/cloud; không thêm runtime dependency.
- Rule 1: HTTP 401 là inconclusive, không phải bằng chứng RLS; 200 [] cần đối chiếu SQL.
- Rule 1: xử lý exception không có Response an toàn dưới StrictMode.
- Full network, SQL login và Flyway cloud chưa chạy vì thiếu cấu hình thật.

## Verification

16/16 PostgreSQL checks + Quick PASS + 3 cấu hình sai bị từ chối đúng mã thoát. Chạy ngày 2026-11-11 (Asia/Saigon).
Phần implementation local đã xong; status complete ở đây chỉ áp dụng các task local.
Yêu cầu hạ tầng tổng thể chưa được đánh dấu hoàn thành; xem 01-VERIFICATION.md.

## Threat Flags

Khóa service-role không được dùng cho anon probe; không chạy nội dung env như lệnh. Cloud chưa được nghiệm thu.

## Next Phase Readiness

Chờ migration, SQL assertions và Data API probe trên Supabase thật.
Không tự chuyển Phase 2.
