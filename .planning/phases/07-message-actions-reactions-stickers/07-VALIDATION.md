---
phase: "07"
slug: "message-actions-reactions-stickers"
status: verified_automated
nyquist_compliant: true
wave_0_complete: true
created: "2026-11-27"
---

# Phase 7 — Validation Strategy

> Verification contract for Phase 7 execution: proves every 24-hour recall, delete-for-me, 6-emoji reaction, sticker pack/store, offline sticker queue, and Room v3 -> v4 migration invariant on Windows without requiring Docker or external Supabase instances.

---

## Test Infrastructure

| Property | Value |
|---|---|
| **Backend Framework** | JUnit 5 + Spring Boot Test (`MockMvc`) + `EmbeddedPostgres` (`io.zonky.test:embedded-postgres`, zero Docker) |
| **Android JVM Framework** | JUnit 4 + Mockito + `InstantTaskExecutorRule` + WorkManager / OkHttp `MockWebServer` |
| **Android Instrumentation** | AndroidX Test JUnit + Room Testing (`MigrationTestHelper` `3 -> 4` & `1 -> 4`, `4.json`) |
| **Static Analysis** | Android Lint (`lintDebug`: zero errors, zero `RestrictedApi` from `ArchTaskExecutor`) |
| **Quick Run (Backend, từ thư mục backend)** | `.\mvnw.cmd test "-Dtest=FlywayMigrationTest,MessageActionsAndReactionsApiTest,StickerPacksAndMessagingApiTest"` |
| **Quick Run (Android JVM, từ thư mục repo)** | `.\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.worker.SendMessageWorkerTest' --no-daemon` |
| **Full Backend Suite** | `powershell -Command "cd backend; ./mvnw.cmd test"` |
| **Full Android JVM + Lint + Build** | `powershell -Command ".\gradlew.bat testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug --no-daemon"` |
| **Instrumentation Command** | `powershell -Command ".\gradlew.bat connectedDebugAndroidTest --no-daemon"` |
| **Estimated Runtime** | Backend full ~55–90s; Android JVM + Lint + Debug APK ~60–110s; Instrumentation ~60–120s |

> **Windows Environment Notes:**
> - Always chain commands with `;` in PowerShell (never `&&` or `||`).
> - Keep `net.bytebuddy.experimental=true` so Mockito mocks final classes cleanly on Java 17.
> - Do not switch branches during execution; verify everything cleanly against the working tree.

---

## Sampling Rate

- **After every task commit:** Run the targeted backend or Android JVM test class for the modified slice.
- **After every plan wave:** Run `powershell -Command "cd backend; ./mvnw.cmd test"` and `powershell -Command ".\gradlew.bat testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug --no-daemon"`.
- **Before `/gsd-verify-work`:** Full backend suite, full Android JVM suite, `lintDebug`, `AppDatabaseMigrationTest` (`3 -> 4` and `1 -> 4`), and 2-device manual E2E checklist must all pass.
- **Max feedback latency:** 110 seconds.

---

## Kết quả thực thi

Code kiểm chứng nằm trên nhánh `feature/phase-7-core`, tách từ `main` tại `6eb9629`. Các tên suite dưới đây là tên thực tế trong mã nguồn.

| Phạm vi | Suite | Kết quả |
|---|---|---|
| PostgreSQL V9, RLS và nâng cấp từ V8 | `FlywayMigrationTest` | 4/4 |
| Gỡ tin, thu hồi media, xóa phía mình, quyền hội thoại và cảm xúc | `MessageActionsAndReactionsApiTest` | 5/5 ở lần chạy riêng |
| Danh mục, xác thực ảnh, thêm/gỡ bộ, gửi lại và lịch sử sticker | `StickerPacksAndMessagingApiTest` | 4/4 trong lần chạy cùng các suite gửi và đồng bộ |
| JVM Android, gồm WebSocket/sync, WorkManager, cache, Retrofit và ViewModel | `testDebugUnitTest` | 288/288 |
| Room migration và DAO trên emulator | `AppDatabaseMigrationTest`, `Phase7RoomDaoTest` | 10/10 |
| Menu gỡ tin, cảm xúc và cửa hàng trên emulator với repository cục bộ | `Phase7MessageActionsUiTest` | 3/3 |
| Biên dịch instrumentation và debug APK | `compileDebugAndroidTestJavaWithJavac`, `assembleDebug` | Đạt |
| Phiên bản debug dùng backend | `assembleDebug -PuseFakeData=false` | Đạt |
| Phân tích tĩnh Android | `lintDebug` | 0 lỗi, 106 cảnh báo, 0 phát hiện `RestrictedApi` |
| Toàn bộ backend trên EmbeddedPostgres | `.\mvnw.cmd -B test` | 335/335 |

