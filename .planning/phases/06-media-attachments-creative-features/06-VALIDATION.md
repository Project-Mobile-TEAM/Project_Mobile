---
phase: "06"
slug: "media-attachments-creative-features"
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-11-21"
---

# Phase 6 — Validation Strategy

> Verification contract for Phase 6 execution: proves every media, offline-queue, notification/periodic-sync, WebRTC signaling/coturn, and ML Kit translation invariant on Windows without requiring Docker or external Supabase instances.

---

## Test Infrastructure

| Property | Value |
|---|---|
| **Backend Framework** | JUnit 5 + Spring Boot Test (`MockMvc`) + `EmbeddedPostgres` (`io.zonky.test:embedded-postgres:2.0.7`, no Docker) [VERIFIED: `feature/phase-5-core:backend/pom.xml#L105`] |
| **Android JVM Framework** | JUnit 4 + Mockito `5.14.2` + `InstantTaskExecutorRule` + WorkManager / OkHttp `MockWebServer` [VERIFIED: `feature/phase-5-core:gradle/libs.versions.toml#L10,L23-L24`] |
| **Android Instrumentation** | AndroidX Test JUnit (`1.3.0`) + Room Testing (`MigrationTestHelper` `1 -> 2 -> 3`, `3.json`) + `FileProvider` / Espresso (`3.7.0`) [VERIFIED: `feature/phase-5-core:app/build.gradle.kts#L138-L140`] |
| **Static Analysis** | Android Lint (`lintDebug`: zero errors, zero `RestrictedApi` from `ArchTaskExecutor`) |
| **Quick Run (Backend)** | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=MediaStorageAndRangeApiTest"` |
| **Quick Run (Android JVM)** | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.worker.MediaSendWorkerTest' --no-daemon"` |
| **Full Suite Command** | `powershell -Command "cd backend; ./mvnw.cmd test"` and `powershell -Command ".\gradlew.bat testDebugUnitTest lintDebug --no-daemon"` |
| **Instrumentation Command** | `powershell -Command ".\gradlew.bat connectedDebugAndroidTest --no-daemon"` |
| **Estimated Runtime** | Backend full ~55–90s; Android JVM + Lint ~45–100s; Instrumentation ~60–120s |

> **Windows Environment Notes:**
> - Always invoke backend and Gradle checks with valid Windows PowerShell syntax (`;`, not `&&` or `||`).
> - Keep `net.bytebuddy.experimental=true` (already configured in `app/build.gradle.kts#L70` and `backend/pom.xml#L115`) so Mockito mocks final classes smoothly.
> - Remember the Phase 5 `app/build` ACL caveat (`STATE.md#L82`): run Gradle from a clean writable workspace if `AccessDeniedException` occurs on `app/build/generated/`.

---

## Sampling Rate

- **After every task commit:** Run the targeted backend or Android JVM test class for the modified slice (`powershell -Command "cd backend; ./mvnw.cmd test -Dtest=<Suite>"` or `powershell -Command ".\gradlew.bat testDebugUnitTest --tests '<Suite>' --no-daemon"`).
- **After every plan wave:** Run `powershell -Command "cd backend; ./mvnw.cmd test"` and `powershell -Command ".\gradlew.bat testDebugUnitTest lintDebug --no-daemon"`.
- **Before `/gsd-verify-work`:** Backend suite, Android JVM unit tests, `lintDebug`, Room `MigrationTestHelper` (`1 -> 2 -> 3`), and 2-device manual E2E checks (media offline queue, mid-flight disconnect, LAN/coturn voice call, ML Kit translation) must all pass.
- **Max feedback latency:** 100 seconds.

---

## Initial Verification Matrix (Before Planner Assigns Task IDs)

