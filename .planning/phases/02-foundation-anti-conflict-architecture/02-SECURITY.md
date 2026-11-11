---
phase: 02-foundation-anti-conflict-architecture
status: secured
verdict: SECURED
threats_open: 0
reviewed: 2026-11-11
scope: foundation-only
---
# Bảo mật nền tảng Phase2

Plan không có threat ID; đây là rà soát luồng dữ liệu thực tế của foundation.

| Nguy cơ | Biện pháp và bằng chứng |
|---|---|
|Lộ credential Supabase|Env không vào app/Git, BackendApplication không in secret|
|Password/OTP xuống client|Room10 bảng đúng ERD; RoomFoundationTest kiểm schema không credential|
|Lộ body xác thực qua log|BASIC Debug/NONE Release, không log BODY/header|
|Lộ DB exception|Response cố định; MockMvc kiểm exception nhạy cảm không xuất ra|
|HTTP/Fake trong bản phát hành|Overlay chỉ Debug; Release manifest cleartext=false, DEBUG=false, USE_FAKE_DATA=false; runtime guard|
|Preview thành login giả|Nút chỉ Debug; RealAuth từ chối khi chưa có nghiệp vụ|
|CORS rộng ngoài dev|CorsConfig chỉ profile dev, không cookie; preflight thật HTTP200 đúng origin|
|Backup cache|allowBackup=false|

FTS bind parameter, quote từng token MATCH. Cursor update transaction theo userId. Không còn nguy cơ mở chặn phạm vi Phase2.

Chưa có JWT/RBAC/upload nên chưa kiểm bảo mật nghiệp vụ; phải làm ở các phase tương ứng. Release cần backendBaseUrl HTTPS và profile vận hành, chưa deploy. SELECT1 không chứng minh RLS/schema cloud; phần đó kế thừa nghiệm thu Phase1 người dùng đã cập nhật. Không tuyên bố toàn V1 an toàn.