Đầu ra Gradle nằm trong thư mục tạm để tránh lỗi truy cập `app/build` trên Windows. Kiểm thử với hai thiết bị và backend thật được theo dõi ở mục nghiệm thu bên dưới.

### Kiểm tra ứng dụng với backend cục bộ

Một emulator API 37 chạy APK dùng backend thật. Tài khoản đối diện được kiểm tra qua REST trên cùng PostgreSQL nhúng đã nâng lên V9.

- Đăng nhập, mở chat trực tiếp và gửi tin qua Room/WorkManager. Tài khoản đối diện thấy tin trong lịch sử.
- Thả cảm xúc qua menu Android. API lịch sử của tài khoản đối diện trả cảm xúc mới.
- Gỡ tin qua menu Android. Chỗ cũ hiện nhãn tin đã gỡ và nội dung biến mất khỏi lịch sử của tài khoản đối diện.
- Xóa phía mình tin do tài khoản đối diện gửi. Tin biến mất trên Android của người xóa và vẫn có trong lịch sử tài khoản gửi.
- Thêm bộ UTE Học Đường, xác minh có ảnh PNG trong cache riêng, bật chế độ máy bay và chọn nhãn dán. Tin chuyển đến backend khi bật lại mạng. Sau khi gỡ bộ, nhãn dán đã gửi vẫn hiển thị trong phòng và lịch sử REST.

Emulator thứ hai giữ trạng thái offline khi chạy đồng thời trên máy này. Nghiệm thu hai thiết bị, đồng bộ xóa trên hai phiên cùng tài khoản và kiểm tra giao diện DayNight/EN/VI vẫn để mở theo checklist bên dưới.

## Wave 0 Requirements (One-Way Gates)

- [x] `docs/API_CONTRACT.md`, `docs/FLOWS.md`, `docs/ERD.md` — Sync Phase 7 endpoints, `type = "STICKER"`, 3 sync events (`MESSAGE_RECALLED`, `MESSAGE_DELETED_FOR_ME`, `MESSAGE_REACTION_UPDATED`), 5 new PostgreSQL tables, and Room v4 entities (**one-way** gate before coding).
- [x] `backend/src/main/resources/db/migration/V9__message_actions_reactions_stickers.sql` + 12 static PNG sticker assets in `backend/src/main/resources/stickers/` (**one-way**).
- [x] `app/schemas/com.utechat.data.local.AppDatabase/4.json` & `AppDatabase.MIGRATION_3_4` (**one-way**).

---

## Mandatory Edge-Case & Demo Scenarios Checklist

- [ ] **24-Hour Recall & Media Revocation (`D-01`, `D-02`, `D-05`):**
  - Sender recalls own `TEXT` and `IMAGE` within 24h -> both devices update in-place to *"Tin nhắn đã được gỡ"*, chat list preview updates, FTS4 search no longer matches recalled text, cached ML Kit translation disappears, and `GET /api/media/{id}` rejects re-download (`404`) for both sender and recipient.
  - Non-sender (including group `ADMIN`) cannot see Recall option and backend returns `403 FORBIDDEN`.
  - Message older than 24h returns `409 RECALL_EXPIRED`.
  - `SYSTEM` and `CALL` messages cannot be recalled (`409 INVALID_STATE`).
