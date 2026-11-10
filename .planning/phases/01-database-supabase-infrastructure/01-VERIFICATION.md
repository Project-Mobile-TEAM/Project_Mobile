---
phase: 01-database-supabase-infrastructure
verified: "2026-11-10T18:36:49.323Z"
status: human_needed
score: "5/8 observable truths verified locally; 3 cloud checks pending"
execution_mode: inline
covered_files:
  - .env.example
  - .gitignore
  - .planning/REQUIREMENTS.md
  - .planning/ROADMAP.md
  - .planning/phases/01-database-supabase-infrastructure/01-01-PLAN.md
  - .planning/phases/01-database-supabase-infrastructure/01-01-SUMMARY.md
  - .planning/phases/01-database-supabase-infrastructure/01-02-PLAN.md
  - .planning/phases/01-database-supabase-infrastructure/01-02-SUMMARY.md
  - .planning/phases/01-database-supabase-infrastructure/01-03-PLAN.md
  - .planning/phases/01-database-supabase-infrastructure/01-03-SUMMARY.md
  - .planning/phases/01-database-supabase-infrastructure/01-04-PLAN.md
  - .planning/phases/01-database-supabase-infrastructure/01-04-SUMMARY.md
  - .planning/phases/01-database-supabase-infrastructure/01-REVIEW.md
  - .planning/phases/01-database-supabase-infrastructure/01-SECURITY.md
  - .planning/phases/01-database-supabase-infrastructure/01-VALIDATION.md
  - backend/scripts/test-db-connection.ps1
  - backend/scripts/test-schema.mjs
  - backend/scripts/verify-schema.sql
  - backend/src/main/resources/db/demo/V2__seed_demo_data.sql
  - backend/src/main/resources/db/migration/V1__initial_schema.sql
  - docs/ARCHITECTURE_DECISIONS.md
  - docs/ERD.md
  - docs/SUPABASE_SETUP.md
covered_digest: "v1:sha256:9648a953052a33f68afffabb8e83d73b68a31ac40da882d6a85b39e7f2fa674b"
behavior_unverified: 3
behavior_unverified_items:
  - truth: "Supabase project và hai pooler đăng nhập SQL được với TLS"
    test: "Tạo/chọn project; điền .env local; dùng session pooler 5432 cho Flyway, transaction pooler 6543 cho JDBC/SQL login."
    expected: "Project đúng và cả hai SQL login thành công, không chỉ TCP reachable."
    why_human: "Chưa có project/credentials cấu hình cục bộ; không có phiên Dashboard hay Flyway CLI."
  - truth: "Flyway áp dụng migration và SQL verifier trên database thật"
    test: "Làm theo SUPABASE_SETUP.md mục 6. Dùng database demo riêng nếu thêm db/demo; chạy Flyway migrate/info và verify-schema.sql đúng profile."
    expected: "History success; production13 core hoặc demo14; constraints/RLS/indexes PASS và 5 users/fixtures có ở demo."
    why_human: "PGlite thực thi SQL nhưng không kiểm chứng Flyway, pooler hoặc deployment Supabase."
  - truth: "Anon Data API không làm lộ dữ liệu trên project đã seed"
    test: "Sau khi có dữ liệu demo, chạy test-db-connection.ps1 -Full -EnvFile .env, đối chiếu SQL RLS/policies và kiểm tra role authenticated không đặc quyền trên project."
    expected: "Anon không đọc được dòng; authenticated không được policy cấp truy cập. Không coi HTTP401, bảng rỗng hay TCP thành bằng chứng an toàn."
    why_human: "Cần credentials/roles của đúng project; các role test local chỉ chứng minh cơ chế PostgreSQL."
human_verification:
  - test: "Tạo/chọn project; điền .env local; dùng session pooler 5432 cho Flyway, transaction pooler 6543 cho JDBC/SQL login."
    expected: "Project đúng và cả hai SQL login thành công, không chỉ TCP reachable."
    why_human: "Chưa có project/credentials cấu hình cục bộ; không có phiên Dashboard hay Flyway CLI."
  - test: "Làm theo SUPABASE_SETUP.md mục 6. Dùng database demo riêng nếu thêm db/demo; chạy Flyway migrate/info và verify-schema.sql đúng profile."
    expected: "History success; production13 core hoặc demo14; constraints/RLS/indexes PASS và 5 users/fixtures có ở demo."
    why_human: "PGlite thực thi SQL nhưng không kiểm chứng Flyway, pooler hoặc deployment Supabase."
  - test: "Sau khi có dữ liệu demo, chạy test-db-connection.ps1 -Full -EnvFile .env, đối chiếu SQL RLS/policies và kiểm tra role authenticated không đặc quyền trên project."
    expected: "Anon không đọc được dòng; authenticated không được policy cấp truy cập. Không coi HTTP401, bảng rỗng hay TCP thành bằng chứng an toàn."
    why_human: "Cần credentials/roles của đúng project; các role test local chỉ chứng minh cơ chế PostgreSQL."
---

# Phase 01 — Verification

