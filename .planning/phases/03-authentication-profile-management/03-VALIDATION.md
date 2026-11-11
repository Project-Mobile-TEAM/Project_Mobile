---
phase: "3"
slug: "authentication-profile-management"
status: draft
nyquist_compliant: true
wave_0_complete: false
created: "2026-11-11"
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 & Mockito (Backend) / JUnit 4 & Robolectric-free unit tests (Android JVM) |
| **Config file** | `backend/pom.xml`, `app/build.gradle.kts` |
| **Quick run command** | `cd backend; mvn test -Dtest=AuthThrottleServiceTest,OtpServiceTest` |
| **Full suite command** | `cd backend; mvn test; cd ..; .\gradlew testDebugUnitTest` |
| **Estimated runtime** | ~25 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick unit test for modified component
- **After every plan wave:** Run `cd backend; mvn test` and `.\gradlew testDebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green (Backend MockMvc + Android JVM tests)
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 03-01-01 | 01 | 1 | REQ-auth-login | T-03-03 | Stateless SecurityFilterChain, JJWT 0.12.6, BCrypt cost 10 | compile/unit | `cd backend; mvn test-compile` | ✅ | ⬜ pending |
| 03-01-02 | 01 | 1 | REQ-auth-registration, REQ-auth-login | T-03-01, T-03-02 | Atomic OTP consumption (no replay), REQUIRES_NEW throttle persistence (423 Locked) | unit/integration | `cd backend; mvn test -Dtest=OtpServiceTest,AuthThrottleServiceTest,AuthControllerTest` | ❌ W0 | ⬜ pending |
| 03-01-03 | 01 | 1 | REQ-auth-account | T-03-04 | Profile updates (no updated_at), 400 on bad password, full soft delete transaction | unit/integration | `cd backend; mvn test -Dtest=AccountControllerTest` | ❌ W0 | ⬜ pending |
| 03-02-01 | 02 | 2 | REQ-auth-login | T-03-05, T-03-06 | Fail-closed SessionManager (Keystore error halts, no plaintext), AuthInterceptor 401 correlation | unit | `.\gradlew testDebugUnitTest --tests "com.utechat.data.local.SessionManagerTest" --tests "com.utechat.data.remote.AuthInterceptorTest"` | ❌ W0 | ⬜ pending |
| 03-02-02 | 02 | 2 | REQ-auth-registration, REQ-auth-login | T-03-07 | PhoneUtils E.164, Retrofit AuthApi, Real/Fake AuthRepository backward-compatibility | unit | `.\gradlew testDebugUnitTest --tests "com.utechat.data.repository.FakeRepositoriesTest"` | ✅ | ⬜ pending |
| 03-02-03 | 02 | 2 | REQ-auth-registration, REQ-auth-login | T-03-01 | AuthViewModel E.164 validation, dual-mode toggle, rotation state retention, 423 countdown | unit | `.\gradlew testDebugUnitTest --tests "com.utechat.ui.auth.AuthViewModelTest"` | ❌ W0 | ⬜ pending |
| 03-03-01 | 03 | 3 | REQ-auth-account | T-03-09 | Clean logout (cancel OkHttp/WorkManager, generation counter, clear Room & Keystore), Retrofit @HTTP DELETE | unit | `.\gradlew testDebugUnitTest --tests "com.utechat.data.repository.AccountRepositoryTest"` | ❌ W0 | ⬜ pending |
| 03-03-02 | 03 | 3 | REQ-auth-account, REQ-ui-standards | T-03-08 | ProfileViewModel, DayNight & Language switching, full test suite pass | unit | `.\gradlew testDebugUnitTest --tests "com.utechat.ui.profile.ProfileViewModelTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Integration & Real-Flow Verification (P2 - 10)

Để kiểm chứng toàn diện luồng hoạt động thực tế giữa Android Client ↔ Spring Boot Backend ↔ Supabase PostgreSQL (thay vì chỉ chạy Fake in-memory):

