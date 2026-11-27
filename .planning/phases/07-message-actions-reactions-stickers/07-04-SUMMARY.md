---
phase: 07-message-actions-reactions-stickers
plan: "04"
subsystem: android-data
tags: [java, room, retrofit, workmanager, okhttp, offline-first]
requires:
  - phase: 07-02
    provides: Hợp đồng API và danh mục nhãn dán trên server
  - phase: 07-03
    provides: Room v4, DTO và API Retrofit cho Phase 7
provides:
  - Đồng bộ thu hồi, xóa phía bạn và cảm xúc vào Room
  - Hàng đợi WorkManager gửi nhãn dán bằng clientId cố định
  - Danh mục và ảnh nhãn dán cục bộ gắn với phiên đăng nhập
affects: ["07-05", "07-06"]
tech-stack:
  added: []
  patterns: [captured session authorization, transactional sync application, atomic cache publication]
key-files:
  created:
    - app/src/main/java/com/utechat/data/repository/StickerRepository.java
    - app/src/main/java/com/utechat/data/repository/real/RealStickerRepository.java
    - app/src/main/java/com/utechat/data/repository/fake/FakeStickerRepository.java
    - app/src/main/java/com/utechat/data/repository/real/CapturedChatSession.java
    - app/src/main/java/com/utechat/data/sticker/StickerCacheManager.java
    - app/src/test/java/com/utechat/data/sticker/StickerCacheAndRepoTest.java
  modified:
    - app/src/main/java/com/utechat/data/sync/SyncEventApplier.java
    - app/src/main/java/com/utechat/data/sync/ChatWebSocketManager.java
    - app/src/main/java/com/utechat/data/notifications/NotificationHelper.java
    - app/src/main/java/com/utechat/data/worker/SendMessageWorker.java
    - app/src/main/java/com/utechat/data/repository/ChatRepository.java
    - app/src/main/java/com/utechat/data/repository/real/RealChatRepository.java
    - app/src/main/java/com/utechat/data/repository/fake/FakeChatRepository.java
    - app/src/main/java/com/utechat/di/Injection.java
    - app/src/test/java/com/utechat/data/sync/SyncEngineAndApplierTest.java
    - app/src/test/java/com/utechat/data/worker/SendMessageWorkerTest.java
key-decisions:
  - "Áp dụng kết quả HTTP trong phiên đã gửi yêu cầu, kiểm tra mốc sync trước khi thay danh sách cảm xúc."
  - "Giữ ảnh khi gỡ bộ nhãn dán để xem lịch sử. Dọn ảnh cùng Room khi đổi tài khoản hoặc đăng xuất."
requirements-completed: [REQ-message-actions-recall-delete, REQ-message-reactions, REQ-message-stickers]
plan_head_before: 182bc50b1148c7ba6e0aeca0cf42ba121ace608c
commits: 3
completed: 2026-11-27
status: complete
---

# Phase 7 Plan 04 — Đồng bộ thao tác tin nhắn và nhãn dán trên Android

Room áp dụng sự kiện thu hồi, xóa phía bạn và thay đổi cảm xúc. WorkManager tiếp tục gửi nhãn dán với cùng `clientId` khi thiết bị có mạng.

## Kết quả triển khai

- WebSocket yêu cầu đồng bộ khi nhận sự kiện Phase 7. Trình áp dụng lô cập nhật tin nhắn, cảm xúc và bản dịch trong cùng giao dịch. Thông báo chỉ hiển thị mô tả chung cho nhãn dán.
- `SendMessageWorker` gửi `stickerId` từ hàng đợi. Bản ghi xóa cá nhân trong Room giúp bỏ qua ACK và trang lịch sử đến trễ.
- Repository thật và giả cung cấp danh mục, cài và gỡ bộ nhãn dán. Cache kiểm tra đường dẫn backend, loại ảnh và giới hạn dung lượng. Tệp được đưa vào tên đích trước khi lưu đường dẫn vào Room.
- Thao tác tin nhắn và tải ảnh dùng thông tin xác thực đã chụp khi gửi yêu cầu. Phản hồi đến sau khi đổi phiên không ghi vào cơ sở dữ liệu của tài khoản mới.

## Commit từng tác vụ

| Tác vụ | Nội dung | Commit |
| --- | --- | --- |
| 1 | Sự kiện đồng bộ, thông báo và hàng đợi nhãn dán | `e7f34b8` |
| 2 | Repository, cache theo phiên và Injection | `f4a0b1e` |
| 3 | Kiểm thử đồng bộ, hàng đợi và cache nhãn dán | `af8adf8` |

## Điều chỉnh khi triển khai

- **[Rule 2]** Các phản hồi REST kiểm tra phiên đăng nhập và mốc đồng bộ trước khi áp dụng kết quả. Cơ chế này ngăn một trang lịch sử cũ ghi đè danh sách cảm xúc đã được cập nhật.
- **[Rule 2]** ACK gửi tin đến muộn kiểm tra dấu xóa cá nhân trước khi ghi lại tin nhắn. Bộ kiểm thử hàng đợi khởi tạo đúng các DAO Room mới và xác nhận request lịch sử mang token đã chụp.

## Kiểm chứng

- Gradle đã chạy thành công `testDebugUnitTest`, biên dịch `androidTest` và `assembleDebug`.
- Hai bộ kiểm thử Room trên emulator đã qua.
- Kết quả tích hợp `testDebugUnitTest` đạt 288/288. `lintDebug` báo 0 lỗi, 106 cảnh báo.

## Self-Check: PASSED

Đã đối chiếu ba commit và 16 file với phạm vi `182bc50..af8adf8`. Git ghi nhận ba commit và không có file bị xóa trong phạm vi này.
