---
phase: "01"
slug: database-supabase-infrastructure
status: validated
nyquist_compliant: false
wave_0_complete: true
created: 2026-11-10
updated: 2026-11-11
execution_mode: inline
---

# Phase 01 — Validation

Audit đã thực hiện; local implementation pass, nghiệm thu Supabase còn pending.
status validated mô tả việc rà soát contract, không phải phase đã đạt mục tiêu cloud.

## Test Infrastructure

| Loại | Lệnh / bằng chứng |
|---|---|
| PostgreSQL thực thi trong bộ nhớ | node backend/scripts/test-schema.mjs <temporary-node_modules-directory> |
| Dependencies chỉ dành test | @electric-sql/pglite@0.3.7, bcryptjs@3.0.2; cách cài ở docs/SUPABASE_SETUP.md mục 10 |
| Quick cấu hình offline | powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/test-db-connection.ps1 -Quick |
| Cloud diagnostics | powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/test-db-connection.ps1 -Full -EnvFile .env |
| SQL sau migrate | psql -v ON_ERROR_STOP=1 -f backend/scripts/verify-schema.sql |
| Demo profile | SET utechat.verify_demo = 'true' trong cùng session trước verify-schema.sql |

## Per-Task Verification Map

Tất cả task cùng phục vụ requirement trong PLAN: “Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu”.
Các nhãn REQ-infra-* trong bản cũ là nhãn kiểm thử nội bộ, không phải requirement mới.

| Task | Wave | Threat | Kiểm tra | Local | Cloud |
|---|---|---|---|---|---|
| 01-01-01 | 1 | T-01-01 | check-ignore env + Quick TLS/ports | PASS | N/A |
| 01-01-02 | 1 | T-01-02 | Hướng dẫn dual port, RLS, secret và Flyway | PASS | Provisioning pending |
| 01-02-01 | 1 | T-01-03 | PGlite DDL 13 bảng, phone/self/FK/unique/watermark | PASS | Migrate pending |
| 01-02-02 | 1 | T-01-04 | 2 role không đặc quyền, RLS trên mọi bảng, index và mutation tests | PASS | Role/Data API pending |
| 01-03-01 | 2 | T-01-05 | BCrypt compare thật, 5 users/devices | PASS | Seed pending |
| 01-03-02 | 2 | T-01-06 | Seed replay, sequence, fixtures và receipts | PASS | Nghiệp vụ Phase 3–6 |
| 01-04-01 | 3 | T-01-07 | Verifier production/demo và 3 mutation tests | PASS | SQL assertions pending |
| 01-04-02 | 3 | T-01-08/09 | Quick PASS; bad port/unresolved ref/placeholder Full bị từ chối | PASS | Full pending |

## Executed Evidence

- 16/16 PostgreSQL WASM checks PASS, gồm BCrypt và RLS với dữ liệu không rỗng.
- PowerShell Quick exit 0.
- Port sai, tham chiếu env chưa định nghĩa và Full dùng placeholder: exit 1 đúng kỳ vọng.
- git diff --check PASS.
- Test dùng database trong bộ nhớ. Không có .env thật, Flyway CLI, SQL login hoặc cloud probe thành công.
- Windows/PGlite có thông báo path từ runtime, nhưng tất cả assert pass và process exit 0.

## Wave 0

- [x] Migration/demo và backend/scripts đã có.
- [x] .env.example tồn tại, không chứa credential thật.
- [x] Suite SQL và script cấu hình có lệnh chạy lại.
- [x] 8 task được ánh xạ về bằng chứng kiểm tra.
- [ ] Nghiệm thu deployment thật: xem 01-UAT.md.

## Manual / Credential-Gated Verifications

| Kiểm tra | Expected | Lý do pending |
|---|---|---|
| Provision project + dual-pooler SQL login | Đúng project, session5432/runtime6543 đăng nhập và TLS thực tế | Chưa có cấu hình local thật |
| Flyway + verify-schema.sql | History success; production13, demo14 nếu bật demo; constraints/RLS/seed PASS | Chưa có project/CLI |
| PostgREST anon trên dữ liệu đã seed | Không thấy dòng; 401/empty database không đủ chứng minh | Chưa có project/key |

## Validation Audit 2026-11-11

8/8 local task checks covered. 3 cloud evidence gaps escalated tới UAT, chưa resolved.
Sửa sai khác contract cũ: seed nằm db/demo; production13/demo14; Quick offline; HTTP401 inconclusive.
Không có UI hoặc prior-phase regression trong Phase 1. Giữ nyquist_compliant false tới khi có nghiệm thu cloud.
