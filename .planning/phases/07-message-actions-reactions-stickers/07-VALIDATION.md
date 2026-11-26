---
phase: "07"
slug: "message-actions-reactions-stickers"
status: draft
nyquist_compliant: false
wave_0_complete: false
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
| **Quick Run (Backend)** | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=MessageActionsReactionsStickersApiTest"` |
| **Quick Run (Android JVM)** | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.worker.StickerSendWorkerTest' --no-daemon"` |
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

## Initial Verification Matrix (Before Planner Assigns Task IDs)

| Suite / Artifact | Wave | Decisions & Reqs Covered | Secure / Invariant Behavior | Test Type | Command | File Exists | Status |
|---|---|---|---|---|---|---|---|
| `V9MigrationAndRlsTest` | Wave 1 | `D-01`, `D-03`, `D-06`, `D-08`, `D-13` | `V9__message_actions_reactions_stickers.sql` runs cleanly after `V1`–`V8` on `EmbeddedPostgres`; verifies `messages.recalled_at`, `messages.sticker_id`, `attachments.revoked_at`, `messages_type_check` accepts `'STICKER'`, `sync_events_event_type_check` accepts `MESSAGE_RECALLED`/`MESSAGE_DELETED_FOR_ME`/`MESSAGE_REACTION_UPDATED`, `message_reactions` CHECK rejects emojis outside the 6 fixed emojis, all 5 new tables have `relrowsecurity = true` with 0 public policies, and 2 sample sticker packs (1 default + 1 store, 6 stickers each) are seeded | Backend Integration (`EmbeddedPostgres`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=V9MigrationAndRlsTest"` | ❌ Planned | ⬜ pending |
| `MessageRecallAndDeleteApiTest` | Wave 2 | `D-01`, `D-02`, `D-03`, `D-04`, `D-05` | Injectable `Clock` verifies `23h59m59s` recall succeeds (`200 OK`) and `24h00m01s` fails (`409 RECALL_EXPIRED`); non-sender (including group `ADMIN`) receives `403 FORBIDDEN`; `SYSTEM`/`CALL` receives `409 INVALID_STATE`; left group / blocked 1-1 receives `403 NOT_A_MEMBER` / `403 BLOCKED`; repeat recall is idempotent (`200 OK`, no duplicate `sync_events`); recall clears `text`/`attachment_id`/`latitude`/`longitude`/`sticker_id`, deletes reactions, revokes `attachments.revoked_at`, and `GET /api/media/{id}` rejects both sender and recipient (`404`); Delete-for-Me succeeds even when left group or blocked (`204`), emits `MESSAGE_DELETED_FOR_ME` **only** to caller, excludes message from `GET /messages` and `GET /media/{id}` (`403`), and falls back `ConversationDto.lastMessage` to previous remaining message (or `null`) | Backend Integration (`EmbeddedPostgres` + `MockMvc`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=MessageRecallAndDeleteApiTest"` | ❌ Planned | ⬜ pending |
| `MessageReactionApiTest` | Wave 2 | `D-04`, `D-06`, `D-07` | All 6 fixed emojis (`❤️`, `😂`, `👍`, `😮`, `😢`, `😡`, including `U+2764` normalized to `U+2764 U+FE0F`) succeed; 7th emoji (`🔥`) fails `400 VALIDATION`; selecting new emoji replaces old (`PRIMARY KEY (message_id, user_id)`); tapping same emoji or `emoji: null` removes reaction; `SYSTEM` or recalled message fails `409 INVALID_STATE`; left group / blocked 1-1 fails `403 NOT_A_MEMBER` / `403 BLOCKED`; emits `MESSAGE_REACTION_UPDATED` to active members and returns reactions in `GET /messages` | Backend Integration (`EmbeddedPostgres` + `MockMvc`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=MessageReactionApiTest"` | ❌ Planned | ⬜ pending |
| `StickerPackAndSendApiTest` | Wave 2 | `D-08`, `D-09`, `D-10` | New account gets `ute_daily` (`installed = true`) and `ute_campus` (`installed = false`) from `GET /api/stickers/packs`; `POST`/`DELETE /api/stickers/packs/{packId}/install` persist and sync across calls (including uninstalling default pack); `GET /api/stickers/assets/{packCode}/{fileName}` requires Bearer (`401` without), serves PNG with `X-Content-Type-Options: nosniff`, blocks path traversal (`400`/`404`); `POST /conversations/{id}/messages` with `type = "STICKER"` + `stickerId` returns `201` (and `200` on `clientId` replay), rejects non-null `text`/`mediaUrl`, and historical sticker message remains readable after uninstalling pack | Backend Integration (`EmbeddedPostgres` + `MockMvc`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=StickerPackAndSendApiTest"` | ❌ Planned | ⬜ pending |
| `SyncEventApplierPhase7Test` | Wave 3 | `D-02`, `D-03`, `D-05`, `D-07`, `D-10` | Verifies `MESSAGE_RECALLED` sets `recalledAt`, clears `text`/`normalized_text`/`attachmentJson`, deletes cached translations in `message_translation`, and clears reactions; `MESSAGE_DELETED_FOR_ME` deletes local `message` row, clears reactions/translations, and recomputes `conversation.lastMessageId`; `MESSAGE_REACTION_UPDATED` upserts/deletes `message_reaction`; `MESSAGE_CREATED` with `type = "STICKER"` preserves sticker metadata in `attachmentJson` | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.sync.SyncEventApplierPhase7Test' --no-daemon"` | ❌ Planned | ⬜ pending |
| `StickerSendWorkerTest` & `StickerCacheManagerTest` | Wave 3 | `D-08`, `D-09`, `D-10`, `D-12` | `SendMessageWorker` sends queued `type = "STICKER"` with `stickerId` + `clientId` without touching `MediaApi`, handles `clientId` idempotent replay, `401` session isolation, and `409 MEMBERSHIP_CHANGED`; `ApiClient.resolveBackendStickerUrl()` accepts `/api/stickers/assets/{pack}/{file}.png` and rejects external URLs/traversal; `StickerCacheManager` downloads to `filesDir/stickers/` and cleans up on session wipe | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.worker.*' --tests 'com.utechat.data.sticker.*' --no-daemon"` | ❌ Planned | ⬜ pending |
| `Phase7UiAndViewModelTest` & `FakeRepositoriesTest` | Wave 4 | `D-01`, `D-04`, `D-05`, `D-06`, `D-07`, `D-10`, `D-11`, `D-12` | `MessageAdapter` preserves viewTypes `0..5`, maps `STICKER` to `TYPE_STICKER_OUT`/`IN` (`6`/`7`), renders recalled messages as *"Tin nhắn đã được gỡ"* (`"Message recalled"`), groups reaction chips; `SystemMessageFormatter.preview()` formats `STICKER` and recalled previews; `MessageActionBottomSheet` visibility matrix (sender vs non-sender, <24h vs >24h, `TEXT` vs `SYSTEM`/`CALL`/recalled, active vs read-only/blocked); offline recall/delete/reaction returns `NETWORK` error without mutating Room; `FakeRepositoriesTest` remains 100% green | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.ui.chat.*' --tests 'com.utechat.data.repository.FakeRepositoriesTest' --no-daemon"` | ⚠️ Partial (`FakeRepositoriesTest` exists) | ⬜ pending |
| `AppDatabaseMigrationTest` (`3 -> 4`, `1 -> 4`) | Wave 1 / Wave 4 | `D-13` | `MigrationTestHelper` validates `MIGRATION_3_4` and `1 -> 2 -> 3 -> 4` against `4.json`, preserving existing messages, `attachment_transfer`, `message_translation`, and FTS4; verifies `message.recalledAt` defaults to `NULL`, FTS4 drops recalled text on update, and `message_reaction` / `sticker_pack` / `sticker` tables work | Android Instrumentation | `powershell -Command ".\gradlew.bat connectedDebugAndroidTest --no-daemon"` | ⚠️ Partial (exists up to v3) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ partial*

---

## Wave 0 Requirements (One-Way Gates)

- [ ] `docs/API_CONTRACT.md`, `docs/FLOWS.md`, `docs/ERD.md` — Sync Phase 7 endpoints, `type = "STICKER"`, 3 sync events (`MESSAGE_RECALLED`, `MESSAGE_DELETED_FOR_ME`, `MESSAGE_REACTION_UPDATED`), 5 new PostgreSQL tables, and Room v4 entities (**one-way** gate before coding).
- [ ] `backend/src/main/resources/db/migration/V9__message_actions_reactions_stickers.sql` + 12 static PNG sticker assets in `backend/src/main/resources/stickers/` (**one-way**).
- [ ] `app/schemas/com.utechat.data.local.AppDatabase/4.json` & `AppDatabase.MIGRATION_3_4` (**one-way**).

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
- [ ] Backend `cd backend; ./mvnw.cmd test` passes 100% with `EmbeddedPostgres` (zero Docker dependency)
- [ ] Android `.\gradlew.bat testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug --no-daemon` passes 100% with 0 lint errors
- [ ] `AppDatabaseMigrationTest` passes `3 -> 4` and `1 -> 4` against `4.json`
- [ ] 2-device real backend E2E (recall 24h + media revoke, delete-for-me + preview fallback, 6-emoji reactions, sticker store + offline queue + historical preservation) verified before closing Phase 7
- [ ] Update Per-Task Verification Map and set `nyquist_compliant: true` once all execution checks pass

**Approval:** pending