| Kịch bản kiểm chứng | Phương thức thực hiện | Tiêu chí nghiệm thu (Pass Criteria) |
|---------------------|-----------------------|-------------------------------------|
| **1. Bật Real Repositories trong Debug** | Gọi `Injection.setOverrideUseFake(false)` hoặc chạy build flag `-PuseFakeData=false` | App kết nối trực tiếp `http://10.0.2.2:8080/` (Emulator) hoặc IP backend LAN thật. |
| **2. Đăng ký & Đăng nhập Real E2E** | Nhập SĐT `+84988112233`, nhận `demoOtp`, hoàn tất mật khẩu | Dữ liệu xuất hiện trên PostgreSQL: bảng `users`, `user_devices`, `auth_sessions`. Token JWT 30 ngày được cấp phát. |
| **3. Khôi phục phiên khi mở lại App (Cold Start)** | Thoát app, kill process, mở lại app | `MainActivity` đọc token từ `EncryptedSessionStorage`, bỏ qua màn hình Login, vào thẳng giao diện chính. |
| **4. Xoay màn hình khi nhập OTP** | Mở `OtpVerifyFragment`, đang đếm ngược 45s, xoay ngang thiết bị | Số điện thoại, mã OTP đang nhập dở và đồng hồ đếm ngược được bảo toàn nguyên vẹn từ ViewModel. |
| **5. Đăng xuất ngoại tuyến & Đổi tài khoản** | Tắt Wifi/4G, bấm "Đăng xuất" trong Profile | App hủy toàn bộ OkHttp/WorkManager, xóa sạch Room `clearAllTables()`, xóa JWT Keystore. Bật mạng lại đăng nhập tài khoản khác: không còn bất kỳ dữ liệu nào của tài khoản trước. |
| **6. Vòng đời Token & Thu hồi tức thì** | Tài khoản A đổi mật khẩu trên thiết bị 1; thiết bị 2 đang dùng token cũ gửi request | Thiết bị 1 tiếp tục dùng bình thường. Thiết bị 2 nhận HTTP 401 ngay lập tức do `token_valid_after` đã bị đẩy lên mốc mới. |
| **7. Chống Brute-force với PostgreSQL thật** | Gửi 5 lần sai mật khẩu liên tiếp vào `POST /api/v1/auth/login/password` | `auth_throttles.failure_count` trong PostgreSQL tăng từ 1 đến 5 (nhờ `REQUIRES_NEW`), lần thứ 5 trả HTTP 423 `LOCKED` kèm `retryAfterSeconds: 900`. |

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/com/utechat/backend/service/OtpServiceTest.java` — stubs for REQ-auth-registration
- [ ] `backend/src/test/java/com/utechat/backend/service/AuthThrottleServiceTest.java` — stubs for REQ-auth-login
- [ ] `backend/src/test/java/com/utechat/backend/controller/AuthControllerTest.java` — stubs for REQ-auth-login
- [ ] `backend/src/test/java/com/utechat/backend/controller/AccountControllerTest.java` — stubs for REQ-auth-account
- [ ] `app/src/test/java/com/utechat/data/local/SessionManagerTest.java` — stubs for REQ-auth-login
- [ ] `app/src/test/java/com/utechat/data/remote/AuthInterceptorTest.java` — stubs for REQ-auth-login
- [ ] `app/src/test/java/com/utechat/ui/auth/AuthViewModelTest.java` — stubs for REQ-auth-registration / login
- [ ] `app/src/test/java/com/utechat/ui/profile/ProfileViewModelTest.java` — stubs for REQ-auth-account / REQ-ui-standards

*Existing infrastructure (JUnit 5, Mockito, Spring Test, JUnit 4, Robolectric/Mock on JVM) covers all phase requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| One-tap demo OTP copy/paste banner | REQ-auth-registration | UI layout & Snackbar appearance on mobile screen | Register a new test number, verify demo OTP banner appears and 1-tap paste autofills PIN field |
| Visual DayNight theme switch | REQ-ui-standards | Rendering check on Android UI components | Toggle Light/Dark mode in Profile, verify all surfaces adapt without contrast degradation |
| Visual language switch (VI / EN) | REQ-ui-standards | Native Android locale recreation | Toggle language in Profile, verify strings change immediately across tabs |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 30s
- [x] `nyquist_compliant: true` set in frontmatter (to be validated after plans authored)

**Approval:** pending 2026-11-11
