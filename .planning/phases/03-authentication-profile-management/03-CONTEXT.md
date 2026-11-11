# Phase 3: Authentication & Profile Management - Context

**Gathered:** 2026-11-11
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 3 delivers the complete **Authentication & Profile Management** subsystem for UTEChat (assigned to Team Member 2), covering both Backend APIs and Android Client UI:
1. **Registration & OTP Demo:** Phone number registration with E.164 validation, OTP verification flow, password setup (BCrypt cost >= 10). Demo OTP is delivered via API response (when demo mode enabled) and displayed on-screen via Snackbar/Dialog for seamless evaluator testing without SMS gateways.
2. **Dual-Mode Login & Anti-Brute-Force:** Login via Password or fast login via OTP demo. Throttling enforced at server level via `auth_throttles` (`PHONE_DEVICE`, `PHONE`) with HTTP 423 `LOCKED` when attempts exceeded.
3. **Password Recovery:** Forgot password flow using OTP demo verification (`/auth/password-reset/*`), with countdown timer, resend button, and automatic invalidation of all other device sessions (`token_valid_after = now()`).
4. **Token & Session Management:** 30-day JWT issuing containing `userId` and `deviceId`. Android client stores token in `EncryptedSharedPreferences` via `SessionManager`. OkHttp `AuthInterceptor` attaches `Authorization: Bearer <token>` only to trusted backend requests. Network errors (`IOException`) maintain session and fallback to Room cache; only HTTP 401 triggers logout.
5. **Clean Logout & Account Switch:** Disconnects WebSocket, cancels pending WorkManager background jobs, clears Room SQLite cache (`appDatabase.clearAllTables()`), and removes JWT from Keystore so no data is leaked between different accounts.
6. **Device Management:** `GET /account/devices` and `DELETE /account/devices/{id}` allowing users to view all active devices with a "This device" badge and revoke lost devices immediately.
7. **Profile, Privacy & Soft Delete:** View and edit personal info (`displayName`, `avatarMediaUrl`), switch `allowMessagesFromNonContacts` via `PATCH /me`, change password (`POST /me/password`), and soft delete account (`DELETE /me`) with password confirmation dialog (releasing phone number, wiping 2-way contacts/blocks, and revoking all sessions).
8. **i18n & DayNight UI:** In-app language switching (English / Tiếng Việt) and theme switching (Light / Dark / System Default) via `AppCompatDelegate`, persisted across app restarts without state loss on orientation change.

</domain>

<decisions>
## Implementation Decisions

### Registration, Login & OTP Demo
- **D-01:** Registration and password recovery flows use demo OTP. To enable seamless demo testing on both Android Emulator and hosted cloud deployments (where `/api/demo/sms` is disabled in production), the backend returns the demo OTP in the response body when demo mode is active, and the Android client displays a clear Snackbar/Dialog showing the OTP code for 1-tap entry. — **Reversibility:** costly — affects Auth API response contract and client UI.
- **D-02:** Navigation for authentication in `nav_auth.xml` is decomposed into distinct, single-responsibility fragments: `LoginFragment` (supporting Password / OTP mode toggle), `RegisterFragment` (phone input & E.164 formatting), `OtpVerifyFragment` (countdown timer, resend OTP, code input), and `ForgotPasswordFragment`.
- **D-03:** Server enforces brute-force protection using the `auth_throttles` table with scopes `PHONE_DEVICE` and `PHONE`. When failed attempts exceed the threshold, server responds with HTTP 423 `LOCKED` and retry-after seconds.

### Token, Session & Security Architecture
- **D-04:** JWT tokens (30-day lifespan containing `userId` and `deviceId`) are securely stored on Android using `EncryptedSharedPreferences` (MasterKeys AES-256) managed by a centralized `SessionManager`. — **Reversibility:** costly — affects local credential security.
- **D-05:** OkHttp `AuthInterceptor` automatically injects `Authorization: Bearer <token>` into outgoing requests destined for `BACKEND_BASE_URL`. Per `docs/DEPLOYMENT_SECURITY.md`, tokens are never placed in URL query parameters, and sensitive credentials (passwords, OTPs, JWTs) are strictly excluded from Logcat logs.
- **D-06:** Strict network failure vs 401 distinction: Transient network issues (`IOException`, `SocketTimeoutException`, offline) NEVER log out the user — the app preserves session and falls back to Room local data. Only an explicit HTTP 401 `UNAUTHORIZED` from the server triggers session invalidation.
- **D-07:** Comprehensive logout cleanup: Invoking `SessionManager.logout()` or switching accounts immediately cancels all WorkManager background tasks, closes active WebSocket signaling, clears all Room SQLite cached tables (`appDatabase.clearAllTables()`), and removes stored credentials from Keystore, preventing cross-account data leakage.

### Device Management
- **D-08:** `DeviceManagementFragment` in `nav_profile.xml` consumes `GET /account/devices` and `DELETE /account/devices/{id}`. Active devices are rendered in a RecyclerView with a distinct "Current Device" badge and last seen timestamps. Revoking a remote device prompts for confirmation and immediately invalidates that device's session on the server.

