---
phase: "05"
slug: "realtime-offline-first-core-messaging"
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-11-19"
---

# Phase 5 — Validation Strategy

> Hợp đồng kiểm chứng trong quá trình thực thi Phase 5: bảo đảm mọi bất biến dữ liệu, bảo mật và offline-first được kiểm chứng tự động ngay từng đợt mà không phụ thuộc Docker hay Supabase ngoài.

---

## Test Infrastructure

| Property | Value |
|---|---|
| **Backend Framework** | JUnit 5 + Spring Boot Test (`MockMvc`) + `EmbeddedPostgres` (`io.zonky.test:embedded-postgres:2.0.7`, không cần Docker) |
| **Android JVM Framework** | JUnit 4 + Mockito 5.14.2 + `InstantTaskExecutorRule` + `WorkManager` test / OkHttp `MockWebServer` |
| **Android Instrumentation** | AndroidX Test JUnit (`1.3.0`) + Room Testing (`MigrationTestHelper` / in-memory SQLite + FTS4 thật) + Espresso (`3.7.0`) |
| **Static Analysis** | Android Lint (`lintDebug`, đặc biệt không có lỗi `RestrictedApi` từ `ArchTaskExecutor`) |
| **Quick run (Backend)** | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=MessagingConcurrencyAndPolicyTest"` |
| **Quick run (Android JVM)** | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.repository.FakeRepositoriesTest' --no-daemon"` |
| **Full suite command** | `powershell -Command "cd backend; ./mvnw.cmd test"` và `powershell -Command ".\gradlew.bat testDebugUnitTest lintDebug --no-daemon"` |
| **Instrumentation command** | `powershell -Command ".\gradlew.bat connectedDebugAndroidTest --no-daemon"` |
| **Estimated runtime** | Backend full ~45–75s; Android JVM + Lint ~40–90s; Instrumentation ~60–120s |

---

## Sampling Rate

- **After every task commit:** Chạy suite test tương ứng của module vừa sửa (`./mvnw.cmd test -Dtest=<Suite>` hoặc `.\gradlew.bat testDebugUnitTest --tests '<Suite>' --no-daemon`).
- **After every plan wave:** Chạy toàn bộ `cd backend; ./mvnw.cmd test` và `.\gradlew.bat testDebugUnitTest lintDebug --no-daemon`.
- **Before `/gsd-verify-work`:** Backend `mvn test`, Android JVM unit test, `lintDebug`, instrumentation tests Room/Migration/FTS4 và nghiệm thu thực tế 2 tài khoản phải xanh 100%.
- **Max feedback latency:** 90 giây.

---

## Initial Verification Matrix (Before Planner Assigns Task IDs)

