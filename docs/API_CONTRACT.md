# UTEChat — Hợp đồng API (REST và WebSocket)

> **Trạng thái:** ĐÃ CHỐT 2026-11-09 (đổi qua pull request). Đây là **hợp đồng chung** giữa app và server: người làm app và người làm server cùng dựa vào đây để làm song song. Thay đổi hợp đồng phải qua **người giữ hợp đồng chung** (ADR-022). Tiền tố mọi đường dẫn REST: `/api` (đúng mẫu `api/auth/login`, `api/conversations/{id}/messages` trong slide Session 5). Các đường dẫn bên dưới viết **không kèm** tiền tố này.

## 1. Quy ước chung

| Hạng mục | Quy ước |
|---|---|
| Dữ liệu | JSON UTF-8, tên trường `camelCase`, thời gian ISO 8601 UTC (ví dụ `2026-11-09T14:32:05Z`) |
| Xác thực | Header `Authorization: Bearer <token>`, một JWT duy nhất, **không có refresh token** (ADR-018). `401` thì app xoá token và về đăng nhập. Các endpoint `/auth/**` (trừ `logout`) không cần token |
| Danh tính | Server lấy `userId` và `deviceId` từ token, **app không gửi** |
| Chống trùng | Gửi tin dùng `clientId` (UUID). Gửi lại cùng `clientId` trả cùng kết quả |
| Phân trang | Con trỏ: `?before=<messageId>&limit=50` (lịch sử), `?after=<messageId>` (đồng bộ). `limit` tối đa 100 |
| Định danh | `uuid` cho người dùng, hội thoại, thiết bị, file, cuộc gọi. `messageId` là số nguyên |
| Số điện thoại | Luôn gửi dạng E.164 (`+84912345678`) |
| Ngôn ngữ lỗi | Mã lỗi cố định (`code`), app tự dịch `code` thành chữ theo ngôn ngữ. `message` của server chỉ để gỡ lỗi |

**Định dạng lỗi:**

```json
{ "error": { "code": "OTP_EXPIRED", "message": "OTP expired", "details": { "retryAfterSeconds": 60 } } }
```

**Mã HTTP:** `400` dữ liệu sai, `401` chưa xác thực hoặc token sai, `403` không có quyền (cũng dùng khi tài nguyên riêng tư không tồn tại), `404` không thấy, `409` xung đột (trùng), `413` tệp quá lớn, `415` loại tệp không cho phép, `423` bị khoá tạm, `429` quá giới hạn tần suất, `5xx` lỗi server.

## 2. REST

### 2.1 Xác thực

| Method và đường dẫn | Body chính | Trả về | Lỗi đặc trưng |
|---|---|---|---|
| `POST /auth/register/otp` | `phone`, `deviceId` | `challengeId`, `expiresInSeconds` | `PHONE_TAKEN` 409, `OTP_RATE_LIMIT` 429 |
| `POST /auth/register/verify` | `challengeId`, `code` | `registrationToken` | `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPTS` |
| `POST /auth/register/complete` | `registrationToken`, `displayName`, `password`, `deviceName` | `token`, `user` (201) | `WEAK_PASSWORD`, `VALIDATION` |
| `POST /auth/login/password` | `phone`, `password`, `deviceId`, `deviceName` | `state` (`COMPLETE`, `NEED_OTP`, `NEED_2FA`), token hoặc `authSessionId` | `BAD_CREDENTIALS` 401, `LOCKED` 423 |
| `POST /auth/login/otp/request` | `phone`, `deviceId` | `challengeId` (luôn 200) | `OTP_RATE_LIMIT` |
| `POST /auth/login/otp/verify` | `challengeId`, `code`, `deviceName` | `state` (`COMPLETE`, `NEED_FRIEND`), token hoặc `authSessionId` | `OTP_INVALID`, `OTP_EXPIRED` |
| `POST /auth/login/second-factor` | `authSessionId`, `code` (OTP, TOTP hoặc mã dự phòng) | `state` (`COMPLETE`, `NEED_FRIEND`) | `CODE_INVALID`, `LOCKED` |
| `POST /auth/login/second-factor/resend` | `authSessionId` | 204 (chỉ khi bước hiện tại là OTP) | `OTP_RATE_LIMIT` |
| `GET /auth/login/friend-challenge?authSessionId=` | — | `challengeId`, `candidates[{userId, displayName}]` (15), `attemptsLeft`, `expiresInSeconds` | `LOCKED`, `SESSION_EXPIRED` |
| `POST /auth/login/friend-challenge` | `authSessionId`, `challengeId`, `selectedUserIds[3]` | `COMPLETE` + token, hoặc `400 FRIEND_WRONG` kèm `attemptsLeft` và bộ mới | `LOCKED` 423 sau lần sai thứ 3 |
| `POST /auth/password-reset/otp` | `phone`, `deviceId` | `challengeId` (luôn 200) | `OTP_RATE_LIMIT` |
| `POST /auth/password-reset/verify` | `challengeId`, `code` | `resetToken` | `OTP_INVALID` |
| `POST /auth/password-reset/complete` | `resetToken`, `newPassword`, `deviceName` | `state` (`COMPLETE`, `NEED_FRIEND`), token hoặc `authSessionId` (thiết bị lạ vẫn qua bước bạn bè) | `WEAK_PASSWORD` |
| `POST /auth/logout` | — | 204 | — |

