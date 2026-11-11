---
phase: 02-foundation-anti-conflict-architecture
status: clean
reviewed: 2026-11-11
review_mode: inline
critical: 0
high: 0
medium: 0
---
# Rà soát Phase2

Đã đọc caller/DAO/repository/navigation/lifecycle/backend/config/tests thuộc ba commit 1fe95a9, 8f875f3, 8ab18b5. Thực hiện inline theo adapter Codex, không dùng subagent.

Các lỗi đã sửa và kiểm lại:
- FakeChat không phát cập nhật conversations: giữ stream theo user, test observer nhận sự kiện.
- ACK tin nhắn: tìm localId theo clientId trong transaction; test giữ ID và không thêm dòng.
- Cursor: transaction không lùi/tách userId; test hai tài khoản.
- Retry/lifecycle: cancel/generation và gỡ callback; JVM test success/error/stale/clear.
- Espresso cũ không tương thích InputManager API37: nâng AndroidX Test, emulator5/5 pass.
- Tương phản thanh trạng thái sáng: sửa theme; bỏ thuộc tính cần API27 để giữ min26.

Bằng chứng: backend4/4, Android JVM10/10, emulator5/5, Lint0 errors/31 warnings, build Debug/Release thành công. Regression SQL Phase1:16/16 pass trong phiên này.

31 cảnh báo gồm thư viện có bản mới, cleartext Debug, overdraw và tài nguyên mẫu chưa dùng. Không nâng toàn bộ dependency ngoài phạm vi.

Real auth/send, JWT, WebSocket và WorkManager thuộc phase sau; chưa thực hiện sẽ từ chối rõ ràng. Phase3 phải xóa cache/hàng đợi khi đổi tài khoản. Chưa thử thiết bị thật/API26 hay tải lớn. Windows có thể khóa output incremental; build sạch app/build đã pass và có hướng dẫn.
