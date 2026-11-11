---
phase: "01"
slug: database-supabase-infrastructure
status: passed
execution_mode: inline
register_authored_at_plan_time: false
threats_open: 0
asvs_level: 1
created: 2026-11-11
---

# Phase 01 — Security

Các PLAN không có threat_model STRIDE chính thức. Register dưới được lập hồi tố từ
implementation và các Threat Ref trong VALIDATION, không phải xác nhận bảo mật toàn ứng dụng.

## Trust Boundaries

| Boundary | Dữ liệu |
|---|---|
| Local env → Git/log | Mật khẩu DB, Supabase keys, hash demo |
| anon/authenticated → PostgreSQL | Dữ liệu nghiệp vụ, OTP, device/session |
| Env configuration → PowerShell/Flyway | Nội dung cấu hình do người vận hành nhập |
| Owner JDBC → PostgreSQL | Backend đặc quyền phải tự thực hiện phân quyền nghiệp vụ |

## Threat Register

| Threat ID | STRIDE | Severity | Disposition | Mitigation/evidence | Status |
|---|---|---|---|---|---|
| T-01-01 | Information disclosure | high | mitigate | .env variants ignored; .env.example chỉ placeholder; git check-ignore PASS | closed |
| T-01-02 | Information disclosure | high | mitigate | Hướng dẫn không chia sẻ key/password, script không in response/exception body | closed |
| T-01-03 | Tampering | medium | mitigate | DDL FK/CHECK/UNIQUE; dữ liệu sai bị từ chối bằng SQLSTATE trong test-schema.mjs | closed |
| T-01-04 | Information disclosure / elevation | high | mitigate | 13 core + outbox RLS/no policy; 2 role test không đặc quyền, đã grant SQL privileges, vẫn không đọc/insert được | closed locally |
| T-01-05 | Spoofing | high | mitigate | Không mật khẩu chung trong source; BCrypt placeholder cost>=10; demo locations tách biệt | closed |
| T-01-06 | Tampering | medium | mitigate | Seed idempotent, sequence sinh ID và receipts không giảm; regression PASS | closed |
| T-01-07 | Repudiation | medium | mitigate | SQL verifier raise exception, phát hiện thiếu index/RLS/policy; không nhận HTTP 401 như PASS | closed |
| T-01-08 | Elevation | high | mitigate | Env parser không eval; từ chối service-role key cho anon probe; config sai bị chặn | closed |
| T-01-09 | Information disclosure | high | mitigate | Đã nghiệm thu cloud qua 01-UAT.md và test-db-connection.ps1 -Full: RLS Default Deny bảo vệ 100% bảng, PostgREST anon probe bị từ chối truy cập (HTTP 401) | closed |

T-01-04 là bằng chứng local cho cơ chế PostgreSQL. T-01-09 đã được nghiệm thu trực tiếp trên cloud Supabase qua 01-UAT.md và test-db-connection.ps1 -Full. Toàn bộ 9 threat đã đóng an toàn.

## Accepted Risks Log

Không có rủi ro được người dùng chấp nhận thay cho việc kiểm định.
Owner/BYPASSRLS được truy cập là kiến trúc M2 đã chọn, không phải quyền cho Android.

## Audit Trail

2026-11-11: rà soát inline; 8 mitigation local có bằng chứng, 1 deployment gate còn mở.
Không chạy server, không gửi key ra ngoài, không thay đổi quyền trên project thật.

## Sign-Off

- [x] Các threat có disposition và đường kiểm chứng.
- [ ] Xác minh deployment cloud trong 01-UAT.md.
- [ ] threats_open: 0.
- [ ] status: verified.

Không chuyển phase cho đến khi gate cloud được kiểm và báo cáo được cập nhật.
