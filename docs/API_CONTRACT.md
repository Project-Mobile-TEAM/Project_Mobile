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

> **Bổ sung đã cài đặt (Phase 3):** (1) Các endpoint gửi OTP trả thêm `demoOtp` (6 số) **chỉ khi** server ở chế độ demo (profile `dev`), kèm việc ghi mã vào file `otp-local/<số>.txt`; ở production trường này vắng mặt. (2) `POST /me/password` và `DELETE /me` khi mật khẩu sai trả **HTTP 400** với `code = BAD_CREDENTIALS` (không phải 401, để app không nhầm với hết phiên và tự đăng xuất). (3) `registrationToken`/`resetToken` dùng lại hoặc hết hạn trả 400 `OTP_EXPIRED`. (4) Xác minh OTP đăng nhập chưa áp khoá `LOCKED`; giới hạn bằng 5 lần sai mỗi mã và 5 mã mỗi giờ mỗi số.
>
> **Ghi chú xác thực V1:** Đăng nhập mật khẩu và OTP trong V1 chỉ trả về `state: COMPLETE` kèm JWT token hoặc trả mã lỗi (`BAD_CREDENTIALS`, `LOCKED`, v.v.). Khi đặt lại mật khẩu thành công qua OTP, server tự động thu hồi token của toàn bộ các thiết bị khác (`token_valid_after = now`).

### 2.2 Tài khoản và Quyền riêng tư

| Method và đường dẫn | Body chính | Trả về | Mô tả |
|---|---|---|---|
| `GET /me` | — | `user` | Lấy thông tin cá nhân gồm: `id`, `phone`, `displayName`, `avatarUrl`, `discoverable`, `allowMessagesFromNonContacts`, `deletionScheduledAt` (null, hoặc thời điểm ISO-8601 bị xoá vĩnh viễn) |
| `PATCH /me` | `displayName`, `avatarMediaUrl`, `discoverable`, `allowMessagesFromNonContacts` | `user` | Cập nhật hồ sơ và cài đặt quyền riêng tư nhắn tin người lạ |
| `POST /me/password` | `oldPassword`, `newPassword` | 204 | Đổi mật khẩu (thu hồi token của mọi thiết bị khác) |
| `DELETE /me` | `password` | 204 | Yêu cầu xoá tài khoản: thu hồi mọi thiết bị ngay, sau **15 ngày** không huỷ thì xoá mềm vĩnh viễn (giải phóng SĐT, xoá toàn bộ dòng liên quan trong `contacts`) |
| `POST /me/cancel-deletion` | — | 204 | Huỷ yêu cầu xoá khi đăng nhập lại trong 15 ngày chờ |
| `GET /account/devices` | — | `[{id, name, platform, lastSeenAt, current}]` | Xem danh sách thiết bị đang đăng nhập |
| `DELETE /account/devices/{id}` | — | 204 | Thu hồi thiết bị (hữu ích khi mất máy) |

### 2.3 Danh bạ, lời mời kết bạn và danh sách chặn

