# Phase 5: Realtime & Offline-First Core Messaging - Pattern Mapping

**Phase:** 05 — Realtime & Offline-First Core Messaging (`REQ-messaging-realtime`, `REQ-messaging-offline-first`, `REQ-messaging-delivery-read-status`, `REQ-messaging-group-chat`, `REQ-messaging-search`)  
**Date:** 2026-11-19  
**Author:** GSD Pattern Mapper  
**Status:** COMPLETE

> **Ghi chú trạng thái mã nguồn trên đĩa (`git ls-files`):** Toàn bộ tệp mẫu đối chiếu trong tài liệu này đều được kiểm chứng tồn tại trực tiếp trên nhánh hiện tại (`main`/`planning`). Các thành phần Phase 4 đang nằm trên nhánh `origin/phase4_NP` (`ContactPolicyService`, `SyncEventWriter`, `RealContactsRepository` đầy đủ, `SessionManager.sessionKey()`) hiện **chưa** được merge vào working tree (`ContactApi`, `RealContactsRepository`, `ContactsListFragment` trên đĩa vẫn là khung stub Phase 2); do đó mọi tham chiếu tích hợp với Phase 4 được đối chiếu qua hợp đồng trong [05-CONTEXT.md](05-CONTEXT.md) và [05-RESEARCH.md](05-RESEARCH.md#L173-L187).

---

## 1. Component Inventory (Danh sách đầy đủ Tệp Tạo mới & Chỉnh sửa)

Danh sách rút trích đầy đủ từ `05-CONTEXT.md` (`D-01`..`D-17`) và `05-RESEARCH.md`, phân loại theo vai trò, luồng dữ liệu và tệp mẫu đối chiếu (`git ls-files`).

### 1.1 Tài liệu Hợp đồng (`docs/`)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`git ls-files`) | Ghi chú triển khai |
|---|---|---|---|---|
| `docs/API_CONTRACT.md` | Modified | Cập nhật hợp đồng REST/WS dùng chung (`GET /sync/bootstrap`, `GET /sync` `hasMore` + `410 SYNC_CURSOR_EXPIRED`, `visibleFromMessageId` & `leftAt`, `DELETE /conversations/{id}/members/{userId}?successorUserId=`, `contact.updated` WS, bảng `systemEvent`/`systemParams`, mã lỗi `SYNC_CURSOR_EXPIRED`, `ADMIN_HANDOVER_REQUIRED`) | `docs/API_CONTRACT.md#L68-L236` | Cập nhật đầu Phase 5 theo `D-06`, `D-09`, `D-10`, `D-14`, `D-17` & `05-RESEARCH.md#L302-L310` |

---

### 1.2 Backend Spring Boot 3 (`backend/`)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`git ls-files`) | Ghi chú triển khai |
|---|---|---|---|---|
| `backend/pom.xml` | Modified | Bổ sung dependency `spring-boot-starter-websocket` (managed bởi Spring Boot `3.3.5`) | `backend/pom.xml#L20-L40` | Giữ `java.version = 17` và `embedded-postgres:2.0.7` (`scope=test`) |
| `backend/src/main/resources/db/migration/V5__sync_and_messaging.sql` | Created | Thêm `sync_events.barrier_xid xid8`, trigger `fn_sync_events_set_barrier` (`BEFORE INSERT`), bảng `sync_retention_state` + `ENABLE ROW LEVEL SECURITY` | *No analog (greenfield)*; cấu trúc bảng/RLS theo `V1__initial_schema.sql#L151-L200`, `V4__contacts_friend_flag.sql#L1-L13` | Bảo đảm lock-free gap-safe `/api/sync` (`05-RESEARCH.md#L234-L285`), tương thích H2 |
| `backend/src/main/java/com/utechat/backend/config/TimeConfig.java` | Created *(nếu chưa có từ Phase 4)* | Spring `@Configuration` cấp `@Bean Clock` UTC phục vụ `MessageRateLimiter` & `ChatWebSocketHandler` | `backend/src/main/java/com/utechat/backend/config/CorsConfig.java` | Tránh xung đột chữ ký nếu merge `origin/phase4_NP` (`05-RESEARCH.md#L178`) |
| `backend/src/main/java/com/utechat/backend/config/WebSocketConfig.java` | Created | `@Configuration @EnableWebSocket` đăng ký endpoint `/ws`, gắn `WsAuthHandshakeInterceptor` và `ChatWebSocketHandler` | *No analog (greenfield WebSocket)* → `05-RESEARCH.md#L193-L200` | Không dùng STOMP; OkHttp Android không gửi `Origin` |
| `backend/src/main/java/com/utechat/backend/config/SecurityConfig.java` | Modified | Giữ `.anyRequest().authenticated()` cho REST + `/ws` Upgrade | `backend/src/main/java/com/utechat/backend/config/SecurityConfig.java#L18-L36` | Rà soát `JwtAuthenticationFilter` chạy trước `WebSocketHttpRequestHandler` |
| `backend/src/main/java/com/utechat/backend/security/JwtAuthenticationFilter.java` | Modified | Gắn thêm `deviceRowId`, `issuedAtMs`, `tokenExpiresAt` vào request attributes (hoặc `AuthenticatedActor`) cho `/ws` handshake | `backend/src/main/java/com/utechat/backend/security/JwtAuthenticationFilter.java#L31-L67` | Giúp `WebSocketSessionRegistry` tra cứu và đóng `4401` khi revoke/hết hạn (`05-RESEARCH.md#L198-L199`) |
| `backend/src/main/java/com/utechat/backend/exception/GlobalExceptionHandler.java` | Modified | Bắt thêm `MissingServletRequestParameterException` -> `400 VALIDATION` (nếu chưa merge Phase 4); hỗ trợ mọi `ApiException` (`410`, `409`, `429`) | `backend/src/main/java/com/utechat/backend/exception/GlobalExceptionHandler.java#L19-L43` | Chuẩn hóa `{ "error": { "code", "message", "details" } }` |
| `backend/src/main/java/com/utechat/backend/dto/MessagingDtos.java` | Created | Immutable Java `record` DTOs: `DirectConversationRequest`, `CreateGroupRequest`, `UpdateConversationRequest`, `AddMembersRequest`, `UpdateRoleRequest`, `SendMessageRequest`, `WatermarkRequest`, `ConversationDto`, `MemberDto` (`visibleFromMessageId`, `leftAt`), `MessageDto`, `BootstrapResponse`, `SyncResponse`, `WsFrame` | `backend/src/main/java/com/utechat/backend/dto/AuthDtos.java#L6-L38` | `camelCase`, `@JsonInclude(NON_NULL)`, không lộ `phone` người lạ |
| `backend/src/main/java/com/utechat/backend/service/ContactPolicyService.java` | Created *(hoặc tái sử dụng Phase 4)* | Ranh giới kiểm quyền `lockPair(a, b)`, `isBlockedEitherWay(a, b)`, `areAcceptedFriends(a, b)` | `AccountService.java#L20-L30` (pattern `@Service` + `JdbcTemplate`); hợp đồng theo `05-RESEARCH.md#L175` | Luôn kiểm `BLOCKED` trước `CONTACT_REQUIRED` (`D-01`, `D-04`) |
| `backend/src/main/java/com/utechat/backend/service/SyncEventWriter.java` | Created / Modified | `@Transactional(propagation = Propagation.MANDATORY)` ghi `sync_events` và gom sự kiện theo transaction để phát qua `afterCommit()` | `AccountService.java#L91-L92` (SQL mẫu); hợp đồng theo `05-RESEARCH.md#L176,L226-L228` | Giữ chữ ký `write(UUID, String, String, String, Object)` tương thích Phase 4 |
| `backend/src/main/java/com/utechat/backend/service/MessageRateLimiter.java` | Created | Sliding Window Log `30 tin / 10 giây / userId` -> `429 RATE_LIMITED` + `retryAfterSeconds` | *No analog (greenfield)*; ném `ApiException` như `ThrottleService.java#L34-L38` | Dùng `Clock` inject được để test xác định (`05-RESEARCH.md#L139`) |
| `backend/src/main/java/com/utechat/backend/service/ConversationService.java` | Created | Tạo/mở chat 1-1 (`direct_key`), tạo nhóm (`>=2` bạn `ACCEPTED`, `<=50`), danh sách/chi tiết, đổi tên, `muted`, thêm/xóa thành viên, đổi role, bàn giao `ADMIN` + rời nhóm nguyên tử, `visible_from_message_id` strict `>`, sinh tin `SYSTEM` idempotent | `AccountService.java#L46-L93`, `AuthService.java#L65-L80` (`@Transactional`, `DuplicateKeyException`, `FOR UPDATE`) | Khóa row `conversations ... FOR UPDATE`; không tạo `SYSTEM` cho `muted`/no-op (`D-09`..`D-14`) |
| `backend/src/main/java/com/utechat/backend/service/MessageService.java` | Created | Tải lịch sử phân trang `?before=&limit=` (`id > visible_from_message_id`), gửi tin `TEXT` chống trùng `clientId`, rate limit `30/10s`, kiểm `NOT_A_MEMBER` & `BLOCKED` -> `CONTACT_REQUIRED`, ghi `MESSAGE_CREATED` | `AccountService.java#L67-L93`, `AuthService.java#L164-L175` (`ON CONFLICT DO NOTHING`) | Chỉ chấp nhận `type = "TEXT"`, từ chối `SYSTEM` từ client (`D-15`) |
| `backend/src/main/java/com/utechat/backend/service/ReceiptService.java` | Created | Cập nhật `last_delivered_message_id` & `last_read_message_id` đơn điệu (`last_delivered >= last_read`), ghi `RECEIPT_UPDATED` khi tăng thực sự | `AccountService.java#L67-L74`, `V1__initial_schema.sql#L95-L103` | Kiểm tra `left_at IS NULL` (`403 NOT_A_MEMBER`) (`D-11`, `D-12`) |
| `backend/src/main/java/com/utechat/backend/service/SyncService.java` | Created | `GET /api/sync/bootstrap` (`REPEATABLE READ READ ONLY` snapshot + cursor) và `GET /api/sync?after=&limit=` (`barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())`, `410 SYNC_CURSOR_EXPIRED`) | *No analog (greenfield)* → `05-RESEARCH.md#L259-L300` | Không dùng `id == last + 1`; không nâng cursor trên nhóm đã rời |
| `backend/src/main/java/com/utechat/backend/service/SyncRetentionService.java` | Created | Job `@Scheduled` mỗi ngày xóa `sync_events` quá 30 ngày (`barrier_xid <= xmin`) và cập nhật `sync_retention_state.min_available_cursor` | `AccountService.java#L116-L122` (`@Scheduled`) | `@Transactional`, giữ cursor an toàn để trả `410 SYNC_CURSOR_EXPIRED` |
| `backend/src/main/java/com/utechat/backend/service/AccountService.java` | Modified | `revokeDevice`: `RETURNING device_id_hash`, dùng `SyncEventWriter`, đẩy `auth.revoked` + đóng WS `4401` sau commit; `changePassword`/`scheduleDeletion`/`purgeExpiredDeletions`: đóng WS `4401` + tự phong `ADMIN` kế nhiệm khi purge user | `AccountService.java#L67-L136` | Giao dịch purge chạy ngắn theo từng user (`05-RESEARCH.md#L215-L220,L257,L327`) |
| `backend/src/main/java/com/utechat/backend/service/AuthService.java` | Modified | `logout` và `completePasswordReset`: đóng session WS của thiết bị bị thu hồi với `CloseStatus(4401)` sau commit | `AuthService.java#L127-L146` | Đẩy đóng socket sau commit (`05-RESEARCH.md#L218-L219`) |
| `backend/src/main/java/com/utechat/backend/websocket/WsAuthHandshakeInterceptor.java` | Created | Từ chối JWT trong query param (`token`, `jwt`, `access_token`, `bearer`, `authorization`) với HTTP `400`/`401`; chép actor + `deviceRowId` + `tokenExpiresAt` vào WS session attributes | *No analog (greenfield WebSocket)* → `05-RESEARCH.md#L194-L200` | Tuyệt đối không ghi log query string (`DEPLOYMENT_SECURITY.md`) |
| `backend/src/main/java/com/utechat/backend/websocket/WebSocketSessionRegistry.java` | Created | Lưu session thread-safe theo `(userId, deviceIdHash)`, bọc `ConcurrentWebSocketSessionDecorator`, gửi frame, đóng `4401`, reaper `@Scheduled(fixedDelay = 30_000)` kiểm hạn JWT & idle `75s` | *No analog (greenfield WebSocket)* → `05-RESEARCH.md#L202-L214` | Ngăn `IllegalStateException: TEXT_FULL_WRITING` của Tomcat |
| `backend/src/main/java/com/utechat/backend/websocket/SyncEventRealtimeDispatcher.java` | Created | Lắng nghe danh sách event đã commit (`afterCommit()`), map sang WS envelope `message.new`, `message.status`, `conversation.updated`, `contact_request.new`, `contact_request.updated`, `contact.updated`, `auth.revoked` | *No analog (greenfield WebSocket)* → `05-RESEARCH.md#L226-L228` | Dùng chung cho cả Phase 4 (`ContactsService`) và Phase 5 |
| `backend/src/main/java/com/utechat/backend/websocket/ChatWebSocketHandler.java` | Created | `TextWebSocketHandler`: `afterConnectionEstablished/Closed`, `handlePongMessage`, JSON `ping`->`pong` (kèm kiểm tra device chưa revoke & update `last_seen_at` throttled), `typing.start` (throttle 1/3s) & `typing.stop` | *No analog (greenfield WebSocket)* → `05-RESEARCH.md#L208-L225` | Kiểm `left_at IS NULL` khi forward `typing`; `typing` không vào `sync_events` |
| `backend/src/main/java/com/utechat/backend/controller/ConversationController.java` | Created | REST endpoints `/api/conversations/**` (`GET`, `POST /direct`, `POST /group`, `GET /{id}`, `PATCH /{id}`, `POST /{id}/members`, `DELETE /{id}/members/{userId}`, `PUT /{id}/members/{userId}/role`, `POST /{id}/delivered`, `POST /{id}/read`) | `AccountController.java#L14-L67`, `AuthController.java#L30-L70` | Lấy danh tính từ `@AuthenticationPrincipal AuthenticatedActor`; parse UUID sai -> `404` |
| `backend/src/main/java/com/utechat/backend/controller/MessageController.java` | Created | REST endpoints `GET /api/conversations/{id}/messages?before=&limit=` và `POST /api/conversations/{id}/messages` (`201`/`200`) | `AccountController.java#L14-L67` | Clamp `limit` `1..100` (mặc định `50`) |
| `backend/src/main/java/com/utechat/backend/controller/SyncController.java` | Created | REST endpoints `GET /api/sync/bootstrap` và `GET /api/sync?after=&limit=` | `AccountController.java#L14-L31` | Chặn `after < 0` -> `400 VALIDATION` |
| `backend/src/test/java/com/utechat/backend/config/FlywayMigrationTest.java` | Modified | Bổ sung kiểm chứng bảng `sync_retention_state`, cột `barrier_xid` và trigger trong `V5__sync_and_messaging.sql` | `FlywayMigrationTest.java#L34-L62` | Chạy trên `EmbeddedPostgres` |
| `backend/src/test/java/com/utechat/backend/messaging/MessagingConcurrencyAndPolicyTest.java` | Created | Integration test: `direct_key` (kể cả concurrent 2 thread), `BLOCKED` trước `CONTACT_REQUIRED`, chống trùng `clientId`, rate limit 30/10s, `NOT_A_MEMBER` | `AuthApiTest.java#L29-L83` (`EmbeddedPostgres` + `MockMvc`) | Dùng `ExecutorService + CountDownLatch` kiểm chứng race condition |
| `backend/src/test/java/com/utechat/backend/messaging/GroupChatApiTest.java` | Created | Integration test: tạo nhóm, tối đa 50 người (`GROUP_FULL`), bạn `ACCEPTED`, `NOT_ADMIN`, strict `visible_from_message_id` (`>`), bàn giao `ADMIN` + rời nhóm (3 trường hợp), idempotent `SYSTEM`, watermark đơn điệu & `READ <= DELIVERED` | `AuthApiTest.java#L29-L83` | Kiểm tra trực tiếp bảng `messages` & `sync_events` qua `JdbcTemplate` |
| `backend/src/test/java/com/utechat/backend/sync/SyncAndBootstrapApiTest.java` | Created | Integration test: `bootstrap` `REPEATABLE READ` khi có ghi đồng thời, phân trang `/sync`, `410 SYNC_CURSOR_EXPIRED`, **2 JDBC connection commit `sync_events` ngược thứ tự ID (`102` trước `101`)** | `AuthApiTest.java#L29-L66` | Dùng `DataSource.getConnection()` trực tiếp cho bài test commit ngược thứ tự |
| `backend/src/test/java/com/utechat/backend/ws/WebSocketAuthAndRealtimeTest.java` | Created | Integration test `@SpringBootTest(webEnvironment = RANDOM_PORT)`: Bearer handshake, từ chối JWT query param, `afterCommit` push, `typing` throttle 1/3s, `DEVICE_REVOKED` `4401`, sự kiện cuối khi bị xóa khỏi nhóm | `AuthApiTest.java#L29-L56` + `StandardWebSocketClient` / OkHttp `WebSocket` | Không yêu cầu Docker; dùng `EmbeddedPostgres` thật |

---

### 1.3 Android Client (`app/`)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`git ls-files`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/data/local/entity/ConversationMemberEntity.java` | Modified | Thêm `@Nullable private Long visibleFromMessageId;` (`INTEGER` SQLite) để cô lập lịch sử nhóm khi offline | `ConversationMemberEntity.java#L8-L57` | Bắt buộc tăng `AppDatabase` version + migration (`D-14`) |
| `app/src/main/java/com/utechat/data/local/entity/MessageEntity.java` | Modified | Thêm nullable Long queuedVisibleFromMessageId (INTEGER) lưu mốc tham gia lúc xếp hàng; giữ mốc qua sync/ACK, thêm `@Nullable private String errorCode;` (`TEXT` SQLite) lưu lý do tin `FAILED` bền vững (`BLOCKED`, `CONTACT_REQUIRED`, `NOT_A_MEMBER`, `NETWORK`...) | `MessageEntity.java#L8-L99` | Giữ nguyên `attachmentJson` cho toàn bộ metadata Phase 6 (`D-15`) |
| `app/src/main/java/com/utechat/data/local/AppDatabase.java` | Modified | Tăng `version = 2`, khai báo `MIGRATION_1_2` (`ALTER TABLE member ADD COLUMN visibleFromMessageId INTEGER; ALTER TABLE message ADD COLUMN errorCode TEXT; ALTER TABLE message ADD COLUMN queuedVisibleFromMessageId INTEGER;`) gắn cho mọi build variant | `AppDatabase.java#L13-L41` | Bỏ `fallbackToDestructiveMigration()` khi `version = 2`; sinh schema `2.json` |
| `app/schemas/com.utechat.data.local.AppDatabase/2.json` | Created (Auto) | Schema JSON Room v2 do `room-compiler` sinh ra khi build | `app/schemas/com.utechat.data.local.AppDatabase/1.json` | Commit cùng code để `MigrationTestHelper` kiểm chứng (`app/build.gradle.kts#L28-L32`) |
| `app/src/main/java/com/utechat/data/local/MessageText.java` | Modified | Giữ nguyên `normalize` & `matchQuery`; bổ sung `safeMatchQuery(String)` lọc ký tự đặc biệt `MATCH` và chuỗi rỗng -> `null` | `MessageText.java#L7-L25`, `RoomConvertersTest.java#L30-L35` | Giữ xanh test `RoomConvertersTest#ftsNormalizesBothFormsAndQuotesOperators` |
| `app/src/main/java/com/utechat/data/local/dao/MessageDao.java` | Modified | Truy vấn phân trang/cửa sổ lọc `m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)`, `searchInConversation`, `searchGlobal`, `purgeBeforeVisibleId`, `findByClientId`, `markFailed`, `deleteFailedLocal`, `upgradeReceiptStatus`, `insertFromServer` (chống tin cũ lọt mốc) | `MessageDao.java#L10-L28` | Trigger SQLite tự cập nhật `message_fts` khi `DELETE`/`UPDATE` (`1.json#L419-L423`) |
| `app/src/main/java/com/utechat/data/local/dao/ConversationDao.java` | Modified | Danh sách hội thoại kèm preview hợp lệ/unread, tìm theo tên & tìm phòng `DIRECT` theo `peerUserId`, `getMemberSync`, `markLeft`, cập nhật `visibleFromMessageId` đơn điệu | `ConversationDao.java#L10-L17`, `SyncStateDao.java#L8-L20` | Chỉ nâng `visibleFromMessageId` (`Math.max`), không bao giờ hạ (`D-14`) |
| `app/src/main/java/com/utechat/data/local/dao/SyncStateDao.java` | Modified | Giữ `advance` & `get`; thêm `clear(userId)` hoặc `reset(userId, cursor, at)` khi `bootstrap` lại sau `410` | `SyncStateDao.java#L8-L24` | Chỉ gọi `advance` bên trong transaction `/api/sync` hoặc `/bootstrap` (`D-07`) |
| `app/src/main/java/com/utechat/data/local/dao/UserDao.java` | Modified | Giữ `insert`, `findById`; bổ sung `upsertPreservingPrivacy` nếu chưa merge từ Phase 4 | `UserDao.java#L10-L18` | Không làm mất `phone` hoặc `allowMessagesFromNonContacts` khi nhận DTO rút gọn |
| `app/src/main/java/com/utechat/data/local/dao/ContactDao.java` | Modified | Bổ sung `deleteContact`, `deleteRequest`, `replaceFriendsAndBlocked`, `replacePendingRequests` phục vụ `bootstrap` và `SyncEventApplier` Contact events | `ContactDao.java#L10-L18` | Hỗ trợ xóa cache khi `removed = true` (`D-03`) |
| `app/src/main/java/com/utechat/data/remote/model/ChatDtos.java` | Created | DTO Gson `camelCase` cho Conversation, Member (`visibleFromMessageId`, `leftAt`), Message (đủ trường `mediaUrl`, `mime`, `sizeBytes`, `fileName`, `durationMs`, `width`, `height`, `systemEvent`, `systemParams`, `call`, `latitude`, `longitude`), Bootstrap, Sync, WS frames, `ErrorBody` kèm `retryAfterSeconds` | `AuthDtos.java#L4-L70` | Bảo toàn 100% metadata Phase 6 (`D-15`) |
| `app/src/main/java/com/utechat/data/remote/api/ChatApi.java` | Modified | Khai báo các `@GET`, `@POST`, `@PATCH`, `@PUT`, `@DELETE` Retrofit endpoints cho `/api/conversations/**`, `/api/sync/bootstrap`, `/api/sync` | `AuthApi.java#L9-L30`, `ChatApi.java#L4-L6` | Đường dẫn tương đối `"api/..."` không có `/` đầu |
| `app/src/main/java/com/utechat/data/remote/api/ApiClient.java` | Modified | Tái sử dụng cấu hình `OkHttpClient` (hoặc cấp `baseHttpClient()`) cho `ChatWebSocketManager` với `pingInterval(0)` + timer JSON ping/pong 25s và `readTimeout(0)` | `ApiClient.java#L11-L27` | Release giữ `HttpLoggingInterceptor.Level.NONE` (`DEPLOYMENT_SECURITY.md`) |
| `app/src/main/java/com/utechat/data/session/SessionManager.java` | Modified | Thêm `getUserId()` đọc `sub` từ JWT (nếu chưa merge Phase 4), `sessionKey()` / phiên thế hệ tăng mỗi lần lưu/xóa token để chống race callback/worker | `SessionManager.java#L22-L97` | Không dùng `ArchTaskExecutor` khi phát đổi phiên; dùng `Handler(Looper.getMainLooper())::post` |
| `app/src/main/java/com/utechat/data/remote/api/AuthInterceptor.java` & `SessionEvents.java` | Modified | Bảo đảm `401` chỉ xóa phiên trùng `token` và phát `SessionEvents.notifyExpired()` một lần | `AuthInterceptor.java#L13-L33`, `SessionEvents.java#L7-L18` | Giữ nguyên quy tắc không rò token sang host lạ |
| `app/src/main/java/com/utechat/data/sync/WsConnectionState.java` | Created | Enum trạng thái kết nối WS: `CONNECTED`, `RECONNECTING`, `DISCONNECTED` | `HealthViewModel.java` (`ProbeStatus` enum) | Điều khiển banner trạng thái dưới Toolbar (`D-08`) |
| `app/src/main/java/com/utechat/data/sync/ChatWebSocketManager.java` | Created | OkHttp `WebSocketListener` kết nối `ws(s)://.../ws` kèm header `Authorization: Bearer`, backoff `1..30s`, ping/pong, gửi `typing.start`/`stop`, xử lý `4401`/`auth.revoked` | *No analog (greenfield WebSocket)* → `05-RESEARCH.md#L386-L393` | Tuyệt đối không đặt JWT vào URL query |
| `app/src/main/java/com/utechat/data/sync/SyncEventApplier.java` | Created | Bộ áp dụng idempotent chỉ nhận batch `/sync` theo thứ tự cho 4 Chat events (`MESSAGE_CREATED`, `CONVERSATION_UPDATED`, `RECEIPT_UPDATED`, `DEVICE_REVOKED`) cộng CALL_UPDATED (metadata đã cache) và 3 Contact events (`CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED`) | *No analog (greenfield)* → `05-RESEARCH.md#L401-L408` | WS frame KHÔNG ghi Room hay nâng `lastSyncEventId`; `/sync` nâng cursor trong cùng transaction |
| `app/src/main/java/com/utechat/data/sync/SyncEngine.java` | Created | Single-flight (`AtomicBoolean running`, `resyncRequested`) chạy `bootstrap` + phân trang `/sync` + xử lý `410 SYNC_CURSOR_EXPIRED` + gửi bù `/delivered` | *No analog (greenfield)* → `05-RESEARCH.md#L394-L400` | Dùng `SYNC_NETWORK` executor riêng và `DISK` executor khi ghi Room |
| `app/src/main/java/com/utechat/data/sync/TypingTracker.java` | Created | Quản lý danh sách user đang gõ theo `conversationId` + auto-expire sau 5s bằng `Handler(Looper.getMainLooper())` + throttle gửi `typing.start` 3s | *No analog (greenfield)* → `05-RESEARCH.md#L223-L225` | Dùng `Handler(Looper.getMainLooper())`, không dùng `ArchTaskExecutor` |
| `app/src/main/java/com/utechat/data/worker/SendMessageWorker.java` | Created | `Worker` chạy chuỗi `"send-messages:<sessionKey>"` (`APPEND_OR_REPLACE`, chỉ truyền `clientId`, `NetworkType.CONNECTED`), retry tối đa 3 lần (tôn trọng `Retry-After` với `429`), đánh dấu `FAILED` + `errorCode` và trả `Result.success()` cho `400`/`403`/`404`/hết lượt, `Result.failure()` + `cancelUniqueWork` cho `401` | *No analog (greenfield WorkManager)* → `05-RESEARCH.md#L360-L384` | Tránh fail dây chuyền toàn chuỗi WorkManager (`05-RESEARCH.md#L364-L365`) |
| `app/src/main/java/com/utechat/data/repository/ChatResult.java` | Created | Wrapper `ok(T)` / `error(String code)` / `error(String code, Long retryAfterSeconds)` cho thao tác mạng Chat/Group | `AuthResult.java` (mẫu từ `RealAuthRepository.java#L12,L205`) | Giao tiếp giữa `ChatRepository` và `Chat*ViewModel` |
| `app/src/main/java/com/utechat/data/repository/ChatRepository.java` | Modified | Giữ nguyên 3 method Phase 2 (`getConversations`, `getMessages`, `sendMessage`), mở rộng phương thức cho openOrCreateDirect, nhóm, phân trang lịch sử, receipts, retry/xóa tin `FAILED`, tìm kiếm FTS4, WS/typing state | `ChatRepository.java#L8-L13` | Bảo toàn tương thích `FakeRepositoriesTest` (`D-16`) |
| `app/src/main/java/com/utechat/data/repository/fake/FakeChatRepository.java` | Modified | Giữ nguyên fixture `DemoData` + implement tối giản/in-memory cho các method mới để app Fake compile & chạy mượt | `FakeChatRepository.java#L12-L92` | Không làm gãy `FakeRepositoriesTest#sendingPublishesNewMessageAndRejectsNonmembers` |
| `app/src/main/java/com/utechat/data/repository/real/RealChatRepository.java` | Modified | Triển khai đầy đủ Room + WorkManager + REST + `SyncEngine` + `ChatWebSocketManager`; dùng `Executor mainExecutor = command -> new Handler(Looper.getMainLooper()).post(command)` | `RealAuthRepository.java#L28-L220`, `RealChatRepository.java#L13-L30` | Cấm `ArchTaskExecutor`; giữ nguyên `clientId` khi retry |
| `app/src/main/java/com/utechat/data/repository/real/RealAuthRepository.java` | Modified | Khi `logout()` hoặc `deleteAccount()` thành công, hủy chuỗi WorkManager `"send-messages:<sessionKey>"` và ngắt `ChatWebSocketManager` trước/song song `clearAllTables()` | `RealAuthRepository.java#L66-L81,L144-L146` | Ngăn gửi tin hoặc ghi cache sang tài khoản mới (`D-05`, `05-RESEARCH.md#L383`) |
| `app/src/main/java/com/utechat/di/Injection.java` | Modified | Khởi tạo `SYNC_NETWORK` executor, `SyncEventApplier`, `SyncEngine`, `ChatWebSocketManager`, `RealChatRepository`, `WorkManager`, callback hủy work khi logout | `Injection.java#L13-L61` | Service Locator thuần Java; tách `DISK` (single-thread) và `SYNC_NETWORK` |
| `app/src/main/java/com/utechat/MainActivity.java` | Modified | Kích hoạt `ChatWebSocketManager` & `SyncEngine` khi có phiên hợp lệ, dọn khi logout/`401` | `MainActivity.java#L40-L66` | Giữ nguyên ẩn `bottomNav` với các màn không thuộc 3 tab top-level |
| `app/src/main/res/navigation/nav_chat.xml` | Modified | Khai báo `chatListFragment`, `chatRoomFragment` (kèm `<argument>` `conversationId`, `peerUserId`, `targetMessageLocalId` và `<deepLink app:uri="utechat://chat/direct/{peerUserId}" />`), `newChatFragment`, `groupCreateFragment`, `groupInfoFragment` | `nav_chat.xml#L1-L7`, `nav_profile.xml#L1-L18` | Giữ `startDestination="@id/chatListFragment"` (`D-02`, `05-RESEARCH.md#L425-L434`) |
| `app/src/main/res/navigation/nav_graph.xml` | Modified *(nếu thêm global action)* | Cân nhắc `<action android:id="@+id/action_global_open_direct_chat" ... />` hỗ trợ song song `<deepLink>` | `nav_graph.xml#L1-L14` | Bảo toàn action Phase 2/3 (`action_splash_to_auth`) |
| `app/src/main/java/com/utechat/ui/chat/ChatNavigation.java` | Created | Helper công khai `openDirectChat(NavController, String userId)` cho `NewChatFragment` và Phase 4 `ContactsListFragment` (`D-02`) | `ProfileFragment.java#L70`, `LoginFragment.java#L33-L35` | Dùng URI `utechat://chat/direct/{peerUserId}` vượt ranh giới nested graph |
| `app/src/main/java/com/utechat/ui/chat/ChatErrors.java` | Created | Ánh xạ mã lỗi `BLOCKED`, `CONTACT_REQUIRED`, `NOT_A_MEMBER`, `NOT_ADMIN`, `GROUP_FULL`, `ADMIN_HANDOVER_REQUIRED`, `RATE_LIMITED`, `VALIDATION`, `NETWORK` sang `@StringRes` | `AuthErrors.java#L7-L24` | Tách biệt khỏi `AuthErrors.java` tránh conflict |
| `app/src/main/java/com/utechat/ui/chat/ChatListViewModel.java` | Created | Quản lý danh sách hội thoại, kết quả tìm kiếm kép (hội thoại vs *"Tin nhắn trên thiết bị"*), trạng thái WS | `ProfileViewModel.java#L18-L47`, `AuthViewModel.java#L37-L60` | Dùng `ViewModelProvider.Factory` qua `CreationExtras` + `SavedStateHandle` |
| `app/src/main/java/com/utechat/ui/chat/ChatRoomViewModel.java` | Created | State phòng chat: mở 1-1 theo `peerUserId`, tải thêm trang cũ (`before`), cửa sổ tin nhảy tới `targetMessageLocalId`, tìm kiếm trong phòng prev/next, gửi/gửi lại/xóa tin `FAILED`, `markRead`, banner chặn/chỉ đọc | `AuthViewModel.java#L25-L60` | Không giữ context/View trong ViewModel |
| `app/src/main/java/com/utechat/ui/chat/NewChatViewModel.java`, `GroupCreateViewModel.java`, `GroupInfoViewModel.java` | Created | State cho màn Tạo chat mới (bạn bè + lookup SĐT qua `ContactsRepository`), Tạo nhóm, và Thông tin nhóm (đổi tên, muted, thành viên, bàn giao `ADMIN` + rời nhóm) | `ProfileViewModel.java#L18-L47`, `AuthViewModel.java#L37-L60` | Tái sử dụng `ContactsRepository` cho lookup SĐT & danh sách bạn bè (`D-01`) |
| `app/src/main/java/com/utechat/ui/chat/ChatListFragment.java` | Modified | Danh sách hội thoại, thanh trạng thái WS dưới Toolbar (`D-08`), nút tạo chat, tìm kiếm 2 nhóm (Tên hội thoại vs *"Tin nhắn trên thiết bị"*), bấm nhảy tới phòng + tin khớp | `ChatListFragment.java#L10-L23`, `DevicesFragment.java#L19-L69` | Dùng `getViewLifecycleOwner()`, null `binding` ở `onDestroyView()` |
| `app/src/main/java/com/utechat/ui/chat/ChatRoomFragment.java` | Created | Bong bóng tin nhắn, phân nhóm ngày, chỉ báo `typing`, banner WS (`D-08`), banner chặn (`D-04`), chế độ chỉ đọc `NOT_A_MEMBER` (`D-12`), tìm kiếm trong phòng prev/next, ẩn sạch nút Phase 6 (`D-15`), `markRead` khi `RESUMED` (`D-11`) | `DevicesFragment.java#L19-L69`, `ProfileFragment.java#L22-L138` | `ChatRoomFragment` nhận `conversationId` / `peerUserId` / `targetMessageLocalId` |
| `app/src/main/java/com/utechat/ui/chat/NewChatFragment.java`, `GroupCreateFragment.java`, `GroupInfoFragment.java` | Created | Giao diện chọn bạn/tìm SĐT người lạ (`D-01`), tạo nhóm (`>=2` bạn), quản lý nhóm & dialog chọn người kế nhiệm `ADMIN` (`D-10`) | `DevicesFragment.java#L19-L69`, `ProfileFragment.java#L104-L127` | Dùng `MaterialAlertDialogBuilder` + `Widget.UTEChat.*` |
| `app/src/main/java/com/utechat/ui/chat/ConversationListAdapter.java`, `MessageAdapter.java`, `GlobalSearchAdapter.java`, `MemberSelectAdapter.java`, `GroupMembersAdapter.java` | Created | `ListAdapter + DiffUtil` cho danh sách hội thoại, tin nhắn (tách view types `TEXT_OUT`, `TEXT_IN`, `SYSTEM`, `NON_TEXT_SUMMARY_OUT`, `NON_TEXT_SUMMARY_IN`, `UNKNOWN_FALLBACK`), kết quả tìm kiếm và thành viên nhóm | `DeviceAdapter.java#L18-L62` | Tuyệt đối không dùng `notifyDataSetChanged()` |
| `app/src/main/java/com/utechat/ui/chat/SystemMessageFormatter.java` | Created | Giải mã `systemEvent` + `systemParams` từ `attachmentJson` sang chuỗi EN/VI qua `strings_chat.xml` (`GROUP_CREATED`, `GROUP_RENAMED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `MEMBER_LEFT`, `ROLE_CHANGED`) | *No analog (greenfield)* → `05-RESEARCH.md#L309,L437` | An toàn khi thiếu param hoặc `systemEvent` lạ |
| `app/src/main/res/layout/fragment_chat_list.xml`, `fragment_chat_room.xml`, `fragment_new_chat.xml`, `fragment_group_create.xml`, `fragment_group_info.xml`, `item_*.xml`, `view_ws_status_banner.xml` | Modified / Created | Layout XML Material 3 DayNight, ViewBinding, dùng `@color/ute_*`, `@style/Widget.UTEChat.*` | `fragment_devices.xml#L1-L33`, `item_device.xml#L1-L35`, `fragment_profile.xml#L1-L114` | Không hiển thị nút Media/Vị trí/Gọi thoại trong `fragment_chat_room.xml` (`D-15`) |
| `app/src/main/res/menu/menu_chat_list.xml`, `menu_chat_room.xml` | Created | Menu tìm kiếm / tạo chat / tìm trong phòng / thông tin nhóm trên `MaterialToolbar` | `menu_save.xml#L1-L5` | Icon tint `@color/ute_on_bar` |
| `app/src/main/res/values/strings_chat.xml` & `values-vi/strings_chat.xml` | Modified | Toàn bộ chuỗi EN/VI: trạng thái WS, lỗi `BLOCKED`/`CONTACT_REQUIRED`/`NOT_A_MEMBER`, *"Tin nhắn trên thiết bị"*, nhãn tóm tắt non-TEXT (`IMAGE`/`AUDIO`/`VIDEO`/`FILE`/`LOCATION`/`CALL`), chuỗi `SYSTEM`, thao tác `FAILED`/nhóm | `strings_chat.xml#L1-L6`, `strings_profile.xml#L1-L47` | Tuân thủ `ADR-025`: chỉ sửa `strings_chat.xml` |
| `app/src/test/java/com/utechat/data/repository/FakeRepositoriesTest.java` | Verified / Modified | Giữ xanh 100% các test Phase 2; có thể bổ sung test cho method mới trên `FakeChatRepository` | `FakeRepositoriesTest.java#L76-L94` | Không phá vỡ `sendingPublishesNewMessageAndRejectsNonmembers` |
| `app/src/test/java/com/utechat/data/local/RoomConvertersTest.java` | Modified | Giữ nguyên `ftsNormalizesBothFormsAndQuotesOperators`; bổ sung test cho `MessageText.safeMatchQuery` | `RoomConvertersTest.java#L30-L35` | Pure JVM JUnit 4 |
| `app/src/test/java/com/utechat/data/worker/SendMessageWorkerTest.java` | Created | JVM unit test phân loại lỗi `IOException`/`408`/`5xx`/`429` (`Retry-After`, max 3 lượt) vs `400`/`403`/`404` (`Result.success()` + `FAILED`) vs `401` (`Result.failure()`) | `RealAuthRepositoryTest.java#L25-L156` | JUnit 4 + Mockito |
| `app/src/test/java/com/utechat/data/sync/SyncEngineAndApplierTest.java` | Created | JVM unit test single-flight sync, `410` re-bootstrap, idempotent 8 event (Chat + Contact + CALL_UPDATED metadata), WS chỉ requestSync, barrier retry không busy-loop, không hạ `visibleFromMessageId`, không nâng cursor khi nhận WS | `RealAuthRepositoryTest.java#L25-L156` | JUnit 4 + Mockito + `Runnable::run` |
| `app/src/test/java/com/utechat/ui/chat/ChatViewModelTest.java` | Created | JVM unit test ViewModel, state xoay màn hình (`SavedStateHandle`), bảo toàn metadata non-TEXT | `RealAuthRepositoryTest.java#L25-L44`, `FakeRepositoriesTest.java#L10-L12` | `InstantTaskExecutorRule` |
| `app/src/androidTest/java/com/utechat/data/local/AppDatabaseMigrationTest.java` | Created | Test migration Room `1 -> 2` bằng `MigrationTestHelper`, giữ nguyên dữ liệu & FTS triggers | *No analog (greenfield MigrationTestHelper)* → `05-RESEARCH.md#L339,L471` | Dùng `room-testing:2.6.1` đã khai báo ở `app/build.gradle.kts#L80` |
| `app/src/androidTest/java/com/utechat/data/local/MessagingRoomAndFtsTest.java` | Created | Test SQLite/FTS4 thật trên Android: tiếng Việt có/không dấu, ký tự `MATCH` đặc biệt, purge `visibleFromMessageId` (`>`), bảo vệ `serverId IS NULL` | `RoomFoundationTest.java#L14-L68` | Chạy `Room.inMemoryDatabaseBuilder` |
| `app/src/androidTest/java/com/utechat/ui/chat/ChatNavigationAndSearchUiTest.java` | Created | Instrumentation Espresso test tìm kiếm toàn cục nhảy tới tin ngoài trang, render thẻ tóm tắt non-TEXT an toàn | `FoundationNavigationTest.java#L18-L48` | Xác nhận điều hướng + UI không crash |

---

## 2. Backend Spring Boot 3 Patterns (`com.utechat.backend`)

### 2.1 REST Controller Pattern (`ConversationController`, `MessageController`, `SyncController`)
- **Tệp mẫu đối chiếu:**
  - `backend/src/main/java/com/utechat/backend/controller/AccountController.java#L13-L67`
  - `backend/src/main/java/com/utechat/backend/controller/AuthController.java#L10-L70`
- **Trích dẫn mẫu thực tế (`AccountController.java#L14-L67`):**
  ```java
  @RestController
  @RequestMapping("/api")
  public class AccountController {
      private final AccountService account;

      public AccountController(AccountService account) {
          this.account = account;
      }

      @GetMapping("/me")
      public UserDto me(@AuthenticationPrincipal AuthenticatedActor actor) {
          return account.getProfile(actor.userId());
      }

      @DeleteMapping("/account/devices/{id}")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void revokeDevice(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id) {
          UUID target;
          try {
              target = UUID.fromString(id);
          } catch (IllegalArgumentException e) {
              throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Device not found");
          }
          account.revokeDevice(actor, target);
      }
  }
  ```
- **Quy chuẩn áp dụng cho `ConversationController`, `MessageController`, `SyncController`:**
  1. Constructor Injection thuần Java 17 (không dùng `@Autowired` field injection).
  2. Luôn lấy danh tính từ `@AuthenticationPrincipal AuthenticatedActor actor` (`actor.userId()`, `actor.deviceIdHash()`), **tuyệt đối không** đọc `userId` của người gọi từ body/query.
  3. Mọi tham số UUID path (`{id}`, `{userId}`) hoặc query (`successorUserId`) parse qua helper `parseUuid(String raw)` ném `ApiException(HttpStatus.NOT_FOUND, ...)` hoặc `400 VALIDATION` (với query/body).
  4. Dùng `@RequestParam(value = "...", required = false)` để tránh Spring ném lỗi trước khi vào logic kiểm tra; với `DELETE /api/conversations/{id}/members/{userId}`, dùng `@RequestParam(value = "successorUserId", required = false) String successorUserId` để tương thích Retrofit `@DELETE` (`05-RESEARCH.md#L483`).

### 2.2 Transactional Service, `JdbcTemplate` & Row Locking Pattern (`ConversationService`, `MessageService`, `ReceiptService`)
- **Tệp mẫu đối chiếu:**
  - `backend/src/main/java/com/utechat/backend/service/AccountService.java#L67-L136`
  - `backend/src/main/java/com/utechat/backend/service/AuthService.java#L65-L80,L164-L175`
- **Trích dẫn mẫu thực tế (`AccountService.java#L85-L93` & `AuthService.java#L74-L78,L166-L173`):**
  ```java
  // AccountService.java#L85-L93
  @Transactional
  public void revokeDevice(AuthenticatedActor actor, UUID targetId) {
      int n = jdbc.update("UPDATE user_devices SET revoked_at = now(), token_valid_after = ? "
              + "WHERE id = ? AND user_id = ? AND device_id_hash <> ? AND revoked_at IS NULL",
              now(), targetId, actor.userId(), actor.deviceIdHash());
      if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Device not found");
      jdbc.update("INSERT INTO sync_events (user_id, event_type, entity_type, entity_id, payload_json) "
              + "VALUES (?, 'DEVICE_REVOKED', 'DEVICE', ?, '{}'::jsonb)", actor.userId(), targetId.toString());
  }

  // AuthService.java#L167-L173 (upsert chống đua song song với ON CONFLICT)
  jdbc.update("INSERT INTO user_devices (user_id, device_id_hash, device_name, trusted, token_valid_after) "
          + "VALUES (?, ?, ?, TRUE, ?) "
          + "ON CONFLICT (user_id, device_id_hash) DO UPDATE SET device_name = EXCLUDED.device_name, "
          + "last_seen_at = now(), revoked_at = NULL, "
          + "token_valid_after = GREATEST(user_devices.token_valid_after, EXCLUDED.token_valid_after)", ...);
  ```
- **Mẫu áp dụng cho Phase 5 (có comment Tiếng Việt theo `AGENTS.md`):**
  ```java
  // Khóa hàng hội thoại trước khi đọc/cập nhật thành viên để tránh race condition vượt quá 50 người hoặc mất ADMIN cuối.
  List<Map<String, Object>> convRows = jdbc.queryForList(
          "SELECT id, type, title, last_message_id FROM conversations WHERE id = ? FOR UPDATE", conversationId);
  if (convRows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");

  // Chống trùng clientId khi tạo tin nhắn (D-05): INSERT ON CONFLICT trả bản ghi cũ nếu cùng conversation_id.
  List<MessageRow> inserted = jdbc.query(
          "INSERT INTO messages (conversation_id, sender_id, client_id, type, text) "
                  + "VALUES (?, ?, ?, 'TEXT', ?) ON CONFLICT (sender_id, client_id) DO NOTHING RETURNING id, created_at",
          mapper, conversationId, actor.userId(), clientId, text);
  ```
- **Lưu ý tích hợp `AccountService` & `AuthService` (`05-RESEARCH.md#L216-L220,L327`):**
  - `AccountService.revokeDevice` sửa sang `RETURNING device_id_hash` và ghi event qua `SyncEventWriter` để `afterCommit()` gửi frame `auth.revoked` rồi đóng đúng `(userId, revokedDeviceIdHash)` với `CloseStatus(4401, "DEVICE_REVOKED")`.
  - `AccountService.purge(UUID userId)` (`AccountService.java#L128-L136`) phải kiểm tra nếu `userId` là `ADMIN` duy nhất trong nhóm còn thành viên active thì tự động nâng `ADMIN` cho thành viên vào sớm nhất (`ORDER BY joined_at ASC, user_id ASC LIMIT 1`) kèm tin `SYSTEM` `ROLE_CHANGED` trước khi đặt `left_at = now()`, và đóng toàn bộ WS của `userId` với `4401`.
  - `AccountService.purgeExpiredDeletions()` (`AccountService.java#L116-L122`) cần xử lý từng `userId` trong transaction ngắn (dùng `TransactionTemplate` thay vì giữ 1 `@Transactional` lớn trên cả vòng lặp) để không giữ `pg_snapshot_xmin` làm nghẽn `/api/sync`.

### 2.3 Security, JWT Filter & Exception Handling
- **Tệp mẫu đối chiếu:**
  - `backend/src/main/java/com/utechat/backend/config/SecurityConfig.java#L18-L36`
  - `backend/src/main/java/com/utechat/backend/security/JwtAuthenticationFilter.java#L31-L67`
  - `backend/src/main/java/com/utechat/backend/security/JwtService.java#L39-L64`
  - `backend/src/main/java/com/utechat/backend/exception/GlobalExceptionHandler.java#L16-L43`
- **Trích dẫn mẫu thực tế (`JwtAuthenticationFilter.java#L54-L66`):**
  ```java
  List<Boolean> valid = jdbc.query(
          "SELECT d.token_valid_after, d.revoked_at, u.status FROM user_devices d JOIN users u ON u.id = d.user_id "
                  + "WHERE d.user_id = ? AND d.device_id_hash = ?",
          (rs, i) -> {
              boolean notRevoked = rs.getTimestamp("revoked_at") == null;
              boolean active = "ACTIVE".equals(rs.getString("status"));
              Number issuedMs = claims.get("iatMs", Number.class);
              boolean fresh = issuedMs != null
                      && issuedMs.longValue() >= rs.getTimestamp("token_valid_after").toInstant().toEpochMilli();
              return notRevoked && active && fresh;
          }, userId, deviceHash);
  ```
- **Áp dụng cho Phase 5:**
  - Mở rộng `JwtAuthenticationFilter.authenticate` SELECT thêm `d.id AS device_row_id` và gắn `deviceRowId`, `issuedAtMs`, `tokenExpiresAt` (`claims.getExpiration().toInstant()`) vào `HttpServletRequest` attributes (ví dụ `REQ_ATTR_WS_META`) để `WsAuthHandshakeInterceptor` chép sang `WebSocketSession.getAttributes()`, giữ nguyên `AuthenticatedActor(UUID userId, String deviceIdHash)` tương thích 100% với controller Phase 3/4.
  - `GlobalExceptionHandler.java` bổ sung `@ExceptionHandler(MissingServletRequestParameterException.class)` (nếu chưa có từ Phase 4) trả `400 VALIDATION` giống `HttpMessageNotReadableException` (`GlobalExceptionHandler.java#L25-L28`).

### 2.4 Backend DTOs (`MessagingDtos.java`)
- **Tệp mẫu đối chiếu:** `backend/src/main/java/com/utechat/backend/dto/AuthDtos.java#L6-L38`
- **Trích dẫn mẫu thực tế (`AuthDtos.java#L12-L31`):**
  ```java
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OtpChallengeResponse(String challengeId, int expiresInSeconds, String demoOtp) { }
  public record UserDto(String id, String phone, String displayName, String avatarUrl,
                        boolean discoverable, boolean allowMessagesFromNonContacts, String deletionScheduledAt) { }
  ```
- **Áp dụng:** Gom nhóm các DTO của Phase 5 trong `MessagingDtos.java` dùng Java `record` bất biến, tên trường `camelCase`, không lồng entity/password/hash và chỉ trả `phone` của người dùng khác khi đã là bạn bè `ACCEPTED`.

### 2.5 Rate Limiter (`MessageRateLimiter.java`)
- **Tệp mẫu đối chiếu:** `backend/src/main/java/com/utechat/backend/service/ThrottleService.java#L34-L38` (cách trả lỗi `ApiException` kèm `retryAfterSeconds`).
- **Quy chuẩn:** Đếm 30 tin / 10 giây / `userId` với `Clock` inject được; vượt ngưỡng ném `new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many messages", Map.of("retryAfterSeconds", retry))`.

---

## 3. Android Data, Room v2 & Repository Patterns

### 3.1 Room Schema v1 -> v2, Migration & DAOs
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/data/local/AppDatabase.java#L13-L41`
  - `app/src/main/java/com/utechat/data/local/entity/ConversationMemberEntity.java#L8-L57`
  - `app/src/main/java/com/utechat/data/local/entity/MessageEntity.java#L8-L99`
  - `app/src/main/java/com/utechat/data/local/dao/MessageDao.java#L11-L27`
  - `app/src/main/java/com/utechat/data/local/dao/SyncStateDao.java#L8-L24`
  - `app/src/main/java/com/utechat/data/local/MessageText.java#L7-L25`
- **Trích dẫn mẫu thực tế:**
  ```java
  // AppDatabase.java#L13-L15 & L32-L36 (hiện tại là version = 1; Phase 5 nâng lên 2 và gắn MIGRATION_1_2 cho cả Debug/Release)
  @Database(entities = {UserEntity.class, ContactRequestEntity.class, ContactEntity.class, ConversationEntity.class, ConversationMemberEntity.class, MessageEntity.class, AttachmentTransferEntity.class, MessageFtsEntity.class, MessageTranslationEntity.class, SyncStateEntity.class},
          version = 2, exportSchema = true)
  ```
  ```java
  // MessageDao.java#L13-L27 (mẫu giữ localId khi server ACK cùng clientId và JOIN message_fts)
  @Transaction
  default void insert(MessageEntity message) {
      // Giữ localId cũ khi server xác nhận cùng clientId, không tạo dòng tin thứ hai.
      Long existing = findLocalId(message.getClientId());
      if (existing != null) message.setLocalId(existing);
      message.setNormalizedText(MessageText.normalize(message.getText()));
      upsert(message);
  }
  @Query("SELECT m.* FROM message m JOIN message_fts ON m.localId = message_fts.rowid WHERE message_fts MATCH :query AND m.conversationId = :conversationId ORDER BY m.createdAt DESC")
  List<MessageEntity> search(String conversationId, String query);
  ```
- **Mẫu Migration `1 -> 2` bắt buộc cho `AppDatabase.java` (`D-05`, `D-14`, `05-RESEARCH.md#L334-L339`):**
  ```java
  // Định nghĩa migration tường minh từ schema v1 lên v2 để không mất dữ liệu cục bộ khi nâng cấp.
  public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
      @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
          // Mốc serverId cô lập lịch sử nhóm khi được thêm lại; null nghĩa là xem từ đầu.
          db.execSQL("ALTER TABLE member ADD COLUMN visibleFromMessageId INTEGER");
          // Mã lỗi bền vững cho tin nhắn FAILED (BLOCKED, CONTACT_REQUIRED, NOT_A_MEMBER...).
          db.execSQL("ALTER TABLE message ADD COLUMN errorCode TEXT");
          // Giữ mốc tham gia lúc soạn tin để không tự gửi tin của lần tham gia nhóm cũ.
          db.execSQL("ALTER TABLE message ADD COLUMN queuedVisibleFromMessageId INTEGER");
      }
  };
  ```
  *(Lưu ý: Không bật `fallbackToDestructiveMigration()` làm bypass migration; luôn gắn `.addMigrations(MIGRATION_1_2)` cho cả Debug và Release).*
- **Mẫu bảo vệ `visibleFromMessageId` & `serverId IS NULL` trong `MessageDao` / `ConversationDao` (`D-14`):**
  ```java
  // Chỉ hiển thị tin đang chờ/lỗi cục bộ (serverId IS NULL) hoặc tin có serverId lớn hơn hẳn mốc tham gia lại (>).
  @Query("SELECT m.* FROM message m "
          + "LEFT JOIN member mb ON mb.conversationId = m.conversationId AND mb.userId = :myUserId "
          + "WHERE m.conversationId = :conversationId "
          + "AND (m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)) "
          + "ORDER BY COALESCE(m.serverId, 9223372036854775807) ASC, m.createdAt ASC, m.localId ASC")
  LiveData<List<MessageEntity>> getVisibleMessages(String conversationId, String myUserId);

  // Dọn tin cũ ngoài phạm vi khi mốc visibleFromMessageId tăng; trigger BEFORE DELETE tự dọn message_fts.
  @Query("DELETE FROM message WHERE conversationId = :conversationId AND serverId IS NOT NULL AND serverId <= :visibleFrom")
  void purgeBeforeVisibleId(String conversationId, long visibleFrom);
  ```

### 3.2 Retrofit API, OkHttp, `SessionManager` & Repository Seam
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/data/remote/api/AuthApi.java#L9-L30`
  - `app/src/main/java/com/utechat/data/remote/model/AuthDtos.java#L4-L70`
  - `app/src/main/java/com/utechat/data/remote/api/ApiClient.java#L11-L27`
  - `app/src/main/java/com/utechat/data/remote/api/AuthInterceptor.java#L13-L33`
  - `app/src/main/java/com/utechat/data/repository/real/RealAuthRepository.java#L28-L221`
  - `app/src/main/java/com/utechat/data/repository/fake/FakeChatRepository.java#L12-L92`
  - `app/src/main/java/com/utechat/di/Injection.java#L13-L61`
- **Trích dẫn mẫu thực tế (`RealAuthRepository.java#L66-L81,L198,L212-L220`):**
  ```java
  // RealAuthRepository.java#L66-L81: dọn phiên cục bộ ngay lập tức, không chờ callback mạng.
  @Override public void logout() {
      String token = session.getToken();
      clearLocal();
      if (token == null) return;
      api.logout("Bearer " + token).enqueue(new Callback<Void>() { ... });
  }

  // RealAuthRepository.java#L212-L220: trích xuất error.code từ JSON lỗi chuẩn của server.
  private static String errorCode(Response<?> r) {
      try {
          if (r.errorBody() != null) {
              ErrorBody body = GSON.fromJson(r.errorBody().string(), ErrorBody.class);
              if (body != null && body.error != null && body.error.code != null) return body.error.code;
          }
      } catch (Exception ignored) { }
      return "HTTP_" + r.code();
  }
  ```
- **Áp dụng cho Phase 5:**
  - `ChatApi.java` theo đúng `AuthApi.java`: đường dẫn tương đối `"api/conversations/..."`, `"api/sync/bootstrap"`, `"api/sync"`.
  - `ChatDtos.java` theo đúng `AuthDtos.java`: `public static final class` chứa public fields cho Gson, tách khỏi Room `@Entity`.
  - `RealChatRepository.java` inject `mainExecutor` mặc định `command -> new Handler(Looper.getMainLooper()).post(command)` (thay thế hoàn toàn `ArchTaskExecutor`) để phát kết quả về UI thread an toàn và test JVM inject được `Runnable::run` như `RealAuthRepositoryTest.java#L42`.
  - `Injection.java` giữ `DISK = Executors.newSingleThreadExecutor()` cho mọi ghi/xóa Room (`clearAllTables()` và `SyncEngine`/`SyncEventApplier`), bổ sung `SYNC_NETWORK` executor riêng để HTTP `/sync` không chặn đọc/ghi Room.
  - `FakeChatRepository.java` giữ nguyên constructor & hành vi `LiveData` list copy (`FakeChatRepository.java#L77-L89`) để `FakeRepositoriesTest` xanh 100%, chỉ thêm các method mới của `ChatRepository`.

---

## 4. Android UI, Navigation & Resource Patterns

### 4.1 Navigation Sub-graph & Cross-Feature Contract (`D-02`)
- **Tệp mẫu đối chiếu:**
  - `app/src/main/res/navigation/nav_chat.xml#L1-L7`
  - `app/src/main/res/navigation/nav_profile.xml#L3-L18`
  - `app/src/main/res/navigation/nav_graph.xml#L3-L14`
  - `app/src/main/java/com/utechat/MainActivity.java#L56-L66`
- **Trích dẫn mẫu thực tế (`nav_profile.xml#L5-L17` & `MainActivity.java#L56-L60`):**
  ```xml
  <!-- nav_profile.xml#L5-L12 -->
  <fragment android:id="@+id/profileFragment" android:name="com.utechat.ui.profile.ProfileFragment">
      <action android:id="@+id/action_profile_to_devices" app:destination="@id/devicesFragment" />
  </fragment>
  <fragment android:id="@+id/devicesFragment" android:name="com.utechat.ui.profile.DevicesFragment" />
  ```
  ```java
  // MainActivity.java#L59-L60: bottom_nav tự ẩn ở các màn chi tiết không phải 3 tab gốc.
  boolean topLevel = id == R.id.chatListFragment || id == R.id.contactsListFragment || id == R.id.profileFragment;
  binding.bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);
  ```
- **Áp dụng cho `nav_chat.xml` và `ChatNavigation.java` (`D-02`):**
  ```xml
  <!-- app/src/main/res/navigation/nav_chat.xml -->
  <fragment android:id="@+id/chatRoomFragment" android:name="com.utechat.ui.chat.ChatRoomFragment">
      <!-- Cho phép điều hướng trực tiếp từ nav_contacts sang phòng chat 1-1 theo userId (D-02). -->
      <argument android:name="conversationId" app:argType="string" app:nullable="true" android:defaultValue="@null" />
      <argument android:name="peerUserId" app:argType="string" app:nullable="true" android:defaultValue="@null" />
      <argument android:name="targetMessageLocalId" app:argType="long" android:defaultValue="-1L" />
      <deepLink app:uri="utechat://chat/direct/{peerUserId}" />
      <action android:id="@+id/action_chatRoom_to_groupInfo" app:destination="@id/groupInfoFragment" />
  </fragment>
  ```
  - Giữ nguyên `app:startDestination="@id/chatListFragment"` và ID `@id/chatListFragment` để `BottomNavigationView` và `MainActivity` không đổi logic ẩn/hiện tab.

### 4.2 ViewModel, Fragment, RecyclerView ListAdapter & XML DayNight
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/ui/profile/ProfileViewModel.java#L18-L25` & `app/src/main/java/com/utechat/ui/auth/AuthViewModel.java#L37-L43` (`ViewModelProvider.Factory` + `SavedStateHandle`)
  - `app/src/main/java/com/utechat/ui/profile/DevicesFragment.java#L19-L69` (`Fragment` + `ViewBinding` + `MaterialAlertDialogBuilder` + `Snackbar`)
  - `app/src/main/java/com/utechat/ui/profile/DeviceAdapter.java#L18-L62` (`ListAdapter` + `DiffUtil`)
  - `app/src/main/java/com/utechat/ui/auth/AuthErrors.java#L7-L24` (`@StringRes` mapper)
  - `app/src/main/res/layout/fragment_devices.xml#L3-L33` & `item_device.xml#L3-L35`
  - `app/src/main/res/values/themes.xml#L30-L41`, `styles_auth.xml#L5-L71`, `values/strings_chat.xml`, `values-vi/strings_chat.xml`
- **Trích dẫn mẫu thực tế (`AuthViewModel.java#L38-L42` & `DeviceAdapter.java#L24-L37`):**
  ```java
  // AuthViewModel.java#L38-L42: chuẩn khởi tạo ViewModel có SavedStateHandle và Application từ CreationExtras.
  public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
          new ViewModelInitializer<>(AuthViewModel.class, extras -> {
              Application app = extras.get(APPLICATION_KEY);
              return new AuthViewModel(createSavedStateHandle(extras), Injection.provideAuthRepository(app));
          }));
  ```
  ```java
  // DeviceAdapter.java#L24-L33: chuẩn ListAdapter + DiffUtil, tuyệt đối không dùng notifyDataSetChanged().
  public DeviceAdapter(OnRevokeListener listener) {
      super(new DiffUtil.ItemCallback<DeviceInfo>() {
          @Override public boolean areItemsTheSame(@NonNull DeviceInfo a, @NonNull DeviceInfo b) { return a.id.equals(b.id); }
          @Override public boolean areContentsTheSame(@NonNull DeviceInfo a, @NonNull DeviceInfo b) { ... }
      });
      this.listener = listener;
  }
  ```
- **Áp dụng cho UI Phase 5:**
  - Mọi ViewModel dùng `ViewModelInitializer` với `APPLICATION_KEY` và `createSavedStateHandle(extras)`.
  - `MessageAdapter` override `getItemViewType(int position)` tách rõ: `TEXT_OUT`, `TEXT_IN`, `SYSTEM`, `NON_TEXT_SUMMARY_OUT`, `NON_TEXT_SUMMARY_IN`, `UNKNOWN_FALLBACK` (`D-15`), hiển thị tên/nội dung thẻ tóm tắt qua `strings_chat.xml` (EN/VI).
  - Bố cục XML dùng `?attr/colorSurface`, `?attr/colorOnSurface`, `?attr/colorOnSurfaceVariant`, `@color/ute_bar`, `@color/ute_on_bar`, `@color/ute_card`, `@color/ute_accent`, `@style/Widget.UTEChat.Toolbar`, `@style/Widget.UTEChat.Card` để tương thích 100% Material 3 DayNight.
  - Chuỗi UI chỉ khai báo trong `app/src/main/res/values/strings_chat.xml` và `app/src/main/res/values-vi/strings_chat.xml` (`ADR-025`).

---

## 5. Testing Patterns (Backend + Android JVM + Instrumentation)

### 5.1 Backend Integration Tests (`EmbeddedPostgres` + `MockMvc`)
- **Tệp mẫu đối chiếu:**
  - `backend/src/test/java/com/utechat/backend/auth/AuthApiTest.java#L29-L83,L220-L237`
  - `backend/src/test/java/com/utechat/backend/config/FlywayMigrationTest.java#L34-L62`
- **Trích dẫn mẫu thực tế (`AuthApiTest.java#L29-L51`):**
  ```java
  @SpringBootTest
  @AutoConfigureMockMvc
  class AuthApiTest {
      private static final EmbeddedPostgres PG;
      static {
          try {
              PG = EmbeddedPostgres.builder().start();
          } catch (Exception e) {
              throw new ExceptionInInitializerError(e);
          }
      }
      @DynamicPropertySource
      static void properties(DynamicPropertyRegistry r) {
          r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
          r.add("spring.datasource.username", () -> "postgres");
          r.add("spring.datasource.password", () -> "");
          ...
      }
  ```
- **Áp dụng:** Bắt buộc dùng `EmbeddedPostgres` (`io.zonky.test:embedded-postgres:2.0.7`) chạy Flyway V1→V3→V4→V5 thật trên Windows/CI mà **không yêu cầu Docker**. Với test WebSocket (`WebSocketAuthAndRealtimeTest`), thêm `webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT` + `@LocalServerPort`.

### 5.2 Android JVM Unit Tests
- **Tệp mẫu đối chiếu:**
  - `app/src/test/java/com/utechat/data/repository/FakeRepositoriesTest.java#L10-L95`
  - `app/src/test/java/com/utechat/data/local/RoomConvertersTest.java#L10-L36`
  - `app/src/test/java/com/utechat/data/repository/RealAuthRepositoryTest.java#L25-L156`
- **Trích dẫn mẫu thực tế (`RealAuthRepositoryTest.java#L26,L35-L43`):**
  ```java
  @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();
  @Before public void setUp() {
      session = mock(SessionManager.class);
      ...
      repo = new RealAuthRepository(api, session, dao, Runnable::run, () -> cacheCleared++);
  }
  ```
- **Áp dụng:** Inject `Runnable::run` vào cả `diskExecutor` và `mainExecutor` (thay cho `Looper.getMainLooper()`) để kiểm thử đồng bộ trên JVM với `InstantTaskExecutorRule` và `Mockito`.

### 5.3 Android Instrumentation Tests (SQLite/FTS4/Migration/Espresso)
- **Tệp mẫu đối chiếu:**
  - `app/src/androidTest/java/com/utechat/data/local/RoomFoundationTest.java#L13-L68`
  - `app/src/androidTest/java/com/utechat/FoundationNavigationTest.java#L17-L48`
- **Trích dẫn mẫu thực tế (`RoomFoundationTest.java#L16-L32`):**
  ```java
  @Before public void open() {
      Context context = ApplicationProvider.getApplicationContext();
      db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
  }
  @Test public void ftsFindsVietnameseAndAckKeepsLocalIdentity() {
      MessageEntity pending = message("client-1", null, "Chào bạn Đặng");
      db.messageDao().insert(pending);
      assertEquals(1, db.messageDao().search("conversation-1", MessageText.matchQuery("chao ban")).size());
  }
  ```

---

## 6. Shared Patterns & Anti-Patterns (Quy chuẩn & Bẫy cần tránh)

1. **CẤM `ArchTaskExecutor` trên Android (`lintDebug` `RestrictedApi`):**
   - **Quy chuẩn bắt buộc:** Mọi điều phối về main thread trong `app/src/main/**` (Repository, SessionManager, TypingTracker, WebSocketManager) **bắt buộc** dùng `Handler(Looper.getMainLooper())::post` (qua `Executor mainExecutor` inject được để JVM test truyền `Runnable::run`).
   - **Anti-pattern:** Tuyệt đối **không** dùng `androidx.arch.core.executor.ArchTaskExecutor.getInstance()` (API `@RestrictTo`, làm gãy `./gradlew lintDebug`).
2. **Backend Tests chạy bằng `EmbeddedPostgres`, KHÔNG yêu cầu Docker:**
   - **Quy chuẩn bắt buộc:** Toàn bộ integration test Backend chạy với `io.zonky.test:embedded-postgres:2.0.7` (`AuthApiTest.java#L32-L42`). Các hàm SQL trong `SyncEventWriter` phải tương thích với H2 (trigger `barrier_xid` đặt trong PostgreSQL migration `V5`, không nhét SQL đặc thù PostgreSQL vào `SyncEventWriter` nếu có H2 unit test).
   - **Anti-pattern:** Không dùng Testcontainers/Docker hoặc phụ thuộc database Supabase bên ngoài trong `mvn test`.
3. **100% Java 17 & Comment Tiếng Việt Sư phạm (`AGENTS.md` / `GEMINI.md`):**
   - **Quy chuẩn bắt buộc:** Toàn bộ code Android & Backend viết bằng Java 17 (tuyệt đối không Kotlin/Coroutines/Compose/Hilt); mọi file Java/XML/SQL tạo mới hoặc chỉnh sửa phải có comment Tiếng Việt ngắn gọn, giải thích bản chất phục vụ bảo vệ đồ án.
4. **Bảo mật WebSocket & Phiên (`DEPLOYMENT_SECURITY.md`):**
   - **Quy chuẩn bắt buộc:** Android kết nối `/ws` từ `BuildConfig.BACKEND_BASE_URL` (`https -> wss`, `http -> ws`) qua header `Authorization: Bearer <token>`. Server kiểm `WsAuthHandshakeInterceptor` từ chối query params nhạy cảm (`token`, `jwt`, `access_token`) với `400`/`401` và không log query string.
   - **Anti-pattern:** Không đặt JWT vào query URL, subprotocol hoặc Room; không bỏ kiểm hostname hay dùng `TrustAll`; không dùng `HttpLoggingInterceptor.Level.BODY` hoặc bật log token ở Release.
5. **Bẫy WorkManager `APPEND_OR_REPLACE` & Retry `clientId` (`D-05`):**
   - **Quy chuẩn bắt buộc:** `Data` chỉ truyền `clientId`. Khi gặp lỗi nghiệp vụ `400`/`403`/`404` hoặc hết 3 lần retry (`IOException`/`408`/`5xx`/`429`), Worker ghi `status = "FAILED"` + `errorCode` vào Room và trả **`Result.success()`** để các tin gửi sang hội thoại khác phía sau không bị fail dây chuyền. 401 chỉ hủy queueName(enqueuedSessionKey); chỉ clear/notifyExpired khi token+version còn khớp. Không cancel node theo client tag khi xóa FAILED vì có thể hủy tin phụ thuộc phía sau. Khi user bấm "Gửi lại", giữ nguyên `clientId` cũ.
   - **Anti-pattern:** Không trả `Result.failure()` cho lỗi của một tin nhắn đơn lẻ; không đặt nội dung tin/JWT vào WorkManager `Data`; không retry vô hạn khi gặp `NOT_A_MEMBER`.
6. **Bẫy Con trỏ Đồng bộ WS vs `/api/sync` (`D-03`, `D-07`):**
   - **Quy chuẩn bắt buộc:** Chỉ kết quả từ `GET /api/sync` (và `/api/sync/bootstrap`) mới được phép gọi `SyncStateDao.advance(...)` trong cùng transaction lưu dữ liệu. Phải áp dụng đủ cả 3 sự kiện Contact (`CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED`). Không suy đoán thứ tự sự kiện bằng `eventId == last + 1`.
   - **Anti-pattern:** Tuyệt đối **không** nâng `sync_state.lastSyncEventId` từ WebSocket frame nhận realtime; không bỏ qua Contact events mà vẫn nâng cursor.
   - **Bắt kịp sau barrier:** retryAfterMs=1000 nếu còn sự kiện đã commit bị giữ; client hẹn đúng một lần thử không chặn, gộp theo single-flight và dừng khi đổi phiên/ngắt kết nối. Chỉ báo CONNECTED khi hasMore=false và retryAfterMs=null.
7. **Cô lập lịch sử `visible_from_message_id` & Tin `serverId IS NULL` (`D-14`):**
   - **Quy chuẩn bắt buộc:** Luôn dùng bất đẳng thức chặt **`serverId > visibleFromMessageId`** (không dùng `>=`). Mốc `visibleFromMessageId` chỉ được tăng (`Math.max`). Khi purge tin `serverId <= visibleFromMessageId` kèm trigger `message_fts`, điều kiện bắt buộc phải có `AND serverId IS NOT NULL` để không xóa nhầm tin đang chờ gửi (`SENDING`) hoặc tin lỗi (`FAILED`).
   - **Gửi nhóm an toàn:** lưu message.queuedVisibleFromMessageId tại enqueue; server so expectedVisibleFromMessageId dưới khóa conversations FOR UPDATE dùng chung với send/remove/re-add/SYSTEM. Sai mốc trả 409 MEMBERSHIP_CHANGED, tin thiếu mốc không tự gửi; chỉ Gửi lại chủ động được lấy mốc mới.
   - **Đọc/phân trang:** READ dùng mốc tin thật sự thấy trên RecyclerView khi RESUMED; tải lịch sử phải tăng windowLimit, giữ anchor cuộn và dừng ở endReached.
   - **Anti-pattern:** Không tự động xếp lại tin `FAILED` từ lần tham gia nhóm cũ vào WorkManager khi thành viên vừa được thêm lại nhóm.
8. **Giữ tương thích ngược Seam & Tests hiện có (`D-16`):**
   - **Quy chuẩn bắt buộc:** Giữ nguyên 3 method cũ của `ChatRepository` + fixture `FakeChatRepository`, giữ nguyên `MessageText.normalize` + `MessageText.matchQuery` để `FakeRepositoriesTest` và `RoomConvertersTest` tiếp tục xanh 100%.

---

## No Analog Found (Các thành phần Greenfield — tham chiếu `05-RESEARCH.md`)

Các tệp dưới đây không có tệp mẫu tương đồng trực tiếp trong codebase hiện tại (`git ls-files`); Planner và Executor lấy chi tiết thiết kế trực tiếp từ [05-RESEARCH.md](05-RESEARCH.md):

| Tệp mục tiêu | Lý do chưa có mẫu trên đĩa | Tham chiếu thiết kế chi tiết (`05-RESEARCH.md`) |
|---|---|---|
| `backend/src/main/resources/db/migration/V5__sync_and_messaging.sql` | Lần đầu dùng `xid8` trigger (`barrier_xid`) và bảng `sync_retention_state` chống nhảy cóc transaction PostgreSQL | [05-RESEARCH.md §3.1–3.2](05-RESEARCH.md#L234-L285) |
| `backend/src/main/java/com/utechat/backend/config/WebSocketConfig.java` | Lần đầu cấu hình Spring WebSocket thuần JSON (`/ws`, không STOMP) | [05-RESEARCH.md §2.1](05-RESEARCH.md#L193-L200) |
| `backend/src/main/java/com/utechat/backend/websocket/WsAuthHandshakeInterceptor.java` | Lần đầu kiểm tra handshake Upgrade `/ws` & chặn JWT trên URL query param | [05-RESEARCH.md §2.1](05-RESEARCH.md#L195-L200) |
| `backend/src/main/java/com/utechat/backend/websocket/WebSocketSessionRegistry.java` | Lần đầu quản lý `ConcurrentWebSocketSessionDecorator`, map `(userId, deviceIdHash)`, đóng `4401` & reaper 30s | [05-RESEARCH.md §2.2](05-RESEARCH.md#L202-L220) |
| `backend/src/main/java/com/utechat/backend/websocket/SyncEventRealtimeDispatcher.java` | Lần đầu gắn `TransactionSynchronization.afterCommit()` để đẩy WS đồng bộ sau commit DB | [05-RESEARCH.md §2.3](05-RESEARCH.md#L226-L228) |
| `backend/src/main/java/com/utechat/backend/websocket/ChatWebSocketHandler.java` | Lần đầu xử lý `TextWebSocketHandler`, app-level `ping`/`pong`, `typing.start` throttle 1/3s | [05-RESEARCH.md §2.2–2.3](05-RESEARCH.md#L208-L225) |
| `backend/src/main/java/com/utechat/backend/service/SyncService.java` & `SyncRetentionService.java` | Lần đầu cài đặt `REPEATABLE READ READ ONLY` bootstrap, `/api/sync` `barrier_xid <= xmin`, `410 SYNC_CURSOR_EXPIRED`, dọn 30 ngày | [05-RESEARCH.md §3.1–3.2](05-RESEARCH.md#L234-L300) |
| `backend/src/main/java/com/utechat/backend/service/MessageRateLimiter.java` | Lần đầu cài đặt sliding window rate limiter cho tin nhắn (`30 / 10s / userId`) trên đĩa | [05-RESEARCH.md §4](05-RESEARCH.md#L139) |
| `app/src/main/java/com/utechat/data/worker/SendMessageWorker.java` | Codebase chưa có bất kỳ `Worker` WorkManager nào; đặc biệt có mẫu bẫy `Result.success()` cho chuỗi `APPEND_OR_REPLACE` | [05-RESEARCH.md §5.3](05-RESEARCH.md#L360-L384) |
| `app/src/main/java/com/utechat/data/sync/ChatWebSocketManager.java` | Codebase chưa có OkHttp `WebSocketListener`, backoff `1..30s`, xử lý `4401`/`auth.revoked` | [05-RESEARCH.md §5.4](05-RESEARCH.md#L386-L393) |
| `app/src/main/java/com/utechat/data/sync/SyncEngine.java` | Lần đầu cài đặt single-flight bootstrap + phân trang `/sync` + hồi phục `410 SYNC_CURSOR_EXPIRED` | [05-RESEARCH.md §5.4](05-RESEARCH.md#L394-L400) |
| `app/src/main/java/com/utechat/data/sync/SyncEventApplier.java` | Lần đầu cài đặt bộ xử lý sự kiện idempotent cho batch `/sync` (8 loại event Chat + Contact + CALL_UPDATED metadata); WS chỉ báo thay đổi | [05-RESEARCH.md §5.4](05-RESEARCH.md#L401-L408) |
| `app/src/main/java/com/utechat/data/sync/TypingTracker.java` | Lần đầu cài đặt trạng thái đang nhập + auto-expire 5s theo `conversationId` | [05-RESEARCH.md §2.3](05-RESEARCH.md#L223-L225) |
| `app/src/main/java/com/utechat/ui/chat/SystemMessageFormatter.java` | Lần đầu map `systemEvent` + `systemParams` thành chuỗi đa ngôn ngữ EN/VI | [05-RESEARCH.md §3.3, §5.6](05-RESEARCH.md#L309,L437) |
| `app/src/androidTest/java/com/utechat/data/local/AppDatabaseMigrationTest.java` | Chưa có test `MigrationTestHelper` cho Room `1 -> 2` vì `AppDatabase` hiện vẫn ở `version = 1` | [05-RESEARCH.md §5.1](05-RESEARCH.md#L333-L339) |