| Suite / Artifact | Wave | Decisions & Reqs Covered | Secure / Invariant Behavior | Test Type | Command | File Exists | Status |
|---|---|---|---|---|---|---|---|
| `MediaStorageAndRangeApiTest` | Wave 0/1 | `D-01`, `D-02`, `D-04`, `REQ-messaging-media-attachments` | `POST /api/media` stores in `MEDIA_STORAGE_DIR` (`yyyy/MM/<uuid>.ext`), rejects `413 FILE_TOO_LARGE` (>10MB image/audio, >50MB video, >25MB file) & `415 UNSUPPORTED_TYPE` (magic-byte mismatch, SVG/EXE/APK), enforces 20 uploads/min (`429`), rejects path traversal; `GET /api/media/{id}` returns `206 Partial Content` (`Range`), `416` on bad range, `403` for non-member/removed member/rejoined pre-join `visible_from_message_id`; `POST /messages` rejects cross-user `attachmentId`, wrong `kind != type`, duplicate `attachmentId` reuse, and invalid `LOCATION` coords | Backend Integration (`EmbeddedPostgres` + `MockMvc`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=MediaStorageAndRangeApiTest"` | ❌ Wave 0 | ⬜ pending |
| `CallSignalingAndPolicyApiTest` | Wave 0/1 | `D-09`, `D-10`, `REQ-voice-call-webrtc` | `V6__media_and_calls.sql` constraints; `400 CALL_UNSUPPORTED` on group; `403 BLOCKED` before `403 CONTACT_REQUIRED`; 5 calls/min (`429`); concurrent `POST /calls` returns `409 CALL_BUSY` without ringing; atomic race `accept` vs `reject` vs `45s` timeout (`Clock` injected) ensures exactly 1 outcome; `call.handled_elsewhere` dispatched to callee's other devices; 2-device signaling lock drops `call.offer`/`call.answer`/`call.ice` from any 3rd device or non-`ACTIVE` call; repeated `end`/`reject` inserts **exactly 1 `CALL` message** (`uq_messages_call_id`); HMAC-SHA1 `iceServers[]` generation (`<expiry>:<userId>`, `Base64(HMAC-SHA1)`) + public STUN fallback | Backend Integration (`EmbeddedPostgres` + `MockMvc` + Tomcat WS) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=CallSignalingAndPolicyApiTest"` | ❌ Wave 0 | ⬜ pending |
| `FakeRepositoriesTest` (Regression) | Baseline | `REQ-messaging-realtime`, `REQ-messaging-media-attachments` | Preserves Phase 2/5 `ChatRepository` compatibility and green `FakeChatRepository` behavior | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.repository.FakeRepositoriesTest' --no-daemon"` | ✅ Exists | ⬜ pending |
| `SendMessageWorkerTest` & `MediaSendWorkerTest` | Wave 2 | `D-04`, `REQ-messaging-media-attachments` | Preserves Phase 5 `TEXT` queue behavior; tests (1) offline media queued to `filesDir/pending_media/` + `attachment_transfer` `QUEUED`/`SENDING`; (2) upload succeeds & persists `mediaUrl`, then network drops before `POST /messages` -> **retry skips `POST /media` and sends `POST /messages` with saved `mediaUrl` + same `clientId`**; (3) `413`/`415` marks `FAILED` immediately & returns `Result.success()` so subsequent messages send; (4) temp file deleted on ack & on `deleteFailedMessage` / logout; (5) `401` isolates departing session | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.worker.*' --no-daemon"` | ⚠️ Partial (`SendMessageWorkerTest` exists) | ⬜ pending |
| `NotificationAndSyncWorkerTest` | Wave 2 | `D-05`, `D-06`, `D-07`, `D-08`, `REQ-notifications-sync` | Creates exactly 2 channels (`messages`, `calls`); filters self-sent, `muted`, active foreground `conversationId`, and initial `bootstrap`; `MessagingStyle` grouping & `FLAG_IMMUTABLE` intent; `SyncWorker` 15m periodic single-flight respects `sessionKey`; logout/`401`/`4401`/account switch cancels notifications, cancels `periodic-sync:<sessionKey>`, and drops delayed callbacks | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.notification.*' --no-daemon"` | ❌ Wave 0 | ⬜ pending |
| `CallSessionCoordinatorTest` | Wave 2 | `D-10`, `D-11`, `D-12`, `REQ-voice-call-webrtc` | Uses fake `WebRtcCallEngine` + `MockWebServer`/mock `CallApi`: tests outgoing/incoming states (`RINGING` -> `CONNECTING` -> `ACTIVE` -> `ENDED`), `409 CALL_BUSY`, `45s` `MISSED`, `call.handled_elsewhere`, 2-device signaling guard, early ICE candidate buffering, mute/speaker routing toggles, `mm:ss` elapsed timer preservation, and session wipe cleanup | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.call.*' --no-daemon"` | ❌ Wave 0 | ⬜ pending |
| `MessageTranslationManagerTest` | Wave 2 | `D-13`, `D-14`, `D-15`, `REQ-message-translation` | Direct EN->VI / VI->EN selection (no Language ID); `TEXT`-only enforcement (blocks `SYSTEM`/`CALL`/textless media); Room cache hit works offline; Wi-Fi auto-download vs cellular confirmation gate; indeterminate downloading state *"Đang tải gói ngôn ngữ…"* with zero percentage estimation; offline/failure retry preserves original message; *"Đã dịch"* & *"Ẩn bản dịch"* toggle | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.translate.*' --no-daemon"` | ❌ Wave 0 | ⬜ pending |
| `AppDatabaseMigrationTest` & `MediaProviderAndUiTest` | Wave 3 | `D-02`, `D-03`, `D-04`, `D-11`, `D-14` | `MigrationTestHelper` verifies `1 -> 2 -> 3` and `2 -> 3` (`3.json`), preserving existing messages, FTS4, `attachment_transfer` new columns, and `message_translation` queries; verifies `FileProvider.getUriForFile` builds valid `content://` URIs for `media_cache/` & `pending_media/`; verifies `CallViewModel` / `ChatRoomViewModel` state preservation across `SavedStateHandle` & DayNight/EN-VI strings | Android Instrumentation + JVM | `powershell -Command ".\gradlew.bat connectedDebugAndroidTest --no-daemon"` and `powershell -Command ".\gradlew.bat testDebugUnitTest lintDebug --no-daemon"` | ⚠️ Partial (`AppDatabaseMigrationTest` exists) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ partial*

---

## Wave 0 Requirements

- [ ] `docs/API_CONTRACT.md` & `docs/FLOWS.md` — Sync `D-10` call invariants, `SupportMapFragment` -> `geo:` Intent replacement (`D-02`), `D-04` `mediaUrl` reuse, and `D-13`/`D-15` ML Kit policy (**one-way** gate before coding).
- [ ] `backend/src/main/resources/db/migration/V6__media_and_calls.sql` — Add `caller_device_id_hash`, `callee_device_id_hash`, `expires_at`, `updated_at`, `uq_calls_active_caller`, `uq_calls_active_callee`, `idx_calls_ringing_expires`, `uq_messages_call_id` (**one-way**).
- [ ] `app/schemas/com.utechat.data.local.AppDatabase/3.json` & `AppDatabase.MIGRATION_2_3` — Bump Room v2 -> v3 for `attachment_transfer` (`direction`, `localPath`, `kind`, `mime`, `sizeBytes`, `fileName`, `durationMs`, `width`, `height`, `errorCode`, `updatedAt`) (**one-way**).
- [ ] `backend/src/test/java/com/utechat/backend/media/MediaStorageAndRangeApiTest.java` — Backend integration tests for `D-01`, `D-02`, `D-04`.
- [ ] `backend/src/test/java/com/utechat/backend/call/CallSignalingAndPolicyApiTest.java` — Backend integration tests for `D-09`, `D-10`.
- [ ] `app/src/test/java/com/utechat/data/worker/MediaSendWorkerTest.java` — Android JVM tests for `D-04` offline queue & mid-flight retry.
- [ ] `app/src/test/java/com/utechat/data/notification/NotificationAndSyncWorkerTest.java` — Android JVM tests for `D-05`..`D-08`.
- [ ] `app/src/test/java/com/utechat/data/call/CallSessionCoordinatorTest.java` — Android JVM tests for `D-10`..`D-12`.
- [ ] `app/src/test/java/com/utechat/data/translate/MessageTranslationManagerTest.java` — Android JVM tests for `D-13`..`D-15`.
- [ ] Extend `app/src/androidTest/java/com/utechat/data/local/AppDatabaseMigrationTest.java` for `2 -> 3` and `1 -> 2 -> 3`, plus `MediaProviderAndUiTest.java` for `FileProvider` & room UI.

---

## Mandatory Edge-Case & Demo Scenarios Checklist

- [ ] **Offline Media Queuing (`D-04` Scenario 1):** Turn off network -> pick/compress image (<10MB), record `.m4a` voice note, pick document -> verify `attachment_transfer` persists private copies in `filesDir/pending_media/` with `SENDING` UI -> turn network back on -> WorkManager uploads and sends all queued media messages in order, then cleans up temp files.
- [ ] **Mid-Flight Disconnect After Upload (`D-04` Scenario 2):** Intercept/drop connection right after `POST /api/media` returns `201` (`mediaUrl` saved in `attachment_transfer`) and before `POST /api/conversations/{id}/messages` completes -> reconnect -> verify WorkManager retry **does NOT call `POST /api/media` again** and reuses `mediaUrl` + `clientId`.
- [ ] **HTTP `Range` & RBAC Isolation (`D-01`):** Seek video in `Media3 ExoPlayer` using Bearer token (`206 Partial Content`); confirm `403` when non-member, removed member, or rejoined pre-join member requests `/api/media/{id}`; confirm `FileProvider` opens video/document externally without any token in URL.
- [ ] **WebRTC Concurrency & Coturn Relay (`D-09`..`D-12`):**
  - Call blocked/non-friend -> `403 BLOCKED` / `403 CONTACT_REQUIRED`.
  - Call busy user -> `409 CALL_BUSY`, callee does not ring.
  - Multi-device callee: Device 1 accepts -> Device 2 stops ringing immediately (`call.handled_elsewhere`) -> Device 2 attempts `call.ice` / `call.answer` -> rejected by 2-device signaling lock.
  - 45-second unanswered ring -> server transitions `RINGING -> MISSED`, emits `call.ended`, writes **1 `CALL` message**.
  - Duplicate `POST /end` / `POST /reject` -> returns `204`, writes **1 `CALL` message** total.
- [ ] **Notifications & Session Isolation (`D-05`..`D-08`):**
  - Self-sent, muted conversation, and active foreground `ChatRoomFragment` suppress notifications; inactive conversation posts `MessagingStyle` grouped notification that deep-links to `ChatRoomFragment`.
  - Logout / `401` / `4401` cancels all notifications, cancels `periodic-sync:<sessionKey>` & `send-messages:<sessionKey>`, deletes `pending_media/` & `media_cache/`, and drops delayed results.
- [ ] **ML Kit Translation (`D-13`..`D-15`):**
  - Long-press `TEXT` -> shows *"Dịch sang Tiếng Việt"* / *"Dịch sang Tiếng Anh"* (no menu on `SYSTEM`/`CALL`/media-only).
  - Cellular download shows confirmation prompt; Wi-Fi downloads directly; displays *"Đang tải gói ngôn ngữ…"* with indeterminate progress (no %); caches in Room `message_translation`; works 100% offline afterward; *"Ẩn bản dịch"* hides inline translation cleanly.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|---|---|---|---|
| **2-Device Real Media, Offline Queue & WebRTC Audio Call (LAN + Coturn)** | `D-01`..`D-04`, `D-09`..`D-12` | Physical audio hardware, microphone permissions, `MediaRecorder`, `FusedLocationProviderClient`, and real DTLS-SRTP media relay via STUN/TURN require 2 real devices or emulator+device | 1. Start backend (`dev` or EC2 `prod` with coturn) and install debug APK (`useFakeData=false`) on 2 Android devices (Account A & Account B, `ACCEPTED` friends).<br>2. Execute `D-04` Scenario 1 (offline pick image + record voice + pick file -> reconnect) and Scenario 2 (simulate drop after upload -> verify single media row on server).<br>3. Verify fullscreen image pinch-zoom, inline voice playback, Media3 video seek + *"Mở bằng app ngoài"* via `FileProvider`, document open via `FileProvider`, and `LOCATION` card opening Google Maps via `geo:` Intent.<br>4. Test 1-1 voice call (incoming ringtone/vibration, accept with `RECORD_AUDIO` prompt, mute/speaker toggle, rotation survival, `mm:ss` timer, hang up -> 1 `CALL` message) and missed 45s timeout -> 1 `CALL` message. |
| **15-Minute Background Sync & `POST_NOTIFICATIONS` / Cellular ML Kit Download** | `D-05`..`D-08`, `D-13`..`D-15` | Real Android OS notification shade, `MessagingStyle` tap navigation, Doze/WorkManager scheduling, and real ML Kit ~30MB model download over Wi-Fi vs cellular require device OS interaction | 1. Log in on Android 13+ -> grant/deny `POST_NOTIFICATIONS` once (verify no re-prompt).<br>2. Background app -> send message from Account B -> run `adb shell cmd jobscheduler run` (or wait 15m) -> tap grouped notification -> opens exact `ChatRoomFragment`.<br>3. Switch to mobile data -> long-press `TEXT` -> *"Dịch sang Tiếng Việt"* -> confirm cellular dialog -> observe indeterminate *"Đang tải gói ngôn ngữ…"* -> turn on Airplane Mode -> translate another message 100% offline -> toggle *"Ẩn bản dịch"*. |
| **APK 16 KB ELF Page-Size Inspection (`stream-webrtc-android` & `mlkit:translate`)** | `D-12`, `D-15` | Binary ELF header inspection (`llvm-readelf`) on assembled release/debug APK | Run `Build > Analyze APK` or `llvm-readelf -l` on `lib/arm64-v8a/libjingle_peerconnection_so.so` and `libtranslate_jni.so`; record compliance status for Play/16 KB emulator notes. |

---

## Validation Sign-Off

- [ ] Every task in `06-*-PLAN.md` has `<automated>` + `<fails_when>` or points to Wave 0 scaffolds above
- [ ] Backend `cd backend; ./mvnw.cmd test` passes 100% with `EmbeddedPostgres` (zero Docker dependency)
- [ ] Android `.\gradlew.bat testDebugUnitTest lintDebug --no-daemon` passes 100% with 0 lint errors
- [ ] `AppDatabaseMigrationTest` passes `1 -> 2 -> 3` and `2 -> 3` against `3.json`
- [ ] 2-device real backend E2E (offline media, mid-flight disconnect, WebRTC voice call, notifications, ML Kit offline translation) verified before closing Phase 6
- [ ] Update Per-Task Verification Map and set `nyquist_compliant: true` once all execution checks pass

**Approval:** pending