| Method và đường dẫn | Body chính | Trả về | Mô tả |
|---|---|---|---|
| `GET /users/lookup?phone=` | — | `{id, displayName, avatarUrl, isContact, hasPendingRequest, relationshipStatus, pendingRequestId, pendingRequestDirection}` | Tìm kiếm người dùng chính xác theo SĐT E.164 (10 lượt/phút) |
| `POST /contact-requests` | `addresseeId` | `request` (201) | Gửi lời mời kết bạn (chuyển sang `PENDING`). Lời mời đang chờ cùng/ngược chiều trả 409 `REQUEST_ALREADY_PENDING`, không tự chấp nhận lời mời ngược chiều |
| `GET /contact-requests` | — (hỗ trợ `?status=PENDING&type=incoming` hoặc `outgoing`) | `[{id, requester, addressee, status, createdAt, respondedAt}]` | Lấy danh sách lời mời kết bạn |
| `POST /contact-requests/{id}/accept` | — | `request` (200) | Chấp nhận lời mời: chuyển sang `ACCEPTED`, tạo đồng thời 2 dòng `contacts` (`is_friend = true, blocked = false`) trong cùng 1 transaction, sinh `sync_events` cho cả 2 bên |
| `POST /contact-requests/{id}/reject` | — | `request` (200) | Từ chối lời mời: chuyển sang `REJECTED`, sinh `sync_events` và thông báo cho cả 2 bên |
| `DELETE /contact-requests/{id}` | — | 204 | Hủy lời mời đã gửi: chuyển sang `CANCELED`, sinh `sync_events` cho cả 2 bên |
| `GET /contacts` | — | `[{id, phone, displayName, avatarUrl, blocked: false, createdAt}]` | Lấy danh sách bạn bè chính thức (`is_friend = true AND blocked = false` ở chiều người xem) |
| `GET /contacts/blocked` | — | `[{id, phone, displayName, avatarUrl, blocked: true, createdAt}]` | Lấy danh sách người dùng đang bị chặn để hiển thị và bỏ chặn |
| `DELETE /contacts/{userId}` | — | 204 | Hủy quan hệ bạn bè hai chiều trong cùng transaction: đặt `is_friend = false`, giữ dòng chặn và xóa dòng không chặn |
| `POST /contacts/{userId}/block` | — | 204 | Chỉ đặt `blocked = true` ở chiều của mình, giữ nguyên `is_friend`; nếu chưa có dòng thì tạo block-only `is_friend = false` |
| `DELETE /contacts/{userId}/block` | — | 204 | Dựa trên dòng của mình: `is_friend = true` thì chỉ đặt `blocked = false`; `is_friend = false` thì xóa dòng. Không suy ra tình bạn từ chiều ngược |

**DTO và lỗi Phase 4:** `request` có `{id, requester, addressee, status, createdAt, respondedAt}`; requester/addressee chỉ có `{id, displayName, avatarUrl}`, không chứa SĐT hoặc cài đặt riêng tư. respondedAt có thể null khi PENDING. Lookup giữ đúng tên boolean isContact; relationshipStatus là STRANGER, OUTGOING_PENDING, INCOMING_PENDING, FRIEND hoặc BLOCKED. Khi có lời mời chờ, pendingRequestId là ID lời mời và pendingRequestDirection là INCOMING/OUTGOING theo tài khoản JWT; khi không có thì các trường pending là null.

Lỗi dùng envelope ở mục 1. `400 PHONE_INVALID/SELF`, `404 NOT_FOUND` (gồm các trường hợp cần che hồ sơ), `403 BLOCKED/FORBIDDEN`, `409 REQUEST_ALREADY_PENDING/ALREADY_FRIENDS/INVALID_STATE`, `429 RATE_LIMITED`. Lỗi REQUEST_ALREADY_PENDING có details `{existingRequestId, direction}` với direction INCOMING/OUTGOING; RATE_LIMITED có `{retryAfterSeconds}` là số giây nguyên không âm. JWT/thiết bị không hợp lệ trả `401 SESSION_EXPIRED`; không nhận actor từ body/query.

**Tra cứu SĐT (03):** backend chỉ nhận E.164 hợp lệ (8–15 chữ số sau dấu +), không tự đoán số Việt Nam chưa chuẩn hóa; Android nối chuẩn hóa ô nhập ở mục 17. Đầu vào sai trả PHONE_INVALID trước khi tính lượt. Mỗi tra cứu đúng cấu trúc đều tính lượt, kể cả SELF hoặc NOT_FOUND; lượt thứ 11 trong cửa sổ 60 giây trả RATE_LIMITED theo tài khoản JWT. Người không tồn tại, không ACTIVE, tắt discoverable hoặc có block ở bất kỳ chiều nào đều bị che bằng NOT_FOUND, không trả DTO BLOCKED hay hồ sơ một phần. Lookup không ghi contact/request/sync_events; đọc lại hồ sơ và quan hệ sau khi lấy khóa cặp trong transaction.

**Body gửi lời mời (04):** thiếu/sai addresseeId hoặc UUID rút gọn trả `400 VALIDATION`; UUID đầy đủ viết hoa/thường đều được nhận. Người gửi lấy từ JWT. Biết UUID của người dùng ACTIVE không bị chặn vẫn được gửi lời mời dù người nhận tắt discoverable hoặc quyền nhận tin từ người lạ; các cài đặt đó không thay cho block. Một lời mời mới sinh đúng hai CONTACT_REQUEST_CREATED trong cùng transaction; lỗi ghi sự kiện phải rollback cả lời mời, không để lại pending hoặc sự kiện bên thứ nhất. Trùng cùng/ngược chiều đều trả 409 với ID/chiều lời mời hiện có, không tạo thêm sự kiện hoặc tự chấp nhận.