| Suite / Artifact | Wave | Requirements Covered | Threat Ref | Secure / Invariant Behavior | Test Type | Command | File Exists | Status |
|---|---|---|---|---|---|---|---|---|
| `MessagingConcurrencyAndPolicyTest` | Wave 0/1 | `REQ-messaging-realtime`, `REQ-messaging-delivery-read-status` | T-05-03, T-05-04 | `direct_key` race 2 luồng chỉ tạo 1 row, `403 BLOCKED` trước `CONTACT_REQUIRED` (kể cả sau khi mở chat), chống trùng `clientId`, 30 tin/10s, watermark không giảm & `READ <= DELIVERED` | Backend Integration (`EmbeddedPostgres`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=MessagingConcurrencyAndPolicyTest"` | ❌ Wave 0 | ⬜ pending |
| `GroupChatApiTest` | Wave 1 | `REQ-messaging-group-chat` | T-05-03, T-05-04 | Tối đa 50 thành viên (`GROUP_FULL`), chỉ thêm bạn `ACCEPTED`, `NOT_ADMIN` / `NOT_A_MEMBER`, strict `serverId > visible_from_message_id`, bàn giao `ADMIN` + rời nhóm nguyên tử 3 nhánh, send/remove/re-add cùng khóa và 409 MEMBERSHIP_CHANGED khi sai mốc, `SYSTEM` idempotent (không cho `muted`) | Backend Integration (`EmbeddedPostgres`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=GroupChatApiTest"` | ❌ Wave 0 | ⬜ pending |
| `SyncAndBootstrapApiTest` | Wave 1 | `REQ-messaging-offline-first`, `REQ-messaging-group-chat` | T-05-03, T-05-06 | `GET /api/sync/bootstrap` `REPEATABLE READ`, phân trang `/sync?after=&limit=`, `410 SYNC_CURSOR_EXPIRED`, 2 JDBC tx commit `sync_events` ngược thứ tự ID không bỏ sót sự kiện (`barrier_xid <= xmin`); retryAfterMs khi còn sự kiện bị giữ và bắt kịp không cần WS mới, thành viên rời chỉ nhận 1 sự kiện cuối | Backend Integration (`EmbeddedPostgres`) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=SyncAndBootstrapApiTest"` | ❌ Wave 0 | ⬜ pending |
| `WebSocketAuthAndRealtimeTest` | Wave 1 | `REQ-messaging-realtime`, `REQ-messaging-offline-first`, `REQ-messaging-delivery-read-status` | T-05-01, T-05-02 | Bearer handshake `/ws`, **chặn JWT trong URL query**, `typing.start` throttle 1/3s, đẩy frame `afterCommit`, JSON heartbeat 25s giữ socket idle hơn 90s, `DEVICE_REVOKED` gửi `auth.revoked` + đóng `4401` | Backend Integration (`EmbeddedPostgres` + Tomcat WS) | `powershell -Command "cd backend; ./mvnw.cmd test -Dtest=WebSocketAuthAndRealtimeTest"` | ❌ Wave 0 | ⬜ pending |
| `FakeRepositoriesTest` (hồi quy) | Baseline | `REQ-messaging-realtime` | — | Giữ nguyên tương thích seam `ChatRepository` & `FakeChatRepository` | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.repository.FakeRepositoriesTest' --no-daemon"` | ✅ Exists | ⬜ pending |
| `SendMessageWorkerTest` | Wave 2 | `REQ-messaging-offline-first`, `REQ-messaging-realtime` | T-05-05 | Offline giữ `SENDING`, phân loại mạng/`408`/`5xx`/`429` (`Retry-After`, max 3 lượt) vs `400`/`403`/`404` (`FAILED` + trả `Result.success()` không kẹt chuỗi) vs `401` chỉ dừng đúng phiên (401 trễ không hại lần đăng nhập mới), GROUP sai/thiếu queuedVisibleFromMessageId thành FAILED không tự gửi; Gửi lại giữ `clientId`, Xóa tin lỗi không cancel node, không hủy tin khác | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.worker.SendMessageWorkerTest' --no-daemon"` | ❌ Wave 0 | ⬜ pending |
| `SyncEngineAndApplierTest` | Wave 2 | `REQ-messaging-offline-first`, `REQ-messaging-group-chat` | T-05-05 | Single-flight gộp WS burst, `410 SYNC_CURSOR_EXPIRED` bootstrap lại, WS không tự nâng cursor, áp dụng idempotent 8 loại event (Chat + 3 Contact events + CALL_UPDATED metadata), WS không ghi Room, retryAfterMs tự hẹn sync không busy-loop, bỏ callback/event cũ khi đổi phiên | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest --tests 'com.utechat.data.sync.SyncEngineAndApplierTest' --no-daemon"` | ❌ Wave 0 | ⬜ pending |
| `ChatViewModelTest` & `RoomConvertersTest` | Wave 2 | `REQ-messaging-realtime`, `REQ-messaging-search`, `REQ-messaging-delivery-read-status` | T-05-03 | An toàn `MessageText.safeMatchQuery` (ký tự `MATCH`, rỗng), bảo toàn metadata non-TEXT, `SavedStateHandle`, READ theo viewport (tin đã tải nhưng chưa thấy vẫn unread), phân trang tăng windowLimit/giữ anchor/endReached, banner `BLOCKED`/`CONTACT_REQUIRED` & thanh trạng thái WS | Android JVM | `powershell -Command ".\gradlew.bat testDebugUnitTest lintDebug --no-daemon"` | ❌ Wave 0 (một phần đã có) | ⬜ pending |
| `AppDatabaseMigrationTest`, `MessagingRoomAndFtsTest`, `ChatNavigationAndSearchUiTest` | Wave 3 | `REQ-messaging-search`, `REQ-messaging-group-chat`, `REQ-messaging-offline-first` | T-05-05 | `Migration(1, 2)` thêm `visibleFromMessageId`, `errorCode` & `queuedVisibleFromMessageId` giữ nguyên dữ liệu cũ, FTS4 có/không dấu & xóa cache, `serverId > visibleFromMessageId` + bảo vệ `serverId = NULL`, mở tin ngoài trang từ tìm kiếm toàn cục, render thẻ tóm tắt non-TEXT không crash | Android Instrumentation | `powershell -Command ".\gradlew.bat connectedDebugAndroidTest --no-daemon"` | ❌ Wave 0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/com/utechat/backend/messaging/MessagingConcurrencyAndPolicyTest.java` — test 1-1 `direct_key`, `BLOCKED`/`CONTACT_REQUIRED`, `clientId`, rate limit, watermark (`D-01`, `D-04`, `D-11`, `D-16`).
- [ ] `backend/src/test/java/com/utechat/backend/messaging/GroupChatApiTest.java` — test nhóm 50 người, chỉ bạn `ACCEPTED`, `visible_from_message_id` (`>`), bàn giao `ADMIN` + rời nguyên tử 3 nhánh, tin `SYSTEM` (`D-09`, `D-10`, `D-12`, `D-14`, `D-16`).
- [ ] `backend/src/test/java/com/utechat/backend/sync/SyncAndBootstrapApiTest.java` — test bootstrap snapshot+cursor `REPEATABLE READ`, phân trang `/sync`, `410 SYNC_CURSOR_EXPIRED`, 2 transaction commit khác thứ tự ID (`D-06`, `D-07`, `D-16`).
- [ ] `backend/src/test/java/com/utechat/backend/ws/WebSocketAuthAndRealtimeTest.java` — test Bearer handshake, chặn JWT query param, `typing` throttle, `afterCommit` WS push, `DEVICE_REVOKED` đóng `4401` (`D-03`, `D-07`, `D-16`).
- [ ] `app/src/test/java/com/utechat/data/worker/SendMessageWorkerTest.java` — test WorkManager phân loại lỗi, retry giữ `clientId`, xóa tin lỗi cục bộ, dừng khi `401` (`D-05`, `D-16`).
- [ ] `app/src/test/java/com/utechat/data/sync/SyncEngineAndApplierTest.java` — test `SyncEngine` single-flight, `410` bootstrap lại, `SyncEventApplier` đủ Chat + 3 Contact events, cách ly phiên (`D-03`, `D-06`, `D-07`, `D-16`).
- [ ] `app/src/test/java/com/utechat/ui/chat/ChatViewModelTest.java` — test ViewModel danh sách chat, phòng chat, nhóm, tìm kiếm (`D-01`..`D-15`).
- [ ] `app/src/androidTest/java/com/utechat/data/local/AppDatabaseMigrationTest.java` — test `Migration(1, 2)` dùng `MigrationTestHelper` trên schema `1.json` → `2.json` (`D-14`, `D-16`).
- [ ] `app/src/androidTest/java/com/utechat/data/local/MessagingRoomAndFtsTest.java` — test FTS4 tiếng Việt có/không dấu, ký tự đặc biệt `MATCH`, cập nhật FTS khi purge `visibleFromMessageId`, bảo vệ `serverId = NULL` (`D-13`, `D-14`, `D-16`).
- [ ] `app/src/androidTest/java/com/utechat/ui/chat/ChatNavigationAndSearchUiTest.java` — test điều hướng, mở đúng tin ngoài trang từ tìm kiếm toàn cục, thẻ tóm tắt non-TEXT bảo toàn metadata (`D-02`, `D-08`, `D-13`, `D-15`, `D-16`).

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|---|---|---|---|
| Nghiệm thu thực tế 2 tài khoản trên 2 Emulator/thiết bị kết nối Backend thật (`USE_FAKE_DATA = false`) | Toàn bộ Phase 5 (`D-16`) | Kiểm chứng luồng đầu-cuối đa thiết bị thật (giao diện, mạng bật/tắt, WebSocket reconnect, tương tác người dùng thật) | 1. Bật backend Spring Boot (`dev`) + tài khoản A & B trên 2 máy.<br>2. Test chat 1-1: tìm SĐT người lạ (tắt/bật `allowMessagesFromNonContacts` → `CONTACT_REQUIRED` & gợi ý kết bạn), block realtime qua `/ws`, chọn lại mở đúng phòng.<br>3. Tắt mạng máy A → gửi 2 tin `TEXT` (thấy thanh trạng thái WS mất mạng + tin `SENDING`) → bật mạng lại → kiểm tra WorkManager gửi đúng thứ tự lên B và biên nhận lên `SENT`→`DELIVERED`→`READ`.<br>4. Tạo nhóm 3 người, đổi tên, thu hồi thiết bị (`4401`), bàn giao `ADMIN` & rời nhóm (chế độ chỉ đọc), thêm lại kiểm chứng `visible_from_message_id` (`>`), tìm kiếm FTS4 có/không dấu trong phòng và toàn cục ("Tin nhắn trên thiết bị"). |
| Nối nút "Nhắn tin" từ `ContactsListFragment` (Phase 4) vào điểm mở chat 1-1 (`D-02`) | `REQ-messaging-realtime` (`D-02`) | Phụ thuộc Tv2 hoàn tất UI Phase 4 rồi mới gắn nút trên `ContactsListFragment` | Sau khi Tv2 xong UI Phase 4, gắn click nút "Nhắn tin" gọi `ChatNavigation.openDirectChat` / `utechat://chat/direct/{peerUserId}` và xác nhận bấm từ Danh bạ mở đúng phòng chat 1-1. |

---

## Regression cases from Phase 5 plan review

- [ ] GROUP send/remove/re-add serialize under the same row lock; stale expectedVisibleFromMessageId returns 409 without message/event writes.
- [ ] WS data frames never write Room; older sync replay cannot roll back a newer optimistic WS state because that path is absent.
- [ ] Offline and migrated GROUP pending rows preserve their queued cutoff; no auto-send across tenure, including stale client cache rejected by server.
- [ ] Application JSON ping/pong every 25s keeps an idle socket alive beyond 90s; idle timeout/revocation and timer cleanup are tested with a fake clock.
- [ ] READ follows actual viewport after layout while RESUMED; later unseen messages retain unread status.
- [ ] Older history appears after window expansion; cached pages, scroll anchor and endReached are tested.
- [ ] Exact V2 CALL_UPDATED seed payload is explicitly handled (cached CALL update or known no-match no-op) and cursor can advance.
- [ ] SessionKey persists across process restart for the same login; delayed A-session 401/cancellation cannot affect B; deleting FAILED does not cancel following messages in other conversations.
- [ ] retryAfterMs schedules one guarded retry, does not spin, and catches held events after blocker ends without another WS notification.

## Validation Sign-Off

- [ ] Tất cả task trong `05-*-PLAN.md` có `<automated>` + `<fails_when>` hoặc trỏ scaffold Wave 0 tương ứng
- [ ] Sampling continuity bảo đảm ở mọi wave
- [ ] Không dùng cờ watch-mode; `cd backend; ./mvnw.cmd test` chạy độc lập không cần Docker (`EmbeddedPostgres`)
- [ ] Android `lintDebug` đạt 0 lỗi (không sử dụng `ArchTaskExecutor`)
- [ ] Cập nhật bảng Per-Task Verification Map khi planner chốt Plan/Task ID và đặt `nyquist_compliant: true` khi thực thi xong
- [ ] Nghiệm thu thực tế 2 tài khoản với backend thật hoàn tất trước khi chốt Phase 5

**Approval:** pending
