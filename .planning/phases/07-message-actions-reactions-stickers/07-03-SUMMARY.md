---
phase: 07-message-actions-reactions-stickers
plan: "03"
status: complete
---

# 07-03 — Room v4 và Retrofit

Commits trên `feature/phase-7-core` gồm `4cb66bf`, `182bc50` và `469d8d1`.

Lưu phản ứng, danh mục nhãn dán và dấu xóa phía người dùng trong Room. Dấu xóa theo server ID ngăn lịch sử hoặc ACK đến muộn khôi phục tin. Retrofit sử dụng Bearer token chụp theo phiên và gửi đúng `emoji: null` khi bỏ phản ứng.

## Kiểm chứng

`AppDatabaseMigrationTest` và `Phase7RoomDaoTest` đạt 10/10 trên emulator. `Phase7ChatApiTest` kiểm tra URL tài nguyên cùng origin, payload và yêu cầu cảm xúc chỉ gửi một lần.