**Danh sách lời mời (05):** luôn giới hạn requester/addressee theo tài khoản JWT, không nhận userId thay chủ danh sách. status thiếu hoặc blank mặc định PENDING; nhận đúng PENDING/ACCEPTED/REJECTED/CANCELED. type thiếu hoặc blank lấy cả hai chiều; incoming/outgoing lọc theo người nhận/người gửi. Giá trị khác trả `400 VALIDATION`. Chỉ trả lời mời có cả hai tài khoản ACTIVE, hồ sơ tối thiểu không SĐT; discoverable và quyền nhận tin không che lời mời của người đã tham gia. Sắp createdAt giảm dần, cùng thời gian thì ID giảm dần; danh sách rỗng là `[]`. Đây là API đọc, không ghi quan hệ hay sự kiện.

**Chấp nhận lời mời (06):** chỉ addressee từ JWT được accept; người gửi/người thứ ba trả `403 FORBIDDEN`, ID sai/không tồn tại trả `404 NOT_FOUND`. Lấy khóa cặp trước khóa dòng, đọc lại trạng thái và kiểm block cả hai chiều; có block trả `403 BLOCKED`, REJECTED/CANCELED trả `409 INVALID_STATE`. PENDING chuyển ACCEPTED cùng respondedAt, upsert hai chiều bạn bè và sự kiện trong một transaction. Giữ createdAt của dòng contact cũ; mỗi chiều thực sự đổi sinh CONTACT_UPDATED, cả hai người nhận nhận CONTACT_REQUEST_UPDATED. Lỗi ở bất kỳ ghi quan hệ/sự kiện nào rollback toàn bộ. Lặp ACCEPTED không ghi thêm hoặc tự tạo lại contacts đã hủy; không đụng lời mời mới hơn. Trả DTO lời mời lịch sử không đồng nghĩa quan hệ bạn bè hiện tại vẫn còn.

**Từ chối/hủy lời mời (07):** chỉ addressee được reject, chỉ requester được cancel; người khác trả `403 FORBIDDEN` kể cả khi thao tác lặp. ID sai/không tồn tại trả `404 NOT_FOUND`, tài khoản đối phương không ACTIVE cũng trả NOT_FOUND. PENDING chuyển REJECTED/CANCELED và respondedAt, sinh hai CONTACT_REQUEST_UPDATED nguyên tử; không sửa contacts. Cùng thao tác lặp trả kết quả cũ, không đổi respondedAt hoặc thêm event; chuyển từ trạng thái đóng khác trả `409 INVALID_STATE`, accept sau reject/cancel cũng vậy. Reject/cancel vẫn được phép nếu tồn tại block. Reject trả DTO (200); cancel trả 204 không body. Khóa cặp lấy trước khóa dòng, chỉ một chuyển trạng thái thắng khi các thao tác đồng thời.

**Bạn bè/hủy kết bạn (08):** GET /contacts lấy chiều chủ sở hữu JWT, is_friend=true và blocked=false, target ACTIVE; sắp displayName rồi ID tăng dần. Trả đúng DTO contact, phone có thể null. Discoverable/quyền nhận tin không che bạn đã có; block chiều ngược không tự đổi dòng của chủ danh sách, nhưng tương tác/lookup vẫn kiểm block hai chiều. DELETE /contacts/{id} tự lấy actor từ JWT: self trả `400 SELF`, ID sai/target không tồn tại hoặc không ACTIVE trả `404 NOT_FOUND`. Trong một transaction khóa cặp, xóa dòng không chặn ở hai chiều; với dòng chặn chỉ hạ is_friend=false, giữ blocked/createdAt. Ghi CONTACT_UPDATED riêng cho chủ mỗi chiều thực sự đổi; dòng mất có removed=true và không user, dòng block-only còn lại có isFriend=false/blocked=true và user.phone=null. Người lạ, dòng block-only hoặc thao tác lặp không đổi trạng thái không thêm event. Thành công trả 204 không body, không hủy lời mời đang chờ hoặc suy friendship từ ACCEPTED lịch sử. Lỗi ghi event/quan hệ rollback toàn bộ.