## Goal Achievement

Mục tiêu phase là hạ tầng PostgreSQL **trên Supabase**, không chỉ file SQL.
Implementation của 4 plan đã có và kiểm local; chưa có bằng chứng deployment.
Không gọi phase.complete, không đánh dấu yêu cầu hạ tầng hoàn tất hoặc chuyển Phase 2.

| # | Observable truth | Status | Evidence |
|---|---|---|---|
| 1 | Core V1 tạo đúng 13 bảng, không lẫn outbox demo | VERIFIED LOCAL | SQL thực thi trên PGlite; inventory và production verifier pass |
| 2 | PHONE_DEVICE, invitation pair, FK/CHECK/attachment unique và receipts hoạt động | VERIFIED LOCAL | Invalid inserts/updates bị từ chối đúng SQLSTATE |
| 3 | Seed 5 users/devices, privacy/block/pending/direct/group/CALL/LOCATION và sync payloads | VERIFIED LOCAL | BCrypt compare, fixtures, replay không nhân bản/lùi mốc pass |
| 4 | Default-deny RLS và verifier phát hiện cấu hình sai | VERIFIED LOCAL | 2 custom nonprivileged roles không thấy dữ liệu; 3 mutation tests pass |
| 5 | Env, setup và script chẩn đoán dùng được | VERIFIED LOCAL | Quick exit0, 3 negative cases exit1, git check-ignore |
| 6 | Project và SQL login session/runtime thật | HUMAN NEEDED | Không có cấu hình thật hoặc project đã đăng nhập |
| 7 | Flyway migration/SQL verifier trên cloud | HUMAN NEEDED | Chưa chạy Flyway CLI/cloud SQL |
| 8 | Supabase Data API/roles thật giữ quyền deny | HUMAN NEEDED | Chưa probe trên project đã seed |

Score chỉ mô tả 5 bằng chứng local; không có nghĩa 5/8 deployment đã thành công.

## Required Artifacts and Wiring

8 source/config/doc files của phase tồn tại, có nội dung thực thi:
.gitignore, .env.example, SUPABASE_SETUP.md, V1 core, V2 demo và ba script backend/scripts.
4 SUMMARY có commit implementation tương ứng.

- .env.example → session/runtime JDBC URLs: Quick xác minh TLS, ports và prepareThreshold=0.
- Core DDL → demo seed → SQL verifier: PGlite chạy đúng thứ tự và kiểm profile13/14.
- Seed → sync_events: đầy đủ MESSAGE_CREATED payload cho 12 recipient events và CALL_UPDATED fixtures.
- Setup → Flyway locations: mặc định core; demo opt-in riêng.
- Setup → Supabase thực tế: chưa wired bằng cấu hình được xác thực.

## Requirements Coverage

| PLAN requirement | REQUIREMENTS.md mapping | Status |
|---|---|---|
| Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu | Traceability: Nền móng CSDL (13 bảng V1), Phase 1 | PARTIAL — local implementation có; cloud acceptance pending |

Requirement trong 4 PLAN là mô tả dùng chung, không phải mã REQ độc lập.
Không đánh dấu hoàn thành các requirement nghiệp vụ Phase 3–6 chỉ vì có bảng.
Không thêm tính năng V2. V2 trong tên seed là version migration.

## Plan Deviations Reviewed

Demo outbox/seed tách db/demo; BCrypt do người vận hành chọn; không URL storage giả;
BIGSERIAL sinh ID; replay giữ watermark; HTTP401 inconclusive; Quick offline.
Các thay đổi này sửa lỗi/rủi ro trong plan và giữ khớp ERD, không mở rộng phạm vi sản phẩm.
Các verify tìm chuỗi ở đường dẫn seed cũ và đếm14 trong production đã được thay bằng
suite SQL hành vi và contract mới trong 01-VALIDATION.md; không tuyên bố verify cũ pass.

Retention 30 ngày mới có index/chính sách tài liệu, scheduler backend thuộc Phase 5.
Chưa có API auth/contact/chat/media thực thi; nghiệp vụ và WebRTC/translation không nằm trong Phase 1.

## Quality Gates

- Code review inline: clean trong phạm vi8 files; không phải reviewer độc lập.
- Security: 8 mitigation local có bằng chứng, 1 cloud verification gate open.
- Nyquist: local8/8 tasks covered; cloud evidence pending; nyquist_compliant false.
- UI: không áp dụng (không thay UI).
- Prior-phase regression: không áp dụng (Phase 1).
- Các sửa đổi tài liệu người dùng có sẵn được giữ; không stage toàn bộ working tree.

## Human Verification Required

3 bước đã lưu trong 01-UAT.md. Cấu hình secret trong .env cục bộ, không gửi vào chat.
Sau khi cấu hình xong có thể tiếp tục tự động các bước CLI khả dụng; không cần
người dùng tự làm những bước mà công cụ đã có đủ quyền và credentials để thực hiện.

Next: $gsd-verify-work 1 sau khi môi trường Supabase sẵn sàng.
