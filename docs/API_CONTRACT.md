# UTEChat — Hợp đồng API (REST và WebSocket)

> **Trạng thái:** ĐÃ CHỐT 2026-11-09, cập nhật chuẩn hóa V1 2026-11-10. Đây là **hợp đồng chung** giữa app và server: người làm app và người làm server cùng dựa vào đây để làm song song. Thay đổi hợp đồng phải qua **người giữ hợp đồng chung** (ADR-022). Tiền tố mọi đường dẫn REST: `/api` (đúng mẫu `api/auth/login`, `api/conversations/{id}/messages` trong slide Session 5). Các đường dẫn bên dưới viết **không kèm** tiền tố này.

## 1. Quy ước chung

**Kênh truyền V1:** Release/hosted bắt buộc HTTPS cho REST/media và WSS cho WebSocket; HTTP chỉ cho Debug nội bộ. Tuân thủ [DEPLOYMENT_SECURITY.md](DEPLOYMENT_SECURITY.md) về URL cấu hình, TLS, proxy, token và điều kiện phát hành.

| Hạng mục | Quy ước |
|---|---|
| Dữ liệu | JSON UTF-8, tên trường `camelCase`, thời gian ISO 8601 UTC (ví dụ `2026-11-09T14:32:05Z`) |
| Xác thực | Header `Authorization: Bearer <token>`, một JWT duy nhất, **không có refresh token** (ADR-018). `401` thì app xoá token và về đăng nhập. Các endpoint `/auth/**` (trừ `logout`) không cần token |
| Danh tính | Server lấy `userId` và `deviceId` từ token, **app không gửi** |
| Chống trùng | Gửi tin dùng `clientId` (UUID). Gửi lại cùng `clientId` trả cùng kết quả |
| Phân trang & Đồng bộ | Phân trang lịch sử tin nhắn: `?before=<messageId>&limit=50`. Đồng bộ bắt kịp dữ liệu: `GET /sync?after=<syncEventId>`. `limit` tối đa 100 |
| Định danh | `uuid` cho người dùng, hội thoại, thiết bị, file, cuộc gọi, lời mời kết bạn. `messageId` và `syncEventId` là số nguyên `bigint` |
| Số điện thoại | Luôn gửi dạng E.164 (`+84912345678`) |
| Ngôn ngữ lỗi | Mã lỗi cố định (`code`), app tự dịch `code` thành chữ theo ngôn ngữ. `message` của server chỉ để gỡ lỗi |

**Định dạng lỗi:**

```json
{ "error": { "code": "OTP_EXPIRED", "message": "OTP expired", "details": { "retryAfterSeconds": 60 } } }
```

**Mã HTTP:** `400` dữ liệu sai, `401` chưa xác thực hoặc token sai, `403` không có quyền (cũng dùng khi tài nguyên riêng tư không tồn tại hoặc vi phạm chính sách quyền riêng tư), `404` không thấy, `409` xung đột (trùng), `413` tệp quá lớn, `415` loại tệp không cho phép, `423` bị khoá tạm, `429` quá giới hạn tần suất, `5xx` lỗi server.

## 2. REST

### 2.0 Hệ thống (Health & Readiness)

| Method và đường dẫn | Body chính | Trả về | Mô tả |
|---|---|---|---|
| `GET /health` | — | `{"status": "UP"}` | Liveness probe kiểm tra server đang hoạt động (gọi qua `/api/health`) |
| `GET /health/ready` | — | `{"status": "UP", "database": "CONNECTED"}` | Readiness probe kiểm tra kết nối Supabase CSDL (gọi qua `/api/health/ready`) |

### 2.1 Xác thực