### 2.2 Tài khoản

| Method và đường dẫn | Body chính | Trả về |
|---|---|---|
| `GET /me` | — | `user` (id, phone, displayName, avatarUrl, twoFactorEnabled) |
| `PATCH /me` | `displayName`, `avatarMediaUrl` (lấy từ `POST /media`), `discoverable` | `user` |
| `POST /me/password` | `oldPassword`, `newPassword` | 204 (thu hồi thiết bị khác) |
| `DELETE /me` | `password` | 204 (xoá mềm) |
| `GET /account/devices` | — | `[{id, name, platform, lastSeenAt, current}]` |
| `DELETE /account/devices/{id}` | — | 204 |
| `POST /account/2fa/setup` | `password` | `otpauthUri`, `secret` |
| `POST /account/2fa/enable` | `code` | `backupCodes[8]` |
| `POST /account/2fa/disable` | `password`, `code` | 204 |

### 2.3 Danh bạ

| Method và đường dẫn | Body chính | Trả về |
|---|---|---|
| `GET /users/lookup?phone=` | — | `{id, displayName, avatarUrl, isContact}` hoặc 404. Giới hạn 10 lượt/phút |
| `GET /contacts` | — | danh sách bạn |
| `POST /contacts` | `contactUserId` | 201 (không lỗi nếu đã có) |
| `DELETE /contacts/{userId}` | — | 204 |
| `POST /contacts/{userId}/block` và `DELETE …/block` | — | 204 |

### 2.4 Hội thoại và tin nhắn

| Method và đường dẫn | Body chính | Trả về |
|---|---|---|
| `GET /conversations` | — | danh sách hội thoại (kèm `lastMessage`, `unreadCount`, `members` rút gọn) |
| `POST /conversations/direct` | `userId` | hội thoại 1-1 (tạo nếu chưa có, theo `direct_key`) |
| `POST /conversations/group` | `title`, `memberIds[]` | hội thoại nhóm (201) |
| `GET /conversations/{id}` | — | chi tiết kèm thành viên và vai trò |
| `PATCH /conversations/{id}` | `title`, `muted` | hội thoại (đổi tên cần `ADMIN`) |
| `POST /conversations/{id}/members` | `userIds[]` | 204 (`ADMIN`) |
| `DELETE /conversations/{id}/members/{userId}` | — | 204 (`ADMIN`, hoặc chính mình = rời nhóm) |
| `PUT /conversations/{id}/members/{userId}/role` | `role` | 204 (`ADMIN`) |
| `GET /conversations/{id}/messages?before=&limit=` | — | tin cũ hơn `before`, mới nhất trước |
| `POST /conversations/{id}/messages` | `clientId`, `type`, `text`, `mediaUrl`, `durationMs`, `latitude`, `longitude` | `messageId`, `createdAt` (201) |
| `POST /conversations/{id}/delivered` | `upToMessageId` | 204 |
| `POST /conversations/{id}/read` | `upToMessageId` | 204 |
| `GET /sync?after=<messageId>` | — | `{messages[], conversations[], receipts[], nextCursor}` |

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
| `GET /media/{id}` | — (URL có bảo vệ bằng token, gửi `Authorization`) | luồng byte, hỗ trợ `Range` (phát video, tải lại) | 403 nếu không phải thành viên |

### 2.6 Cuộc gọi

| Method và đường dẫn | Body chính | Trả về |
|---|---|---|
| `POST /calls` | `conversationId`, `media` | `callId`, `state` (`RINGING`, `MISSED`, `BUSY`), `iceServers[]` |
| `POST /calls/{id}/accept` | — | `iceServers[]` |
| `POST /calls/{id}/reject` | — | 204 |
| `POST /calls/{id}/end` | — | 204 |

`iceServers` gồm STUN và TURN với `username`/`credential` có thời hạn (HMAC), dùng cho `RTCPeerConnection`. Lỗi: `CALL_UNSUPPORTED` 400 (nhóm hoặc bot), `BLOCKED` 403, `CALL_BUSY` 409 (chính người gọi đang trong cuộc gọi khác).

### 2.7 Chỉ ở môi trường demo

| Method và đường dẫn | Ghi chú |
|---|---|
| `GET /demo/sms?phone=` | Trả các tin OTP gần nhất từ `demo_sms_outbox`. Cần header khoá demo riêng. **Không tồn tại** ở profile `prod` |

## 3. WebSocket

