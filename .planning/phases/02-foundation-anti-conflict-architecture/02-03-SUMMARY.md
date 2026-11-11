---
phase: 02-foundation-anti-conflict-architecture
plan: "03"
subsystem: ui
status: complete
completed: 2026-11-11
requirements-completed: []
key-files:
  created:
    - app/src/main/java/com/utechat/di/Injection.java
    - app/src/main/java/com/utechat/ui/splash/HealthViewModel.java
    - docs/FOUNDATION_SETUP.md
coverage:
  - id: navigation
    description: Ba tab, recreate và probe thật
    requirement: REQ-ui-standards
    verification:
      - kind: automated_ui
        ref: app/src/androidTest/java/com/utechat/FoundationNavigationTest.java
        status: pass
      - kind: unit
        ref: app/src/test/java/com/utechat/ui/splash/HealthViewModelTest.java
        status: pass
    human_judgment: false
---

# 02-03 — Navigation và Walking Skeleton

Commit `8ab18b5` hoàn tất hai task UI/network và probe lifecycle. Một Activity/ViewBinding, bốn graph auth/chat/contacts/profile, ba tab; chuỗi tách5 nhóm EN/VI; Material3 DayNight. Manual DI cấp singleton Fake/Real, probe luôn gọi thật.

HealthViewModel quản lý retry/generation/cancel. Fragment quan sát theo view lifecycle và gỡ Handler/Binding đúng lúc. Lỗi có chữ, nút thử lại/ngoại tuyến và live region; thành công chờ1.5 giây rồi sang Auth.

## Kiểm tra

JVM10/10, emulator5/5, Lint0 errors/31 warnings, Debug/Release build thành công. UI thật: Supabase CONNECTED, Auth, ba tab, recreate, dark mode và landscape.

## Điều chỉnh và giới hạn

Cleartext chỉ Debug; log BASIC Debug/NONE Release thay BODY. Health dùng /ready theo API_CONTRACT. Bốn graph và5 nhóm resource theo CONTEXT; chưa dựng graph media rỗng.
Preview chỉ Debug, không cấp JWT/login giả. Màn hình nghiệp vụ vẫn là khung cho phase sau.
Release tắt Fake/chặn HTTP, cần backendBaseUrl HTTPS khi triển khai. Windows khóa output incremental: dọn riêng app/build rồi build sạch đã pass.
REQ-ui-standards mới hoàn thành phần Phase2; tuỳ chỉnh ngôn ngữ/theme thuộc Phase3.
