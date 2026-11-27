---
phase: "07"
plan: "05"
subsystem: android-chat
tags: [android, java, material3, viewbinding, livedata, stickers, reactions]
requires:
  - phase: "07-04"
    provides: Kho dữ liệu chat và nhãn dán, đồng bộ sự kiện, hàng đợi gửi.
provides:
  - Menu nhấn giữ, cụm và chi tiết cảm xúc, bong bóng và cửa hàng nhãn dán.
affects: ["07-06"]
tech-stack:
  added: []
  patterns: [Material 3 Views, ViewBinding, LiveData, RecyclerView payloads]
key-files:
  created:
    - app/src/main/java/com/utechat/ui/chat/MessageActionBottomSheet.java
    - app/src/main/java/com/utechat/ui/chat/ReactionDetailsBottomSheet.java
    - app/src/main/java/com/utechat/ui/chat/StickerTrayView.java
    - app/src/main/java/com/utechat/ui/chat/StickerStoreDialog.java
  modified:
    - app/src/main/java/com/utechat/ui/chat/ChatRoomFragment.java
    - app/src/main/java/com/utechat/ui/chat/ChatRoomViewModel.java
    - app/src/main/java/com/utechat/ui/chat/MessageAdapter.java
    - app/src/main/java/com/utechat/ui/chat/ConversationListAdapter.java
    - app/src/main/java/com/utechat/ui/chat/SystemMessageFormatter.java
    - app/src/main/java/com/utechat/ui/chat/ChatErrors.java
key-decisions:
  - Cụm cảm xúc hiển thị tối đa ba mục mỗi hàng trên màn hình hẹp.
  - Ảnh nhãn dán ưu tiên tệp hợp lệ trong thư mục cache. Lịch sử có thể tải lại ảnh qua URL cùng origin với backend.
requirements-completed: [REQ-message-actions-recall-delete, REQ-message-reactions, REQ-message-stickers]
metrics:
  tasks: 3
  files: 33
  completed: "2026-11-27"
plan_head_before: af8adf88301989cac9de19f3a6882bdba9e415f9
commits: 3
status: complete
---

# Phase 7 Plan 05 · Thao tác tin nhắn, cảm xúc và nhãn dán trên Android

Menu nhấn giữ mở dịch EN/VI, gỡ tin và xóa riêng tư theo trạng thái tin. Phòng chat hiển thị cụm cảm xúc, danh sách người phản ứng và khay nhãn dán đã cài.

## Các commit

| Task | Nội dung | Commit |
|---|---|---|
| 1 | Layout, tài nguyên EN/VI, nhãn xem trước và thông báo lỗi | `5e426e9` |
| 2 | Menu nhấn giữ, chi tiết cảm xúc, khay và cửa hàng nhãn dán | `35468c7` |
| 3 | Kết nối Adapter, ViewModel và Fragment với luồng dữ liệu chat | `8a3b43a` |

## Kiểm chứng

- `compileDebugJavaWithJavac`, `testDebugUnitTest` và `compileDebugAndroidTestJavaWithJavac` đã chạy qua.
- Đã tạo APK debug và APK chạy instrumentation từ Gradle với thư mục đầu ra riêng trên Windows.
- `Phase7MessageActionsUiTest` đạt 3/3 trên emulator. `lintDebug` báo 0 lỗi, 106 cảnh báo.

## Điều chỉnh khi thực hiện

- Thư mục đầu ra Gradle đặt trong thư mục tạm bằng init script ngoài repo để tránh lỗi truy cập `app/build` trên Windows.
- Kiểm tra tệp nhãn dán trong thư mục cache của ứng dụng trước khi cho gửi trên cấu hình backend thật. Dữ liệu mẫu dùng fixture riêng.
- Cụm cảm xúc chia tối đa ba mục mỗi hàng và dùng vùng chạm tối thiểu 48dp.

## Self-Check: PASSED

Đã đối chiếu 33 tệp, ba commit và phạm vi `af8adf8..8a3b43a`. `git diff --check` không báo lỗi.