**Kết nối:** `wss://<host>/ws?token=<token>` (cùng JWT với REST, mã hoá URL như slide Session 5, server từ chối nếu thiết bị bị thu hồi). Server đóng kết nối với mã `4401` khi token sai hoặc thiết bị bị thu hồi. Ping/pong mỗi 25 giây, mất 2 nhịp thì coi là đứt. Token nằm trong URL nên **Nginx không được ghi query string của `/ws` vào log**. Một người dùng có thể có nhiều kết nối (mỗi thiết bị một kết nối).

**Khung chung:**

```json
{ "v": 1, "type": "message.new", "id": "…uuid sự kiện…", "payload": { } }
```

### Server gửi xuống app

| `type` | `payload` | Khi nào |
|---|---|---|
| `message.new` | message (như mục 2.4) | Có tin mới trong hội thoại của người dùng |
| `message.status` | `conversationId`, `userId`, `deliveredUpTo`, `readUpTo` | Một thành viên nâng mốc |
| `typing` | `conversationId`, `userId`, `state` (`start`, `stop`) | Người khác đang nhập (kể cả bot khi đang trả lời) |
| `conversation.updated` | hội thoại rút gọn | Đổi tên nhóm, thêm hoặc xoá thành viên, đổi vai trò |
| `call.incoming` | `callId`, `conversationId`, `caller`, `media` | Có người gọi |
| `call.accepted`, `call.ended` | `callId`, `state`, `endReason` | Thay đổi trạng thái cuộc gọi |
| `call.offer`, `call.answer`, `call.ice` | `callId`, `sdp` hoặc `candidate` | Báo hiệu WebRTC từ phía bên kia |
| `call.handled_elsewhere` | `callId` | Một thiết bị khác của chính người nhận đã nhận hoặc từ chối cuộc gọi, thiết bị này tắt chuông |
| `auth.revoked` | — | Thiết bị bị thu hồi, sau đó server đóng kết nối |

### App gửi lên server

| `type` | `payload` | Ghi chú |
|---|---|---|
| `typing.start`, `typing.stop` | `conversationId` | Giới hạn 1 lần mỗi 3 giây cho `start` |
| `call.offer`, `call.answer`, `call.ice` | `callId`, `sdp` hoặc `candidate` | Chỉ hợp lệ khi người gửi thuộc cuộc gọi đó |
| `ping` | — | Giữ kết nối |

Gửi tin, đánh dấu đã nhận, đã đọc **không** đi qua WebSocket mà qua REST (ADR-008), để chạy được qua hàng đợi WorkManager và phục hồi được khi mất kết nối.

## 4. Giới hạn tần suất

| Thao tác | Giới hạn |
|---|---|
| Gửi OTP | 1 lần mỗi 60 giây và 5 lần mỗi giờ cho mỗi số điện thoại |
| Nhập OTP | Tối đa 5 lần sai mỗi mã |
| Đăng nhập mật khẩu sai | 5 lần thì khoá 15 phút cho (số, thiết bị), và 20 lần mỗi giờ cho một số thì khoá số đó 1 giờ |
| Thử thách bạn bè | Tối đa 3 lần **theo tài khoản**, sai thì khoá tài khoản ở bước này 15 phút |
| Tra số điện thoại | 10 lượt mỗi phút mỗi người dùng |
| Mã TOTP sai | Tối đa 5 lần rồi khoá tạm |
| Gửi tin | 30 tin mỗi 10 giây mỗi người dùng |
| Upload | 20 tệp mỗi phút mỗi người dùng |
| Hỏi bot AI | 30 yêu cầu mỗi ngày mỗi người dùng (cấu hình được) |
| Tạo cuộc gọi | 5 mỗi phút mỗi người dùng |

## 5. Danh mục mã lỗi chính

`VALIDATION`, `PHONE_TAKEN`, `PHONE_INVALID`, `BAD_CREDENTIALS`, `LOCKED`, `WEAK_PASSWORD`, `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPTS`, `OTP_RATE_LIMIT`, `CODE_INVALID`, `SESSION_EXPIRED`, `FRIEND_WRONG`, `NOT_FOUND`, `FORBIDDEN`, `NOT_A_MEMBER`, `NOT_ADMIN`, `GROUP_FULL`, `BLOCKED`, `FILE_TOO_LARGE`, `UNSUPPORTED_TYPE`, `AI_QUOTA`, `AI_UNAVAILABLE`, `CALL_BUSY`, `CALL_NOT_FOUND`, `CALL_UNSUPPORTED`, `SELF`, `RATE_LIMITED`, `INTERNAL`.

## 6. Tính tương thích

- Phiên bản hợp đồng nằm ở tiền tố `/api` (như slide) và trường `v` của WebSocket. Thêm trường mới vào JSON là **tương thích**, đổi tên hoặc xoá trường là **không tương thích** và phải thêm tiền tố mới (`/api/v2`).
- App dùng **Gson** và DTO tách khỏi entity Room (ADR-023): trường lạ trong JSON bị bỏ qua, trường bắt buộc thiếu thì app coi phản hồi là lỗi.
- App bỏ qua trường lạ và `type` WebSocket lạ (không báo lỗi), để server nâng cấp trước app được.
