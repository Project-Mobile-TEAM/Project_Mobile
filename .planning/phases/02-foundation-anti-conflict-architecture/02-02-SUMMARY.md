---
phase: 02-foundation-anti-conflict-architecture
plan: "02"
subsystem: database
status: complete
completed: 2026-11-11
tags: [room, fts4, repository, offline]
provides:
  - Schema Room10 bảng v1, DAO và repository seams
affects: [authentication, contacts, messaging, media]
key-files:
  created:
    - app/src/main/java/com/utechat/data/local/AppDatabase.java
    - app/schemas/com.utechat.data.local.AppDatabase/1.json
    - app/src/main/java/com/utechat/data/repository/fake/FakeChatRepository.java
    - app/src/androidTest/java/com/utechat/data/local/RoomFoundationTest.java
key-decisions:
  - Theo lựa chọn trực tiếp của người dùng, dùng10 bảng Room của ERD mục4 thay vì13 bảng server
requirements-completed: []
coverage:
  - id: room-cache
    description: Room đúng schema offline, FTS tiếng Việt và cursor đơn điệu
    requirement: REQ-ui-standards
    verification:
      - kind: integration
        ref: app/src/androidTest/java/com/utechat/data/local/RoomFoundationTest.java
        status: pass
      - kind: unit
        ref: app/src/test/java/com/utechat/data/local/RoomConvertersTest.java
        status: pass
    human_judgment: false
  - id: fake-seams
    description: Fixture độc lập, gửi tin phát lại observer hội thoại
    verification:
      - kind: unit
        ref: app/src/test/java/com/utechat/data/repository/FakeRepositoriesTest.java
        status: pass
    human_judgment: false
---

# 02-02 — Room offline và repository seams

Commit `8f875f3` hoàn tất hai task Room/DAO và repository fixture, đồng thời pin dependencies cần để biên dịch.

## Phạm vi thực hiện

10 bảng: user_cache, contact_request, contact, conversation, member, message, attachment_transfer, message_fts, message_translation, sync_state.
Không sao chép password_hash, OTP hoặc bảng phiên xác thực server vào Android.

Schema v1 được Room export và commit. FTS4 có normalized_text bỏ dấu và đổi đ/Đ; insert/ACK giữ localId theo clientId. SyncStateDao cập nhật cursor trong transaction, không lùi và tách userId. Destructive migration chỉ Debug.

Ba repository interfaces có Fake và khung Real. Fake dùng5 hồ sơ cùng seed, không credential; gửi tin phát LiveData của cả messages và conversations. Real đọc Room; login/send chưa thực hiện sẽ từ chối rõ ràng.

## Bằng chứng

- RoomConvertersTest:3/3; FakeRepositoriesTest:3/3.
- RoomFoundationTest:3/3 trên emulator API37, kiểm FTS/ACK/cursor/schema không credential.
- Schema JSON chứa đúng10 entities (không tính bảng nội bộ SQLite/FTS).

## Điều chỉnh và bàn giao

D-12/plan cũ13 entities bị thay thế bởi quyết định người dùng đã lưu trong 02-CONTEXT.md. Không dựng 13 bảng xác thực trên điện thoại.
Manual DI được nối trong commit02-03 vì phụ thuộc ApiClient và HealthViewModel của wave3.
AndroidX Test nâng JUnit1.3.0/Espresso3.7.0 để chạy trên emulator API37; compile/target vẫn35.
REQ-ui-standards chỉ hoàn thành phần Phase2. Phase3 phải triển khai xoá cache/hàng đợi khi logout/đổi tài khoản; DAO đồng bộ chỉ gọi từ executor, không bật allowMainThreadQueries.
