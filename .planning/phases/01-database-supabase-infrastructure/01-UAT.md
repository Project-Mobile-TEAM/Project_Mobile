---
status: testing
phase: 01-database-supabase-infrastructure
source: [01-VERIFICATION.md]
started: "2026-11-10T18:36:49.339Z"
updated: "2026-11-10T18:36:49.339Z"
---

## Current Test

number: 1
name: Project Supabase và cấu hình local
expected: |
  Có project Supabase riêng và .env đã điền hostname, ports, DB credentials, project URL và anon key.
  Không gửi password/key vào chat. Chỉ báo đã cấu hình để tiếp tục kiểm bằng CLI.
awaiting: user response

## Tests

### 1. Project Supabase và SQL login dual-port

expected: |
  Làm theo docs/SUPABASE_SETUP.md mục 2–5. Session pooler 5432 và runtime6543 phải đăng nhập SQL
  được với TLS; TCP reachable một mình không đủ. Chưa có backend JDBC trong Phase 1.
result: pending

### 2. Flyway migrations và SQL assertions

expected: |
  Làm theo mục6. Production locations chỉ db/migration: 13 core, không demo users/outbox.
  Demo dùng database riêng, DEMO_PASSWORD_HASH hợp lệ, locations thêm db/demo: 14 bảng ứng dụng.
  Flyway history success; verify-schema.sql PASS đúng profile.
  Bảng flyway_schema_history là bảng kỹ thuật, không tính vào 13/14.
result: pending

### 3. RLS và Data API thực tế

expected: |
  Có dữ liệu demo trước khi probe. Full -EnvFile .env không thấy row qua anon Data API.
  Đối chiếu SQL relrowsecurity=true, 0 policy trên application tables, không FORCE.
  Xác minh authenticated role không đặc quyền cũng default deny; owner JDBC vẫn truy cập được.
  HTTP401 hay [] trên database rỗng là inconclusive.
result: pending

## Summary

total: 3
passed: 0
issues: 0
pending: 3
skipped: 0

## Gaps

Chưa có credentials/project cloud để chạy3 bước. Đây là checkpoint môi trường,
không phải3 lỗi SQL đã phát hiện. 16 kiểm tra SQL local đã PASS.

## Resume

Sau khi cấu hình .env local: $gsd-verify-work 1.
Giữ Phase 1 pending cho tới khi ghi bằng chứng thực, cập nhật SECURITY/VALIDATION và verification.