- [ ] **Delete for Me & Preview Fallback (`D-03`, `D-04`, `D-05`):**
  - User A deletes latest message for themselves -> hidden on User A's Device 1 and Device 2 (`MESSAGE_DELETED_FOR_ME`), still visible to User B.
  - User A's `ChatListFragment` preview automatically falls back to the previous remaining message (or empty if none left).
  - Works even in a left group (`left_at != null`) or blocked 1-1 chat (`D-04`), while Recall and Reactions are blocked (`403 NOT_A_MEMBER` / `403 BLOCKED`).
- [ ] **6-Emoji Reactions (`D-06`, `D-07`):**
  - Long-press shows unified BottomSheet with `❤️`, `😂`, `👍`, `😮`, `😢`, `😡` bar on top.
  - React with `❤️` -> chip appears below bubble on both devices; switch to `😂` -> replaces `❤️` (max 1 per user); tap `😂` again -> removes reaction.
  - Tap reaction chip cluster -> `ReactionDetailsBottomSheet` lists exact users and emojis.
- [ ] **Sticker Packs, Offline Cache, Uninstall & Offline Queue (`D-08`..`D-12`):**
  - Brand-new account opens Sticker tray -> `UTE Thường Ngày` (default pack) is ready immediately.
  - Open Sticker Store -> install `UTE Học Đường` -> images cache locally -> turn on Airplane Mode -> browse installed stickers offline and send one -> queued in `SendMessageWorker` (`SENDING`) -> reconnect -> auto-delivers with borderless sticker bubble.
  - Uninstall `UTE Học Đường` -> pack disappears from picker tray, **previously sent stickers from that pack remain visible in chat history**.
  - Account switch / logout wipes `filesDir/stickers/` and Room sticker/reaction tables cleanly.
- [ ] **Offline Action Guard (`D-12`):**
  - Turn on Airplane Mode -> attempt Recall, Delete for Me, or Reaction -> clear offline notice shown, message remains intact in Room.
  - Long-press `TEXT` while offline (with ML Kit model already downloaded) -> translation still succeeds 100% offline.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|---|---|---|---|
| **2-Device Realtime Recall, Delete-for-Me, Reactions & Stickers E2E** | `D-01`..`D-12` | Multi-device realtime WebSocket fanout, visual inspection of borderless sticker bubbles, BottomSheet ergonomics, DayNight & EN/VI switching, and real Airplane Mode transitions require 2 devices/emulators | 1. Run backend (`dev` profile) and install debug APK (`useFakeData=false`) on Device A (Account A) and Device B (Account B).<br>2. Send `TEXT` + `IMAGE` from A -> translate `TEXT` on B -> recall both from A -> confirm in-place *"Tin nhắn đã được gỡ"*, preview update, FTS4 removal, and media blocked.<br>3. Send 2 messages -> delete latest for me on A -> confirm A's preview falls back to message 1 while B still sees message 2.<br>4. Test 6-emoji toggle/switch/remove + tap chip cluster for details.<br>5. Install Pack 2 from Sticker Store -> turn on Airplane Mode -> send sticker -> reconnect -> verify delivery -> uninstall Pack 2 -> confirm historical sticker stays visible.<br>6. Verify DayNight dark mode and EN/VI language switch on all new sheets and trays. |

---

## Validation Sign-Off

- [ ] Every task in `07-*-PLAN.md` has `<automated>` + `<fails_when>` or points to Wave 0 scaffolds above
- [x] Backend `cd backend; ./mvnw.cmd test` passes 100% with `EmbeddedPostgres` (zero Docker dependency)
- [x] Android `.\gradlew.bat testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug --no-daemon` passes 100% with 0 lint errors
- [x] `AppDatabaseMigrationTest` passes `3 -> 4` and `1 -> 4` against `4.json`
- [ ] 2-device real backend E2E (recall 24h + media revoke, delete-for-me + preview fallback, 6-emoji reactions, sticker store + offline queue + historical preservation) verified before closing Phase 7
- [x] Ghi kết quả tự động và tên suite thực tế trong bảng kết quả thực thi. Checklist hai thiết bị vẫn chờ nghiệm thu.

**Nghiệm thu:** chờ chạy trên hai thiết bị.