| Method và đường dẫn | Body chính | Trả về | Lỗi đặc trưng |
|---|---|---|---|
| `POST /auth/register/otp` | `phone`, `deviceId` | `challengeId`, `expiresInSeconds` | `PHONE_TAKEN` 409, `OTP_RATE_LIMIT` 429 |
| `POST /auth/register/verify` | `challengeId`, `code` | `registrationToken` | `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPTS` |
| `POST /auth/register/complete` | `registrationToken`, `displayName`, `password`, `deviceName` | `token`, `user` (201) | `WEAK_PASSWORD`, `VALIDATION` |
| `POST /auth/login/password` | `phone`, `password`, `deviceId`, `deviceName` | `state` (`COMPLETE`), `token`, `user` | `BAD_CREDENTIALS` 401, `LOCKED` 423 |
| `POST /auth/login/otp/request` | `phone`, `deviceId` | `challengeId` (luôn 200) | `OTP_RATE_LIMIT` |
| `POST /auth/login/otp/verify` | `challengeId`, `code`, `deviceName` | `state` (`COMPLETE`), `token`, `user` | `OTP_INVALID`, `OTP_EXPIRED`, `LOCKED` 423 |
| `POST /auth/password-reset/otp` | `phone`, `deviceId` | `challengeId` (luôn 200) | `OTP_RATE_LIMIT` |
| `POST /auth/password-reset/verify` | `challengeId`, `code` | `resetToken` | `OTP_INVALID`, `OTP_EXPIRED` |
| `POST /auth/password-reset/complete` | `resetToken`, `newPassword`, `deviceName` | `state` (`COMPLETE`), `token` | `WEAK_PASSWORD` |
| `POST /auth/logout` | — | 204 | — |

> **Ghi chú xác thực V1:** Đăng nhập mật khẩu và OTP trong V1 chỉ trả về `state: COMPLETE` kèm JWT token hoặc trả mã lỗi (`BAD_CREDENTIALS`, `LOCKED`, v.v.). Khi đặt lại mật khẩu thành công qua OTP, server tự động thu hồi token của toàn bộ các thiết bị khác (`token_valid_after = now`).

### 2.2 Tài khoản và Quyền riêng tư

| Method và đường dẫn | Body chính | Trả về | Mô tả |
|---|---|---|---|
| `GET /me` | — | `user` | Lấy thông tin cá nhân gồm: `id`, `phone`, `displayName`, `avatarUrl`, `discoverable`, `allowMessagesFromNonContacts` |
| `PATCH /me` | `displayName`, `avatarMediaUrl`, `discoverable`, `allowMessagesFromNonContacts` | `user` | Cập nhật hồ sơ và cài đặt quyền riêng tư nhắn tin người lạ |
| `POST /me/password` | `oldPassword`, `newPassword` | 204 | Đổi mật khẩu (thu hồi token của mọi thiết bị khác) |
| `DELETE /me` | `password` | 204 | Xoá mềm tài khoản (giải phóng SĐT, xoá toàn bộ dòng liên quan trong `contacts`, thu hồi mọi thiết bị) |
| `GET /account/devices` | — | `[{id, name, platform, lastSeenAt, current}]` | Xem danh sách thiết bị đang đăng nhập |
| `DELETE /account/devices/{id}` | — | 204 | Thu hồi thiết bị (hữu ích khi mất máy) |

### 2.3 Danh bạ, lời mời kết bạn và danh sách chặn

