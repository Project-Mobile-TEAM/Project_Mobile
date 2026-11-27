---
phase: 07-message-actions-reactions-stickers
plan: "06"
status: complete
---

# 07-06 — Kiểm chứng Phase 7

Commit `87898c9` bổ sung test Android cho menu tin nhắn, cảm xúc, gỡ tin, bộ nhãn dán và logic ViewModel. Commit `0a7b4bc` cập nhật kiểm tra danh sách bảng production qua Flyway V9.

- Backend Maven đạt 335/335 trên EmbeddedPostgres. Lần đầu có một assertion đếm bảng theo schema trước V9. Lần chạy lại kiểm tra danh sách tên bảng và đạt toàn bộ.
- Android JVM đạt 288/288. Emulator đạt 13/13 trong ba suite Room và UI đã chọn. Lint đạt 0 lỗi, 106 cảnh báo. APK debug và APK debug dùng backend đều build thành công.
- Một emulator và tài khoản đối diện qua REST xác nhận gỡ tin, xóa phía người dùng, cảm xúc và gửi nhãn dán sau khi có mạng lại. Xem bằng chứng và checklist hai thiết bị trong [07-VALIDATION.md](07-VALIDATION.md).
