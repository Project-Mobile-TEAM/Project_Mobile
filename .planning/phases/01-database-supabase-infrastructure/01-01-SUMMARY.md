---
phase: 01-database-supabase-infrastructure
plan: "01"
subsystem: infra
status: complete
completed: 2026-11-11
execution_mode: inline
tasks_completed: 2
requirements-completed: []
requirements-addressed:
  - "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
key-files:
  created: [".env.example","docs/SUPABASE_SETUP.md"]
  modified: [".gitignore"]
coverage:
  - id: P01-LOCAL
    description: "Cấu hình an toàn và hướng dẫn Supabase M2"
    requirement: "Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu"
    verification:
      - kind: integration
        ref: "git check-ignore + kiểm tra nội dung cấu hình/tài liệu + Quick PowerShell"
        status: pass
    human_judgment: false
  - id: P01-CLOUD
    description: "Nghiệm thu trên project Supabase thật"
    verification: []
    human_judgment: true
    rationale: "Cần project và credentials cấu hình cục bộ; chưa có bằng chứng cloud."
---

# 01-01: Cấu hình an toàn và hướng dẫn Supabase M2

## Accomplishments

- Cấu hình tách Flyway session pooler 5432 và JDBC transaction pooler 6543, TLS và prepareThreshold=0.
- .env và các biến thể bị Git bỏ qua; .env.example chỉ có placeholder.
- Hướng dẫn tạo project, nạp env như dữ liệu, production/demo migration và nghiệm thu.

## Task Commits

Tasks 01-01-01 và 01-01-02 được lưu cùng một commit theo deliverable: `b8411a4`.
Đây là commit theo plan, không phải hai commit theo task.

## Deviations from Plan

- Không tạo hay đăng nhập project Supabase vì không có phiên/credentials. Hướng dẫn không thay thế bằng chứng cloud.
- Làm rõ RLS default deny chặn dòng, không tắt toàn bộ HTTP Data API; không coi TCP là SQL login.

## Verification

git check-ignore + kiểm tra nội dung cấu hình/tài liệu + Quick PowerShell. Chạy ngày 2026-11-11 (Asia/Saigon).
Phần implementation local đã xong; status complete ở đây chỉ áp dụng các task local.
Yêu cầu hạ tầng tổng thể chưa được đánh dấu hoàn thành; xem 01-VERIFICATION.md.

## Threat Flags

Không đưa credential thật vào Git; quyền truy cập cloud chưa xác minh.

## Next Phase Readiness

Chờ migration, SQL assertions và Data API probe trên Supabase thật.
Không tự chuyển Phase 2.