| Method và đường dẫn | Body chính | Trả về | Mô tả |
|---|---|---|---|
| `GET /users/lookup?phone=` | — | `{id, displayName, avatarUrl, isContact, hasPendingRequest}` | Tìm kiếm người dùng chính xác theo SĐT E.164 (10 lượt/phút) |
| `POST /contact-requests` | `addresseeId` | `request` (201) | Gửi lời mời kết bạn (chuyển sang `PENDING`). Nếu đã có lời mời chờ ở chiều ngược lại, trả 409 `REQUEST_ALREADY_PENDING` hoặc trả lời mời hiện có |
| `GET /contact-requests` | — (hỗ trợ `?status=PENDING&type=incoming` hoặc `outgoing`) | `[{id, requester, addressee, status, createdAt}]` | Lấy danh sách lời mời kết bạn |
| `POST /contact-requests/{id}/accept` | — | `request` (200) | Chấp nhận lời mời: chuyển sang `ACCEPTED`, tạo đồng thời 2 dòng `contacts` (`blocked = false`) trong cùng 1 transaction, sinh `sync_events` cho cả 2 bên |
| `POST /contact-requests/{id}/reject` | — | `request` (200) | Từ chối lời mời: chuyển sang `REJECTED`, sinh `sync_events` và thông báo cho cả 2 bên |
| `DELETE /contact-requests/{id}` | — | 204 | Hủy lời mời đã gửi: chuyển sang `CANCELED`, sinh `sync_events` cho cả 2 bên |
| `GET /contacts` | — | `[{id, phone, displayName, avatarUrl, blocked: false, createdAt}]` | Lấy danh sách bạn bè chính thức (`blocked = false`) |
| `GET /contacts/blocked` | — | `[{id, phone, displayName, avatarUrl, blocked: true, createdAt}]` | Lấy danh sách người dùng đang bị chặn để hiển thị và bỏ chặn |
| `DELETE /contacts/{userId}` | — | 204 | Xóa bạn bè (unfriend): xóa quan hệ hai chiều trong cùng transaction |
| `POST /contacts/{userId}/block` | — | 204 | Chặn người dùng. Nếu là bạn bè: đặt `blocked = true`. Nếu là người lạ: tạo một dòng block-only `(owner_id, contact_user_id, blocked = true)` |
| `DELETE /contacts/{userId}/block` | — | 204 | Bỏ chặn người dùng. Nếu là dòng block-only người lạ: xóa dòng. Nếu vốn là bạn bè: đặt `blocked = false` |

### 2.4 Hội thoại và tin nhắn

| Method và đường dẫn | Body chính | Trả về | Lỗi kiểm tra chính sách |
|---|---|---|---|
| `GET /conversations` | — | danh sách hội thoại (kèm `lastMessage`, `unreadCount`, `members` rút gọn) | — |
| `POST /conversations/direct` | `userId` | hội thoại 1-1 (tạo nếu chưa có, theo `direct_key`) | `403 CONTACT_REQUIRED` (nếu đối phương tắt nhận tin người lạ và chưa kết bạn), `403 BLOCKED` |
| `POST /conversations/group` | `title`, `memberIds[]` | hội thoại nhóm (201) | `403 CONTACT_REQUIRED` (chỉ được thêm người đã kết bạn `ACCEPTED`) |
| `GET /conversations/{id}` | — | chi tiết kèm thành viên và vai trò | `403 FORBIDDEN` |
| `PATCH /conversations/{id}` | `title`, `muted` | hội thoại (đổi tên cần `ADMIN`) | `403 NOT_ADMIN` |
| `POST /conversations/{id}/members` | `userIds[]` | 204 (`ADMIN`) | `403 CONTACT_REQUIRED` (chỉ thêm người đã kết bạn `ACCEPTED`), `403 NOT_ADMIN` |
| `DELETE /conversations/{id}/members/{userId}` | — | 204 (`ADMIN`, hoặc chính mình = rời nhóm) | `403 FORBIDDEN` |
| `PUT /conversations/{id}/members/{userId}/role` | `role` | 204 (`ADMIN`) | `403 NOT_ADMIN` |
| `GET /conversations/{id}/messages?before=&limit=` | — | tin cũ hơn `before` (`messageId`), mới nhất trước (phân trang lịch sử) | — |
| `POST /conversations/{id}/messages` | `clientId`, `type`, `text`, `mediaUrl`, `latitude`, `longitude` | `messageId`, `createdAt` (201) | `403 CONTACT_REQUIRED` (nếu hội thoại 1-1 mà đối phương tắt nhận tin người lạ và chưa kết bạn), `403 BLOCKED` |
| `POST /conversations/{id}/delivered` | `upToMessageId` | 204 | — |
| `POST /conversations/{id}/read` | `upToMessageId` | 204 | — |
| `GET /sync?after=<syncEventId>` | — | `{events: [{id, eventType, entityType, entityId, payload, createdAt}], nextCursor}` | — |