### Profile, Privacy & Soft Delete
- **D-09:** `ProfileFragment` provides profile editing (`displayName`, avatar image selection) via `PATCH /me`.
- **D-10:** Privacy setting `allowMessagesFromNonContacts` is controlled via a MaterialSwitch in `ProfileFragment`, dispatching `PATCH /me {"allowMessagesFromNonContacts": true/false}` to govern whether strangers can initiate 1-1 conversations.
- **D-11:** Soft delete account (`DELETE /me`) requires re-entering the account password in a confirmation warning dialog. Upon server confirmation (204 No Content), the phone number is set to NULL, two-way contact relationships and blocks are purged, all sessions are revoked, and the client executes a clean logout.

### UI Standards: i18n & DayNight
- **D-12:** Language selection (Tiếng Việt / English) and Theme selection (Sáng / Tối / Theo hệ thống) are accessible from `ProfileFragment` via RadioDialogs. Selections are stored in preferences and applied instantly via `AppCompatDelegate.setApplicationLocales()` and `AppCompatDelegate.setDefaultNightMode()`, maintaining full state across orientation changes.

### Educational & Code Commenting Standard
- **D-13:** 100% of generated code (Android Java, XML layouts/navigation/menu/strings, Backend Spring Boot, SQL, scripts) MUST have concise, clear in-code comments in Vietnamese explaining the logic, intent, and architectural purpose of functions and statements, enabling students to defend and explain the codebase confidently to course instructors. — **Reversibility:** reversible — documentation requirement.

### the agent's Discretion
- Layout styling and animation transitions between auth fragments following Material 3.
- Avatar image caching using standard Android components or Glide/coil if already integrated.
- Regex validation patterns for E.164 phone numbers (`^\+[1-9]\d{1,14}$`) and strong passwords (minimum 8 characters with letters, numbers, and symbols).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing:**

### Architecture & Security Specs
- `docs/DEPLOYMENT_SECURITY.md` — Section 1 (HTTPS/WSS), Section 3 (Token, Bearer, no JWT in URL, credential logging prohibitions).
- `docs/API_CONTRACT.md` — Section 2.1 (Auth endpoints: register, login, password reset, logout) and Section 2.2 (Account: `/me`, password, delete, `/account/devices`).
- `docs/FLOWS.md` — Section 1.1 (Registration & OTP), Section 1.2 (Login & Throttling), Section 1.3 (Forgot Password), Section 1.4 (Profile & Soft Delete).
- `docs/ARCHITECTURE_DECISIONS.md` — ADR-001 (Phone E.164), ADR-002 (Demo OTP), ADR-003 (Dual-Mode Login & Throttles), ADR-018 (Token Auth & Revocation), ADR-021 (i18n & DayNight).
- `docs/COURSE_ALIGNMENT.md` — Section 3 (Navigation Component), Section 4 (MVVM, LiveData, Room), Section 5 (Retrofit, OkHttp, WorkManager).

### Codebase Assets & Seams
- `app/src/main/res/navigation/nav_auth.xml` — Auth child navigation graph.
- `app/src/main/res/navigation/nav_profile.xml` — Profile child navigation graph.
- `app/src/main/java/com/utechat/data/repository/AuthRepository.java` — Seam interface to be fully implemented with `RealAuthRepository`.
- `app/src/main/java/com/utechat/di/Injection.java` — Service locator providing `AuthRepository` and `SessionManager`.
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — Schema for `users`, `user_devices`, `otp_challenges`, `auth_sessions`, `auth_throttles`.
- `GEMINI.md` & `AGENTS.md` — In-code educational Vietnamese comment standard.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `app/src/main/java/com/utechat/data/repository/fake/FakeAuthRepository.java` already implements mock auth with 5 demo accounts.
- `app/src/main/java/com/utechat/data/remote/api/AuthApi.java` contains initial Retrofit interface definitions for auth endpoints.
- `app/src/main/java/com/utechat/data/local/AppDatabase.java` provides Room DAO access for local user state.

### Established Patterns
- Pure Single Activity (`MainActivity`) with BottomNavigationView visibility toggled dynamically based on destination.
- Seam pattern separating Interface from Real and Fake implementations via `Injection.java`.

### Integration Points
- `LoginFragment` transitions to `MainActivity`'s main navigation container on successful authentication.
- `SessionManager` coordinates token lifecycle between OkHttp interceptors and Android UI.

</code_context>

<specifics>
## Specific Ideas

- The OTP dialog/snackbar provides a "Copy" or "Auto-fill" button so evaluators don't need to manually transcribe 6-digit codes during live demonstrations.
- The Device Management screen reinforces high security standards in the university rubric by proving that stolen devices can be terminated remotely.

</specifics>

<deferred>
## Deferred Ideas

- None — all discussed items fit strictly into Phase 3 scope as bounded by ROADMAP.md.

</deferred>

---

*Phase: 3-Authentication & Profile Management*
*Context gathered: 2026-11-11*