**Khóa và transaction:** mọi thay đổi quan hệ ở mục 03–09 phải lấy khóa cặp UUID không phân biệt chiều bằng ContactPolicyService.lockPair trong transaction, **trước** khóa dòng; không tự lấy khóa dòng rồi mới khóa cặp. Khóa thuộc transaction và được nhả khi commit/rollback. Chặn một chiều cấm tương tác cả hai chiều; đủ hai dòng is_friend không chặn mới được dùng như quan hệ bạn bè để kiểm chính sách.

**Hợp đồng sync_events của Phase 4** (lưu database; API đồng bộ/WebSocket thực hiện ở Phase 5):

| event_type | entity_type / entity_id | payload_json | Người nhận |
|---|---|---|---|
| CONTACT_REQUEST_CREATED | CONTACT_REQUEST / ID lời mời | Toàn bộ DTO request sau khi tạo, status PENDING, hai hồ sơ tối thiểu không SĐT | Người gửi và người nhận |
| CONTACT_REQUEST_UPDATED | CONTACT_REQUEST / ID lời mời | Toàn bộ DTO request sau chuyển trạng thái, gồm respondedAt; không chỉ gửi một lệnh hoặc trường status riêng lẻ | Người gửi và người nhận |
| CONTACT_UPDATED | CONTACT / ID đối phương | `{contactUserId, isFriend, blocked, removed, user?}` phản ánh dòng quan hệ của chính người nhận; user nếu có dùng DTO contact và chính sách trả phone của server | Chủ trạng thái quan hệ bị thay đổi; nếu cả hai chiều đổi thì ghi riêng cho hai bên |

Sự kiện và dữ liệu nghiệp vụ phải cùng commit/rollback; SyncEventWriter yêu cầu transaction đang tồn tại. Không ghi sự kiện mới cho thao tác lặp không đổi trạng thái. removed=true nghĩa là không còn dòng quan hệ; isFriend/blocked vẫn là boolean rõ ràng. Không khôi phục quan hệ chỉ từ sự kiện hoặc lời mời ACCEPTED lịch sử. Các quy tắc này là hợp đồng để nghiệm thu từng API ở mục 03–09, không xác nhận các API đó đã hoàn thành.

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
> - Khi người dùng đặt thành `false`: chỉ những người dùng đã có quan hệ bạn bè được chấp thuận (cả hai chiều `contacts.is_friend = true`, không có block ở bất kỳ chiều nào) mới được gửi tin nhắn trực tiếp 1-1.
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

`VALIDATION`, `PHONE_TAKEN`, `PHONE_INVALID`, `BAD_CREDENTIALS`, `LOCKED`, `WEAK_PASSWORD`, `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPTS`, `OTP_RATE_LIMIT`, `SESSION_EXPIRED`, `NOT_FOUND`, `FORBIDDEN`, `NOT_A_MEMBER`, `NOT_ADMIN`, `GROUP_FULL`, `BLOCKED`, `CONTACT_REQUIRED`, `REQUEST_ALREADY_PENDING`, `ALREADY_FRIENDS`, `INVALID_STATE`, `FILE_TOO_LARGE`, `UNSUPPORTED_TYPE`, `CALL_BUSY`, `CALL_NOT_FOUND`, `CALL_UNSUPPORTED`, `SELF`, `RATE_LIMITED`, `INTERNAL`.

## 6. Chính sách thông báo (Notification)

- V1 **không dùng Firebase Cloud Messaging (FCM)**. FCM chỉ ghi nhận như hướng mở rộng tương lai cho V2.
- `NotificationChannel` (`messages`) được sử dụng để phát thông báo khi ứng dụng đang hoạt động (foregound/background) hoặc kết nối WebSocket còn duy trì.
- Khi ứng dụng ở nền và WebSocket bị ngắt, `WorkManager` định kỳ (15 phút) gọi `GET /api/sync?after=<syncEventId>` để kiểm tra tin mới theo cơ chế **best-effort** (không cam kết nhận tức thời).
- Khi ứng dụng bị người dùng hoặc hệ thống **force-stop hoàn toàn**, hệ điều hành Android đình chỉ toàn bộ tiến trình nền, do đó không cam kết nhận được thông báo ngay lập tức cho đến khi ứng dụng được mở lại.

## 7. Tính tương thích

- Tiền tố `/api` cố định cho V1. Thêm trường mới vào JSON là tương thích ngược.
- App dùng Gson và DTO tách biệt với Room Entity.