> **Chính sách nhận tin nhắn người lạ (`allowMessagesFromNonContacts`):**
> - Mặc định là `true` (cho phép nhận tin từ người lạ tìm thấy qua SĐT).
> - Khi người dùng đặt thành `false`: chỉ những người dùng đã có quan hệ bạn bè được chấp thuận (`contacts` với `blocked = false`) mới được gửi tin nhắn trực tiếp 1-1.
> - Kiểm tra `BLOCKED` được ưu tiên trước: nếu người nhận đã chặn người gửi thì trả `403 BLOCKED`, bất kể cài đặt nhận tin người lạ.
> - Nếu một cuộc hội thoại 1-1 đã được mở từ trước, nhưng sau đó người nhận tắt quyền này và hai người chưa kết bạn: mọi yêu cầu gửi tin nhắn mới `POST /conversations/{id}/messages` sẽ bị server từ chối với mã HTTP 403 `CONTACT_REQUIRED`.
> - Gửi lời mời kết bạn qua `POST /contact-requests` vẫn luôn được phép kể cả khi tắt nhận tin từ người lạ.
>
> **Quy ước gửi tin từ Client:**
> Client chỉ gửi các trường: `clientId`, `type`, `text`, `mediaUrl`, `latitude`, `longitude`. Với tin có tệp, `mediaUrl` phải là URL `/api/media/{attachmentId}` do server trả về từ `POST /media`; client không được tự tạo URL. Server lấy `attachmentId` từ URL, kiểm tra attachment thuộc người gửi, chưa gắn vào message khác và `kind` phù hợp với `type`, sau đó tự lấy metadata (`durationMs`, `sizeBytes`, `width`, `height`, `mime`) từ bản ghi `attachments`.
>
> **Định nghĩa con trỏ đồng bộ (`GET /sync?after=<syncEventId>`):**
> - `syncEventId` tăng đơn điệu toàn cục.
> - `nextCursor` là `syncEventId` lớn nhất trong đợt trả về. Nếu không có sự kiện mới hơn, `nextCursor` giữ nguyên giá trị `after`.
> - Client lưu `lastSyncEventId` trong Room và chỉ tăng khi đã xử lý thành công sự kiện tương ứng. Sự kiện xử lý theo cơ chế idempotent.
> - `messages.id` **chỉ được dùng cho phân trang lịch sử tin nhắn** (`?before=<messageId>`).
>
> **Định nghĩa biên nhận (Receipts):**
> - `DELIVERED`: Ít nhất một thiết bị của người nhận đã lưu thành công tin nhắn vào dữ liệu cục bộ.
> - `READ`: Người nhận đã đọc tin nhắn trên ít nhất một thiết bị.

**Tin nhắn trả về:**

```json
{
  "id": 1042, "clientId": "…uuid…", "conversationId": "…uuid…", "senderId": "…uuid…",
  "type": "IMAGE", "text": null,
  "mediaUrl": "/api/media/…uuid…", "mime": "image/jpeg", "sizeBytes": 483201, "fileName": null,
  "durationMs": null, "width": 1200, "height": 800,
  "systemEvent": null, "systemParams": null, "call": null,
  "latitude": null, "longitude": null, "createdAt": "2026-11-09T14:32:05Z"
}
```

### 2.5 Đa phương tiện

| Method và đường dẫn | Body chính | Trả về | Lỗi |
|---|---|---|---|
| `POST /media` | multipart: `kind`, `file` | `mediaUrl`, `mime`, `sizeBytes`, `width`, `height`, `durationMs` (201) | `FILE_TOO_LARGE` 413, `UNSUPPORTED_TYPE` 415 |
| `GET /media/{id}` | — (URL có bảo vệ: Header `Authorization` Bearer token) | luồng byte, hỗ trợ `Range` (phát video, tải lại) | 403 nếu không phải thành viên |

> **Phát media qua app ngoài:** App dùng `FileProvider` làm phương án duy nhất. App tải/cache file về bộ nhớ tạm bằng token rồi chia sẻ `content://` qua `FileProvider` với cờ `FLAG_GRANT_READ_URI_PERMISSION`. Không dùng query ticket và tuyệt đối không đưa JWT vào URL.

### 2.6 Cuộc gọi thoại WebRTC 1-1

| Method và đường dẫn | Body chính | Trả về | Điều kiện kiểm tra |
|---|---|---|---|
| `POST /calls` | `conversationId`, `media` (`VOICE`) | `callId`, `state` (`RINGING`, `MISSED`, `BUSY`), `iceServers[]` | Bắt buộc hai bên đã là bạn bè `ACCEPTED` và không bị `blocked`. Trả `403 CONTACT_REQUIRED` nếu chưa kết bạn, `403 BLOCKED` nếu bị chặn |
| `POST /calls/{id}/accept` | — | `iceServers[]` | — |
| `POST /calls/{id}/reject` | — | 204 | — |
| `POST /calls/{id}/end` | — | 204 | — |

`iceServers` gồm STUN và TURN coturn với credential có thời hạn (HMAC). V1 chỉ hỗ trợ cuộc gọi thoại (`VOICE`), không hỗ trợ video call. Lỗi: `CALL_UNSUPPORTED` 400 (nhóm), `CONTACT_REQUIRED` 403 (chưa kết bạn), `BLOCKED` 403, `CALL_BUSY` 409.

### 2.7 Môi trường demo

| Method và đường dẫn | Ghi chú |
|---|---|
| `GET /demo/sms?phone=` | Trả các tin OTP gần nhất từ `demo_sms_outbox`. Cần header khoá demo riêng. **Không tồn tại** ở profile `prod` |

## 3. WebSocket

**Kết nối:** `wss://<host>/ws`, gửi header `Authorization: Bearer <token>` trong HTTP Upgrade handshake bằng OkHttp. Không đặt JWT trong query URL. Server kiểm token/thiết bị và quyền trên từng thao tác; đóng socket khi token hết hạn hoặc bị thu hồi theo ADR-018. Ping/pong mỗi 25 giây. Một tài khoản có thể có nhiều kết nối đồng thời từ các thiết bị khác nhau.

**Khung chung cho sự kiện làm thay đổi dữ liệu:**

```json
{
  "v": 1,
  "type": "message.new",
  "id": "…uuid…",
  "syncEventId": 1042,
  "payload": { }
}
```

Trường `syncEventId` trỏ trực tiếp tới `sync_events.id`. Client cập nhật `lastSyncEventId` trong Room khi đã xử lý xong sự kiện. Realtime events và events lấy từ `/sync` xử lý theo cơ chế idempotent.

### Server gửi xuống app

| `type` | Mang `syncEventId`? | `payload` | Khi nào |
|---|:---:|---|---|
| `message.new` | Có | message (như mục 2.4) | Có tin mới trong hội thoại của người dùng |
| `message.status` | Có | `conversationId`, `userId`, `deliveredUpTo`, `readUpTo` | Một thành viên nâng mốc biên nhận |
| `typing` | Không | `conversationId`, `userId`, `state` (`start`, `stop`) | Người khác trong hội thoại đang nhập văn bản |
| `conversation.updated` | Có | hội thoại rút gọn | Đổi tên nhóm, thêm/xoá thành viên, đổi vai trò |
| `contact_request.new` | Có | thông tin lời mời kết bạn | Có người gửi lời mời kết bạn mới |
| `contact_request.updated` | Có | thông tin lời mời và trạng thái mới | Lời mời kết bạn được chấp nhận/từ chối/hủy |
| `call.incoming` | Có | `callId`, `conversationId`, `caller`, `media` (`VOICE`) | Có người gọi đến |
| `call.accepted`, `call.ended` | Có | `callId`, `state`, `endReason` | Thay đổi trạng thái cuộc gọi; được ghi vào `sync_events` với `eventType = CALL_UPDATED` |
| `call.offer`, `call.answer`, `call.ice` | Không | `callId`, `sdp` hoặc `candidate` | Báo hiệu WebRTC từ phía bên kia |
| `call.handled_elsewhere` | Có | `callId` | Thiết bị khác của người nhận đã nhận/từ chối cuộc gọi; được ghi vào `sync_events` với `eventType = CALL_UPDATED` |
| `auth.revoked` | Có | — | Thiết bị bị thu hồi, sau đó server đóng kết nối |

### App gửi lên server

| `type` | `payload` | Ghi chú |
|---|---|---|
| `typing.start`, `typing.stop` | `conversationId` | Giới hạn 1 lần mỗi 3 giây cho `start` |
| `call.offer`, `call.answer`, `call.ice` | `callId`, `sdp` hoặc `candidate` | Báo hiệu WebRTC cuộc gọi thoại |
| `ping` | — | Giữ kết nối |

## 4. Giới hạn tần suất và chống Brute-force

Bảng `auth_throttles` kiểm soát theo `(scope_type, scope_key_hash, purpose)`:
- `scope_type`:
  - `PHONE_DEVICE`: Khóa theo cặp (SĐT, thiết bị) với `scope_key_hash = SHA-256(phone_e164 + ":" + device_id_hash)`. Ví dụ: 5 lần đăng nhập sai khóa 15 phút cho riêng thiết bị đó. Thiết bị mới có `deviceId` mới nên không bị kế thừa khóa cũ.
  - `PHONE`: Khóa toàn tài khoản theo SĐT khi tổng số lần sai vượt ngưỡng lớn (20 lần/giờ). Bảo vệ tài khoản khi mất thiết bị.
  - `DEVICE`: Khóa theo thiết bị.
- `purpose`: `PASSWORD_LOGIN`, `OTP_VERIFY`, `PASSWORD_RESET`.

| Thao tác | Giới hạn |
|---|---|
| Gửi OTP | 1 lần mỗi 60 giây và 5 lần mỗi giờ cho mỗi số điện thoại |
| Nhập OTP | Tối đa 5 lần sai mỗi mã OTP |
| Đăng nhập mật khẩu sai | 5 lần sai thì khoá 15 phút cho cặp `(phone, device)` (`PHONE_DEVICE`), và 20 lần mỗi giờ cho một số thì khoá số đó 1 giờ (`PHONE`) |
| Tra số điện thoại | 10 lượt mỗi phút mỗi người dùng |
| Gửi tin nhắn | 30 tin mỗi 10 giây mỗi người dùng |
| Tải tệp lên (Upload) | 20 tệp mỗi phút mỗi người dùng |
| Tạo cuộc gọi thoại | 5 cuộc gọi mỗi phút mỗi người dùng |

## 5. Danh mục mã lỗi chính

`VALIDATION`, `PHONE_TAKEN`, `PHONE_INVALID`, `BAD_CREDENTIALS`, `LOCKED`, `WEAK_PASSWORD`, `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPTS`, `OTP_RATE_LIMIT`, `SESSION_EXPIRED`, `NOT_FOUND`, `FORBIDDEN`, `NOT_A_MEMBER`, `NOT_ADMIN`, `GROUP_FULL`, `BLOCKED`, `CONTACT_REQUIRED`, `REQUEST_ALREADY_PENDING`, `FILE_TOO_LARGE`, `UNSUPPORTED_TYPE`, `CALL_BUSY`, `CALL_NOT_FOUND`, `CALL_UNSUPPORTED`, `SELF`, `RATE_LIMITED`, `INTERNAL`.

## 6. Chính sách thông báo (Notification)

- V1 **không dùng Firebase Cloud Messaging (FCM)**. FCM chỉ ghi nhận như hướng mở rộng tương lai cho V2.
- `NotificationChannel` (`messages`) được sử dụng để phát thông báo khi ứng dụng đang hoạt động (foregound/background) hoặc kết nối WebSocket còn duy trì.
- Khi ứng dụng ở nền và WebSocket bị ngắt, `WorkManager` định kỳ (15 phút) gọi `GET /api/sync?after=<syncEventId>` để kiểm tra tin mới theo cơ chế **best-effort** (không cam kết nhận tức thời).
- Khi ứng dụng bị người dùng hoặc hệ thống **force-stop hoàn toàn**, hệ điều hành Android đình chỉ toàn bộ tiến trình nền, do đó không cam kết nhận được thông báo ngay lập tức cho đến khi ứng dụng được mở lại.

## 7. Tính tương thích

- Tiền tố `/api` cố định cho V1. Thêm trường mới vào JSON là tương thích ngược.
- App dùng Gson và DTO tách biệt với Room Entity.
