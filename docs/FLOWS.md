# UTEChat — Các luồng hoạt động

> **Trạng thái:** ĐÃ CHỐT 2026-11-09 (đổi qua pull request). Mỗi luồng có mã `F-xx` để báo cáo, test và công việc dẫn chiếu. Luồng bám các ADR trong `ARCHITECTURE_DECISIONS.md`. Cột/bảng xem `ERD.md`, endpoint xem `API_CONTRACT.md`.

## Mục lục

| Nhóm | Luồng |
|---|---|
| Tài khoản và bảo mật | F-01 Đăng ký · F-02 Đăng nhập bằng mật khẩu · F-03 Đăng nhập bằng OTP · F-04 Bước nhận diện bạn bè · F-05 Bật 2FA · F-06 Tắt 2FA · F-07 Quên và đặt lại mật khẩu · F-08 Thiết bị · F-09 Đăng xuất · F-10 Token hết hạn hoặc bị thu hồi · F-11 Hồ sơ, đổi mật khẩu, xoá tài khoản |
| Danh bạ | F-12 Thêm bạn bằng số điện thoại |
| Khởi động | F-30 Mở app (lần đầu và các lần sau) |
| Chat | F-29 Mở hội thoại và tải lịch sử · F-13 Gửi tin khi có mạng · F-14 Gửi tin khi mất mạng (hàng đợi) · F-15 Nhận tin và trạng thái đã nhận, đã đọc · F-16 Đang nhập · F-17 Mất kết nối, nối lại, đồng bộ |
| Quy tắc chung | Mục 11: nhiều thiết bị, chặn, quyền xem lịch sử nhóm, khoá chống dò, xoá tài khoản, xem OTP demo |
| Nhóm | F-18 Tạo nhóm · F-19 Quản lý thành viên, rời nhóm, chuyển quản trị |
| Tìm kiếm | F-20 Tìm kiếm |
| Đa phương tiện | F-21 Gửi ảnh, voice, video, file, vị trí |
| Thông báo | F-22 Thông báo tin mới |
| Mở rộng | F-23 Dịch tin nhắn · F-24 Trợ lý AI · F-25 Gọi thoại · F-26 Cuộc gọi nhỡ |
| Giao diện | F-27 Ngôn ngữ, chế độ tối, xoay màn hình |
| Vận hành | F-28 Triển khai và CI/CD |

Quy ước lỗi chung: mọi lỗi trả `{ "error": { "code", "message" } }` (xem `API_CONTRACT.md`). Thông báo cho người dùng lấy từ chuỗi tài nguyên `en` và `vi`, không viết cứng.

---

## 1. Tài khoản và bảo mật

### F-01 Đăng ký (ADR-001, ADR-002)

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant A as App
    participant S as Server
    U->>A: Nhập số điện thoại
    A->>A: Chuẩn hoá E.164, kiểm tra định dạng
    A->>S: POST /auth/register/otp (phone, deviceId)
    alt Số đã có tài khoản
        S-->>A: 409 PHONE_TAKEN
    else Số mới
        S->>S: Kiểm tra giới hạn gửi, tạo OTP, lưu băm, ghi demo_sms_outbox
        S-->>A: 200 (challengeId, hết hạn sau 5 phút)
    end
    U->>A: Nhập OTP
    A->>S: POST /auth/register/verify (challengeId, code)
    alt Sai hoặc hết hạn hoặc quá số lần
        S-->>A: 400 OTP_INVALID, OTP_EXPIRED hoặc 429 OTP_ATTEMPTS
    else Đúng
        S-->>A: 200 (registrationToken, dùng một lần, 10 phút)
    end
    U->>A: Nhập tên hiển thị và mật khẩu
    A->>S: POST /auth/register/complete (registrationToken, name, password, deviceName)
    S->>S: Tạo users, user_devices (trusted = true), băm mật khẩu
    S-->>A: 201 (token, hồ sơ)
    A->>A: Lưu token mã hoá bằng Keystore, vào app
```

**Ràng buộc:** mật khẩu ≥ 8 ký tự. Tên hiển thị 1 đến 50 ký tự. Kiểm tra dữ liệu **ở cả hai đầu**. **Lỗi cần xử lý:** số sai định dạng, mất mạng, OTP sai, hết hạn, quá số lần, gửi lại quá nhanh (đếm ngược 60 giây trên giao diện).

### F-02 Đăng nhập bằng mật khẩu (ADR-003)

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant A as App
    participant S as Server
    U->>A: Nhập số điện thoại và mật khẩu
    A->>S: POST /auth/login/password (phone, password, deviceId, deviceName)
    alt Sai thông tin hoặc đang bị khoá
        S-->>A: 401 BAD_CREDENTIALS (lỗi chung) hoặc 423 LOCKED
    else Đúng, thiết bị tin cậy
        S-->>A: 200 COMPLETE (token)
    else Đúng, thiết bị lạ
        S->>S: Tạo auth_sessions. 2FA tắt thì NEED_OTP và gửi OTP. 2FA bật thì NEED_2FA
        S-->>A: 200 (authSessionId, next = OTP hoặc TOTP)
        U->>A: Nhập OTP hoặc mã 2FA hoặc mã dự phòng
        A->>S: POST /auth/login/second-factor (authSessionId, code)
        S->>S: Kiểm tra mã
        alt Sai
            S-->>A: 400 CODE_INVALID, đếm số lần
        else Đúng và còn cần bước bạn bè
            S-->>A: 200 NEED_FRIEND (xem F-04)
        else Đúng và không cần bước bạn bè
            S-->>A: 200 COMPLETE (token)
        end
    end
```

**Quy tắc khoá:** 5 lần sai mật khẩu cho một (số điện thoại, thiết bị) thì khoá 15 phút, **và** tổng 20 lần sai mỗi giờ cho một số điện thoại (bất kể thiết bị) thì khoá số đó 1 giờ, để kẻ đổi `deviceId` liên tục không né được. Thông báo lỗi sai thông tin **không lộ** số có tồn tại hay không.
**Gửi lại OTP** trong bước này: `POST /auth/login/second-factor/resend` (cùng giới hạn gửi OTP).

### F-03 Đăng nhập bằng OTP

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant A as App
    participant S as Server
    U->>A: Nhập số điện thoại, chọn "Gửi mã OTP"
    A->>S: POST /auth/login/otp/request (phone, deviceId)
    S->>S: Số không có tài khoản vẫn trả 200 (không lộ), chỉ gửi OTP nếu có
    S-->>A: 200 (challengeId)
    U->>A: Nhập OTP
    A->>S: POST /auth/login/otp/verify (challengeId, code, deviceName)
    alt Sai hoặc hết hạn
        S-->>A: 400 OTP_INVALID hoặc OTP_EXPIRED
    else Đúng, thiết bị tin cậy
        S-->>A: 200 COMPLETE (token)
    else Đúng, thiết bị lạ
        S-->>A: 200 NEED_FRIEND hoặc COMPLETE (xem F-04)
    end
```

OTP đăng nhập là **đủ** ở cả thiết bị lạ, kể cả khi tài khoản bật 2FA (đã chốt). Riêng bước bạn bè (F-04) áp dụng cho thiết bị lạ.

### F-04 Bước nhận diện bạn bè (ADR-005)

Áp dụng sau khi qua OTP hoặc 2FA trên **thiết bị lạ**.

```mermaid
stateDiagram-v2
    [*] --> KiemTra
    KiemTra --> BoQua: Tài khoản mới, hoặc dưới 3 bạn, hoặc không đủ 12 mồi
    BoQua --> HoanTat
    KiemTra --> HienThiThuThach: Từ 3 bạn trở lên
    HienThiThuThach --> HoanTat: Chọn đúng cả 3
    HienThiThuThach --> HienThiThuThach: Sai, còn lượt, xáo trộn lại thứ tự bộ 15 người cũ
    HienThiThuThach --> Khoa: Sai lần thứ 3
    Khoa --> [*]: Khoá 15 phút, báo các thiết bị tin cậy
    HoanTat --> [*]: Cấp token, ghi nhận thiết bị tin cậy
```

**Cách sinh bộ 15 người:** chọn ngẫu nhiên 3 bạn thật (ưu tiên người đã nhắn tin), thêm 12 mồi (người dùng khác: không phải bạn, không bị chặn, không phải bot, `discoverable = true`, không phải tài khoản đã xoá), trộn ngẫu nhiên, chỉ trả `id` và tên hiển thị. Đáp án lưu bản băm. **Chống tấn công giao thoa (intersection attack):** Toàn bộ 15 ứng viên (3 bạn thật + 12 mồi) và 3 đáp án được tái sử dụng và giữ cố định trong suốt cửa sổ hiệu lực (15 phút) của tài khoản/cửa sổ chống dò (đồng bộ với ERD.md). Mỗi lần sai chỉ xáo trộn lại thứ tự hiển thị và tăng `attempts_used` (tối đa 3 lần). Thông báo sai không nói đúng bao nhiêu người.
**Lỗi và biên:** hết hạn bộ thì yêu cầu bộ mới (không tính là lần thử). Phiên `auth_sessions` hết hạn sau 10 phút thì đăng nhập lại từ đầu.

### F-05 Bật 2FA (ADR-004)

1. Người dùng vào Cài đặt, Bảo mật, **Bật xác thực hai lớp**. Server yêu cầu nhập lại **mật khẩu**.
2. `POST /account/2fa/setup` tạo khoá bí mật, lưu `totp_credentials` (chưa xác nhận), trả đường dẫn `otpauth://totp/UTEChat:+84…?secret=…&issuer=UTEChat`.
3. App hiện mã QR (ZXing) và khoá để nhập tay. Người dùng quét bằng ứng dụng authenticator.
4. Người dùng nhập thử một mã. `POST /account/2fa/enable` kiểm tra (cho lệch ±1 nhịp, chống dùng lại bằng `last_used_step`).
5. Đúng thì `confirmed_at` được đặt, `users.two_factor_enabled = true`, server trả **8 mã dự phòng** (chỉ hiện một lần). Người dùng phải bấm xác nhận đã lưu.

### F-06 Tắt 2FA

Người dùng nhập **mật khẩu** và **một mã 2FA hợp lệ** (hoặc một mã dự phòng) rồi `POST /account/2fa/disable`. Xoá `totp_credentials` và `backup_codes`, đặt `two_factor_enabled = false`.

### F-07 Quên và đặt lại mật khẩu

1. Nhập số điện thoại, gửi OTP với `purpose = PASSWORD_RESET` (số không có tài khoản vẫn trả 200).
2. Nhập OTP đúng thì nhận `resetToken` một lần.
3. Nhập mật khẩu mới. Server cập nhật mật khẩu và **thu hồi mọi thiết bị** khác (đặt `revoked_at` và `token_valid_after`, đóng WebSocket).
4. Sau đó **đi tiếp như đăng nhập bằng OTP (F-03)**: thiết bị đang dùng mà là thiết bị lạ thì vẫn phải qua **bước bạn bè (F-04)**. Nếu không, kẻ chỉ cần OTP có thể đi đường "quên mật khẩu" để né bước bạn bè.

### F-08 Thiết bị

- `GET /account/devices` liệt kê thiết bị (tên máy, lần dùng gần nhất, đang dùng hay không).
- `DELETE /account/devices/{id}` thu hồi: đặt `revoked_at` và `token_valid_after = now`, gửi WebSocket `auth.revoked` rồi đóng kết nối. Token của thiết bị đó bị từ chối **ngay** ở yêu cầu kế tiếp (server kiểm tra mốc này ở mỗi yêu cầu và khi nối WebSocket).
- Không được tự thu hồi thiết bị đang dùng bằng nút này (dùng đăng xuất).

### F-09 Đăng xuất

App gọi `POST /auth/logout`. Server đặt `token_valid_after = now` cho thiết bị đó nên token cũ bị từ chối (thiết bị **vẫn là thiết bị tin cậy**, đăng nhập lại không cần OTP). App xoá token, đóng WebSocket, xoá Room của người dùng (hoặc giữ nếu chọn "nhớ tài khoản", mặc định xoá), quay về màn hình đăng nhập. Nếu hàng đợi còn tin **chưa gửi**, app hỏi xác nhận trước ("Còn n tin chưa gửi sẽ bị mất").

### F-10 Token hết hạn hoặc bị thu hồi

Theo slide Session 5 và 6, **không có refresh token**. App gửi `Authorization: Bearer <token>` ở mọi yêu cầu qua `AuthInterceptor` (token đọc từ bộ nhớ, đã giải mã bằng Keystore một lần). Khi server trả `401` (token hết hạn 30 ngày, thiết bị bị thu hồi hoặc đã đăng xuất), `AuthInterceptor` gọi `SessionEvents.notifyExpired()`. App lắng nghe sự kiện này, **xoá token**, đóng WebSocket (không nối lại khi bị từ chối token) và đưa người dùng về màn hình đăng nhập (`popUpTo` hết back stack). WebSocket đóng với mã `4401` cũng đi theo đường này.

### F-11 Hồ sơ, đổi mật khẩu, xoá tài khoản

- **Sửa tên và ảnh đại diện:** `PATCH /me`. Ảnh chọn bằng photo picker (không cần quyền), nén, upload (F-21), gán `avatar_url`. Lỗi: ảnh quá lớn hoặc sai định dạng.
- **Đổi mật khẩu:** bắt buộc nhập mật khẩu cũ. Thành công thì thu hồi các thiết bị khác.
- **Đổi ngôn ngữ:** phía app (F-27).
- **Xoá tài khoản:** nhập mật khẩu để xác nhận. Xoá mềm theo `ERD.md` mục 3 (quy tắc 6).

---

## 2. Danh bạ

### F-12 Thêm bạn bằng số điện thoại

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant A as App
    participant S as Server
    U->>A: Nhập số điện thoại cần tìm
    A->>A: Chuẩn hoá và kiểm tra
    A->>S: GET /users/lookup?phone=+84…
    alt Số của chính mình
        S-->>A: 400 SELF
    else Không có hoặc đã xoá hoặc không discoverable
        S-->>A: 404 NOT_FOUND
    else Tìm thấy
        S-->>A: 200 (id, tên, ảnh, đã là bạn chưa)
    end
    U->>A: Bấm Thêm bạn hoặc Nhắn tin
    A->>S: POST /contacts (contactUserId)
    S->>S: Tạo contacts một chiều, không lỗi nếu đã có
    S-->>A: 201
    A->>A: Lưu Room, mở hoặc tạo hội thoại 1-1 (POST /conversations/direct)
```

**Quy tắc:** chỉ khớp chính xác số điện thoại. Giới hạn 10 lượt tra mỗi phút mỗi người dùng (chống dò). Người bị chặn không thấy hồ sơ nhau. Có thể chặn và bỏ chặn (`blocked`).

---

## 3. Chat

### F-13 Gửi tin khi có mạng (ADR-008, ADR-009)

```mermaid
sequenceDiagram
    actor U as Người gửi
    participant A as App của người gửi
    participant S as Server
    participant B as App của người nhận
    U->>A: Gõ tin và bấm gửi
    A->>A: Tạo clientId, ghi Room (SENDING), hiện ngay trong khung chat
    A->>S: POST /conversations/{id}/messages (clientId, type, text)
    S->>S: Kiểm tra là thành viên, kiểm tra dữ liệu
    S->>S: Một giao dịch: chèn messages (chống trùng theo clientId), cập nhật last_message_id
    S-->>A: 201 (messageId, createdAt)
    A->>A: Cập nhật Room: SENT, gắn serverId
    S-->>B: WebSocket message.new (nếu người nhận đang nối)
    B->>B: Ghi Room, hiện tin, báo thông báo nếu cần (F-22)
    B->>S: POST /conversations/{id}/delivered (upToMessageId)
    S-->>A: WebSocket message.status (deliveredUpTo)
    A->>A: Tin chuyển DELIVERED
```

**Trạng thái tin của người gửi:** `SENDING → SENT → DELIVERED → READ`, hoặc `FAILED` (F-14). `DELIVERED` và `READ` suy ra từ mốc của người nhận (ADR-010).
**Gửi trùng:** server nhận lại cùng `clientId` thì trả cùng `messageId` và không tạo dòng mới.
**Bị chặn:** trong hội thoại 1-1, nếu người nhận đã chặn người gửi thì server trả `403 BLOCKED` và tin thành `FAILED`. Chặn không ảnh hưởng chat nhóm.
**Nhiều thiết bị:** `message.new` được đẩy tới **mọi thiết bị đang nối** của mọi thành viên, **kể cả các thiết bị khác của chính người gửi** (để máy kia cũng thấy tin mình vừa gửi).

### F-14 Gửi tin khi mất mạng (hàng đợi)

```mermaid
stateDiagram-v2
    [*] --> SENDING: Ghi Room, xếp WorkManager
    SENDING --> SENT: REST thành công
    SENDING --> SENDING: Lỗi mạng, thử lại theo backoff lũy tiến
    SENDING --> FAILED: Lỗi vĩnh viễn (400, 403, 404) hoặc quá số lần thử
    FAILED --> SENDING: Người dùng bấm gửi lại
    SENT --> DELIVERED
    DELIVERED --> READ
```

- **Một chuỗi duy nhất tên `send-messages`** (`enqueueUniqueWork`, `ExistingWorkPolicy.APPEND_OR_REPLACE`) để mọi tin gửi **đúng thứ tự**, như slide Session 5.
- `SendMessageWorker` chỉ nhận `clientId`, trả `success` hoặc `retry`, `MAX_ATTEMPTS = 3`. Ràng buộc `NetworkType.CONNECTED`, backoff lũy tiến (`EXPONENTIAL`) bắt đầu 10 giây.
- Việc chỉ mang `clientId`, nội dung đọc lại từ Room (kể cả sau khi app bị tắt).
- Khi có mạng lại, hàng đợi tự chạy và server chống trùng nhờ `clientId`.
- Tin có đính kèm: upload xong (F-21) mới gửi tin; nếu upload lỗi thì tin `FAILED`.

### F-15 Nhận tin và trạng thái đã nhận, đã đọc

- **Nhận khi đang nối:** `message.new` qua WebSocket. App ghi Room, UI tự cập nhật nhờ LiveData.
- **Đã nhận:** khi app nhận tin (dù chưa mở hội thoại), gọi `POST /conversations/{id}/delivered` với tin mới nhất. Server nâng `last_delivered_message_id` (không bao giờ giảm) và đẩy `message.status` cho các thành viên khác.
- **Đã đọc:** khi hội thoại đang mở trên màn hình và tin hiển thị, app gọi `POST /conversations/{id}/read`. Server nâng `last_read_message_id` và đẩy trạng thái. Số tin chưa đọc về 0.
- **Nhóm:** "đã đọc" khi mọi thành viên còn lại có mốc ≥ id tin, hoặc hiện số người đã xem.
- **Nhận qua đồng bộ:** tin nhận được qua `GET /sync` (lúc app vừa mở lại) cũng phải báo `delivered` như tin nhận qua WebSocket.
- **Nhiều thiết bị:** mốc là **theo người dùng**, không theo thiết bị. **Định nghĩa mốc:** `DELIVERED` nghĩa là *ít nhất một thiết bị của người nhận đã lưu thành công tin nhắn vào dữ liệu cục bộ*. `READ` nghĩa là *người nhận đã đọc tin nhắn trên ít nhất một thiết bị*. Đọc ở máy này thì máy kia cũng hết số chưa đọc (server đẩy `message.status` cho cả các thiết bị của chính người đó). Thiết bị khác đang tắt sẽ đồng bộ bù sau qua `GET /sync` mà không ảnh hưởng tới trạng thái đã xác nhận trước đó. Luôn đảm bảo `last_delivered_message_id >= last_read_message_id`.

### F-16 Đang nhập

App gửi WebSocket `typing.start` khi người dùng bắt đầu gõ (giới hạn 1 lần mỗi 3 giây) và `typing.stop` khi dừng hoặc gửi. Server chuyển tiếp cho các thành viên còn lại đang nối (không lưu). Chỉ báo tự tắt sau 5 giây không có tín hiệu mới.

### F-17 Mất kết nối, nối lại, đồng bộ (ADR-008)

```mermaid
stateDiagram-v2
    [*] --> Connected: Mở app, nối /ws bằng JWT
    Connected --> Disconnected: Mất mạng hoặc server đóng
    Disconnected --> Reconnecting: Chờ 1, 2, 4, 8… tối đa 30 giây
    Reconnecting --> Connected: Nối thành công thì gọi GET /sync
    Reconnecting --> Reconnecting: Thất bại
    Connected --> [*]: Đăng xuất hoặc thiết bị bị thu hồi (auth.revoked)
```

`GET /sync?after=<lastMessageId>` trả: (1) các tin có id lớn hơn con trỏ **trong khoảng người dùng được xem** (mục 11.3), (2) các hội thoại có thay đổi (nhóm mới, đổi tên, đổi thành viên: những thay đổi này luôn sinh tin `SYSTEM` nên không bị bỏ sót), (3) **mốc đã nhận/đã đọc hiện tại** của các thành viên trong mọi hội thoại của người dùng (mốc không sinh tin nên phải gửi trạng thái hiện tại). Kết quả nhiều thì phân trang bằng `nextCursor`. App gọi nó: (a) sau mỗi lần nối lại, (b) khi mở app, (c) trong tác vụ định kỳ WorkManager. Server trả `401` khi token sai và app chạy F-10.

---

## 4. Nhóm

### F-18 Tạo nhóm

1. Người dùng chọn ≥ 2 người từ danh bạ và đặt tên nhóm (1 đến 50 ký tự).
2. `POST /conversations/group (title, memberIds)`. Server kiểm tra: mỗi người **có trong danh bạ của người tạo** và **không chặn người tạo**, không phải bot, tài khoản còn hoạt động. Giới hạn nhóm tối đa **50 thành viên**. Thêm thành viên sau này dùng đúng quy tắc này.
3. Người tạo là `ADMIN`, những người còn lại `MEMBER`. Server chèn tin `SYSTEM` `GROUP_CREATED` và đẩy `conversation.updated` cho mọi thành viên.

### F-19 Quản lý thành viên, rời nhóm, chuyển quản trị

| Hành động | Ai được làm | Kết quả |
|---|---|---|
| Thêm thành viên | `ADMIN` | Thêm dòng `conversation_members`, tin `SYSTEM` `MEMBER_ADDED`. Người mới không thấy tin trước lúc vào |
| Xoá thành viên | `ADMIN` | Đặt `left_at`, tin `SYSTEM` `MEMBER_REMOVED`. Người bị xoá giữ tin cũ nhưng không nhận tin mới |
| Đổi tên nhóm | `ADMIN` | Tin `SYSTEM` `GROUP_RENAMED` |
| Cấp hoặc bỏ quyền quản trị | `ADMIN` | Tin `SYSTEM` `ADMIN_CHANGED` |
| Rời nhóm | Bất kỳ thành viên | Đặt `left_at`, tin `SYSTEM` `MEMBER_LEFT` |

**Thêm lại người đã rời:** đặt lại `left_at = NULL` và mở **khoảng xem mới** bắt đầu từ lúc được thêm lại (mục 11.3). Tin trong thời gian vắng mặt không xem được. Tin của lần tham gia trước vẫn còn trên máy họ nếu chưa xoá.
**Người bị xoá** nhận được đúng tin `SYSTEM` báo mình bị xoá (để app biết và khoá ô nhập), sau đó không nhận gì thêm.

**Quy tắc quản trị:** nếu quản trị viên **cuối cùng** rời đi, server tự nâng thành viên **vào nhóm sớm nhất** lên `ADMIN` trong cùng giao dịch. Nhóm không bao giờ không có `ADMIN` khi còn người. Nhóm hết người thì đánh dấu đã đóng. Tin `SYSTEM` lưu mã sự kiện và tham số, app dịch theo ngôn ngữ.

---

## 5. Tìm kiếm

### F-20 Tìm kiếm (ADR-016)

| Loại | Nơi chạy | Cách làm |
|---|---|---|
| Hội thoại và bạn bè theo tên | Room | `LIKE` trên `conversation.title` và `user_cache.displayName` |
| Tin nhắn trong một hội thoại hoặc toàn bộ | Room | Bảng FTS4 `message_fts`. Kết quả hiển thị hội thoại, đoạn trích có tô sáng từ khoá. Bấm kết quả thì mở hội thoại và **cuộn tới đúng tin** |
| Người mới | Server | `GET /users/lookup` chỉ theo số điện thoại chính xác (F-12) |

Gõ xong dừng khoảng 300 ms mới tìm (debounce). Có trạng thái "đang tìm", "không có kết quả". Tin chưa được tải về máy thì chưa tìm thấy (kéo lịch sử xuống để tải thêm).

---

## 6. Đa phương tiện

### F-21 Gửi ảnh, voice, video, file, vị trí (ADR-011)

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant A as App
    participant S as Server
    U->>A: Chọn ảnh hoặc video (photo picker), ghi âm, chọn file, hoặc chia sẻ vị trí
    A->>A: Kiểm tra loại và dung lượng, nén ảnh, ghi Room (SENDING) kèm localUri
    alt Có tệp
        A->>S: POST /media (multipart, kind, tệp)
        S->>S: Kiểm tra MIME, kích thước, lưu ổ đĩa
        S-->>A: 201 (mediaUrl)
    end
    A->>S: POST /conversations/{id}/messages (clientId, type, mediaUrl hoặc toạ độ)
    S-->>A: 201 (messageId)
    Note over A,S: Người nhận nhận message.new và tải tệp qua GET /media/{id} (URL có bảo vệ bằng token)
```

| Loại | Cách lấy | Giới hạn và ghi chú |
|---|---|---|
| Ảnh | Photo picker hoặc camera | Nén về cạnh dài ≤ 1600 px, ≤ 10 MB |
| Voice | `MediaRecorder` (xin quyền `RECORD_AUDIO` khi bấm ghi) | ≤ 5 phút, ≤ 10 MB. Chạm nhanh quá thì bỏ ("quá ngắn"). Luôn `release()` |
| Video | Photo picker | ≤ 50 MB. Phát bằng trình phát trong app (`Media3 ExoPlayer`). Khi chọn mở bằng ứng dụng ngoài: app tải file về bộ nhớ tạm rồi chia sẻ `content://` qua `FileProvider` kèm quyền đọc tạm thời, hoặc xin `ticket` tạm thời từ server (xem API_CONTRACT.md), tuyệt đối không đưa JWT vào URL |
| File | Trình chọn file hệ thống | ≤ 25 MB, MIME nằm trong danh sách cho phép |
| Vị trí | `FusedLocationProviderClient.getCurrentLocation` một lần, ưu tiên `BALANCED_POWER_ACCURACY`, **kiểm tra `null`**, huỷ ở `onDestroyView`. Xin quyền bằng `PermissionRequester` khi bấm chia sẻ (đồng ý quyền xấp xỉ là đủ) | Bấm vào tin vị trí mở **màn hình bản đồ** (`SupportMapFragment`, marker, Safe Args truyền toạ độ) kèm nút "Mở trong bản đồ" bằng `geo:`. Khoá Maps API trong `local.properties`, không commit |

Mất mạng giữa chừng: tin ở trạng thái `SENDING` và `attachment_transfer` giữ tiến độ, WorkManager thử lại. Từ chối quyền: tính năng đó báo lý do, phần còn lại của app vẫn dùng được.

---

## 7. Thông báo

### F-22 Thông báo tin mới (ADR-012)

1. Lần đầu (Android 13+) xin quyền `POST_NOTIFICATIONS`. Từ chối thì app vẫn chạy, chỉ không báo.
2. Có `NotificationChannel` `messages` (độ ưu tiên cao). Một thông báo gộp theo hội thoại.
3. Khi app nhận `message.new` mà hội thoại đó **không đang mở**, hiển thị thông báo (tên người gửi, đoạn tin, hoặc "Đã gửi một ảnh"). Hội thoại bị tắt tiếng (`muted`) thì không báo.
4. Bấm thông báo mở thẳng hội thoại đó (deep link bằng Navigation). `PendingIntent` dùng `FLAG_IMMUTABLE`.
5. Khi app bị hệ thống dừng: `WorkManager` định kỳ 15 phút gọi `/sync` và báo tin mới. **Không có tin tức thời khi app đã tắt hẳn** (cần FCM nếu muốn).

---

## 8. Mở rộng

### F-23 Dịch tin nhắn (ADR-014)

1. Nhấn giữ tin văn bản của người khác rồi chọn **Dịch**.
2. Nếu chưa có gói ngôn ngữ thì tải (hiện tiến độ, chỉ qua Wi-Fi nếu người dùng chọn).
3. ML Kit dịch trên máy sang ngôn ngữ app hiện tại. Bản dịch hiện **dưới tin gốc**, lưu `message_translation`. Có nút "Ẩn bản dịch".
4. Lỗi: gói chưa tải được, ngôn ngữ nguồn không nhận ra, tin quá dài (cắt hoặc báo).

### F-24 Trợ lý AI (ADR-015)

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant A as App
    participant S as Server
    participant L as Dịch vụ AI
    U->>A: Nhắn cho "UTE AI"
    A->>S: POST /conversations/{id}/messages (như chat thường)
    S->>S: Lưu tin, kiểm tra hạn mức ai_usage
    alt Hết hạn mức
        S->>S: Bot gửi tin "Hôm nay bạn đã hết lượt hỏi"
    else Còn hạn mức
        S-->>A: WebSocket typing.start (từ bot)
        S->>L: Gửi 10 tin gần nhất (khóa API nằm ở server)
        L-->>S: Câu trả lời
        S->>S: Lưu tin của bot, tăng ai_usage
        S-->>A: WebSocket message.new (từ bot)
    end
    Note over S,L: Lỗi mạng hoặc quá thời gian: bot trả lời xin lỗi, không treo UI
```

Bot là `users.is_bot`, không đăng nhập được, không bị coi là mồi trong F-04 và **không tính là "bạn"** khi đếm "từ 3 bạn" (đều loại `is_bot`). Hội thoại với bot có `type = BOT`, tạo khi người dùng mở bot lần đầu (`POST /conversations/direct` với id của bot). Bot không vào được nhóm. Tin gửi cho bot là ảnh, voice, file thì bot trả lời "Hiện mình chỉ đọc được tin văn bản". Tin gửi cho bot đi ra bên thứ ba và giao diện có dòng thông báo.

### F-25 Gọi thoại (ADR-013)

```mermaid
sequenceDiagram
    actor C as Người gọi
    participant CA as App người gọi
    participant S as Server
    participant EA as App người nhận
    actor E as Người nhận
    C->>CA: Bấm gọi thoại
    CA->>CA: Xin quyền RECORD_AUDIO nếu chưa có
    CA->>S: POST /calls (conversationId, media = VOICE)
    S->>S: Kiểm tra là thành viên, người nhận có đang nối WebSocket không, có bận không
    alt Người nhận không nối hoặc đang trong cuộc gọi khác
        S-->>CA: 200 state = MISSED hoặc BUSY (xem F-26)
    else Đổ chuông
        S-->>CA: 201 (callId, iceServers có thời hạn)
        S-->>EA: WebSocket call.incoming (callId, caller)
        EA->>E: Đổ chuông và hiện màn hình cuộc gọi
        alt Người nhận từ chối
            E->>EA: Từ chối
            EA->>S: POST /calls/{id}/reject
            S-->>CA: call.ended (REJECTED)
        else Người nhận nhận máy
            E->>EA: Nhận
            EA->>S: POST /calls/{id}/accept
            S-->>CA: call.accepted
            CA->>S: WebSocket call.offer (SDP)
            S-->>EA: call.offer
            EA->>S: call.answer (SDP)
            S-->>CA: call.answer
            CA->>S: call.ice (các ứng viên mạng) qua lại hai chiều
            Note over CA,EA: Âm thanh đi thẳng giữa hai máy hoặc qua TURN
            C->>CA: Cúp máy
            CA->>S: POST /calls/{id}/end
            S-->>EA: call.ended (HANGUP)
        end
    end
```

**Máy trạng thái cuộc gọi (phía server)**

```mermaid
stateDiagram-v2
    [*] --> RINGING: Tạo cuộc gọi
    RINGING --> ACTIVE: Người nhận nhận máy
    RINGING --> REJECTED: Người nhận từ chối
    RINGING --> MISSED: Hết 45 giây hoặc người gọi huỷ hoặc người nhận không nối
    RINGING --> BUSY: Người nhận đang trong cuộc gọi khác
    ACTIVE --> ENDED: Một bên cúp máy
    ACTIVE --> FAILED: Mất kết nối, quá 20 giây không có tín hiệu
    ENDED --> [*]
    MISSED --> [*]
    REJECTED --> [*]
    BUSY --> [*]
    FAILED --> [*]
```

**Phạm vi:** chỉ gọi trong hội thoại 1-1 (`DIRECT`). Gọi trong nhóm hay gọi bot trả `400 CALL_UNSUPPORTED`. Người nhận đã chặn người gọi thì trả `403 BLOCKED`.
**Nhiều thiết bị:** `call.incoming` đổ chuông **mọi thiết bị đang nối** của người nhận. Thiết bị nào nhận máy trước thì thắng, các thiết bị còn lại nhận `call.handled_elsewhere` và tắt chuông.
**Trên màn hình:** đổ chuông, đang gọi, nút tắt mic, loa ngoài, cúp máy, đồng hồ thời lượng. Một người chỉ có **một cuộc gọi tại một thời điểm**. Cần dịch vụ foreground (kiểu `microphone`) khi đang gọi và app có thể ở nền. **Dự phòng khi demo:** nút `ACTION_DIAL` mở trình gọi điện thoại.

### F-26 Cuộc gọi nhỡ

Khi cuộc gọi kết thúc ở `MISSED`, `REJECTED`, `BUSY`, `FAILED` hoặc `ENDED`, server chèn một tin loại `CALL` vào hội thoại (kèm `call_id`) để cả hai thấy "Cuộc gọi nhỡ", "Cuộc gọi đã kết thúc 02:13", v.v. Người gọi thấy "Không trả lời", người nhận thấy "Cuộc gọi nhỡ". Bấm vào tin gọi lại.

---

## 9. Giao diện

### F-27 Ngôn ngữ, chế độ tối, xoay màn hình (ADR-021)

- **Ngôn ngữ:** mặc định theo hệ thống, chọn lại được trong Cài đặt (`en`, `vi`). Mọi chuỗi trong tài nguyên, không viết cứng. Thiếu chuỗi `vi` thì rơi về `en`.
- **Chế độ tối:** theme Material 3 `DayNight`, `values-night`. Kiểm tra mọi màn hình không có chữ biến mất.
- **Xoay màn hình:** Activity bị tạo lại. ViewModel giữ trạng thái. Nội dung đang gõ và vị trí cuộn được khôi phục (`SavedStateHandle`). Màn chat có layout `layout-land` hợp lý. Cuộc gọi đang diễn ra và upload đang chạy **không bị gián đoạn** khi xoay.
- **Trạng thái màn hình:** mọi màn hình danh sách đều có đủ ba trạng thái: đang tải, trống, lỗi (có nút thử lại).

---

## 10. Vận hành

### F-28 Triển khai và CI/CD (ADR-019)

```mermaid
flowchart LR
    PR["Pull request"] --> CI["GitHub Actions: build, test, đóng gói"]
    CI -- "PR: kiểm tra xong, chưa deploy" --> RV["Rà soát chéo, merge vào main"]
    RV --> CD["main: build, lưu artifact + SHA-256"]
    CD -- "ENABLE_PRODUCTION_DEPLOY = true" --> SSH["SSH vào EC2 bằng tài khoản deploy riêng"]
    SSH --> SWAP["Kiểm tra checksum, đổi bản build, khởi động lại"]
    SWAP --> HC{"/health/ready đạt?"}
    HC -- "Có" --> OK["Hoàn tất"]
    HC -- "Không" --> RB["Rollback về bản build trước"]
```

Rollback chỉ đổi bản build, **không hoàn tác migration**, nên bản cũ phải tương thích schema mới. Sao lưu CSDL Supabase trước khi chạy migration mới.

---

## 11. Luồng bổ sung và quy tắc xuyên suốt

### F-29 Mở hội thoại và tải lịch sử

1. Fragment chat nhận `conversationId` qua Safe Args, ViewModel quan sát `observeMessages(conversationId)` từ Room (hiện ngay những gì đã có).
2. Đồng thời gọi `refreshLatest` (`GET /conversations/{id}/messages?limit=50`) và ghi vào Room bằng `upsertByClientId`.
3. Cuộn lên gần đầu danh sách thì gọi `loadOlder` (`?before=<id nhỏ nhất đang có>`). Trả về ít hơn 50 tin nghĩa là hết lịch sử.
4. Tin hiển thị thì báo `read` (F-15). Thoát màn hình thì huỷ các việc đang chạy gắn với màn hình.
5. Lỗi mạng: **giữ danh sách đang có** và hiện Snackbar, không thay cả màn hình bằng lỗi (đúng slide Session 5).

### F-30 Mở app

```mermaid
flowchart TD
    Start["Mở app"] --> Dev{"Đã có deviceId?"}
    Dev -- "Chưa" --> Gen["Tạo deviceId ngẫu nhiên, lưu mã hoá"] --> Tok
    Dev -- "Rồi" --> Tok{"Đã có token?"}
    Tok -- "Chưa" --> Login["Màn hình đăng nhập hoặc đăng ký"]
    Tok -- "Rồi" --> Main["Màn hình hội thoại, hiện ngay dữ liệu trong Room"]
    Main --> Bg["Nền: nối WebSocket, gọi GET /sync"]
    Bg -- "401 hoặc mã 4401" --> Login
```

Có token thì **không chờ mạng** mới vào màn hình chính: dữ liệu cũ trong Room hiện ngay, mạng cập nhật sau (offline-first). Token chỉ bị coi là hỏng khi server trả `401`.

### 11.1 Nhiều thiết bị cùng lúc

Một tài khoản đăng nhập được trên **nhiều thiết bị tin cậy cùng lúc** (như Telegram). Server giữ danh sách kết nối WebSocket theo **thiết bị**. Tin mới, mốc đã đọc, thay đổi nhóm và cuộc gọi đến được đẩy tới mọi thiết bị đang nối. Mỗi thiết bị có hàng đợi gửi và Room riêng, đồng bộ qua `/sync`.

### 11.2 Chặn người dùng

| Tình huống | Kết quả |
|---|---|
| Người bị chặn tra số điện thoại của người chặn | `404` như không tồn tại |
| Người bị chặn nhắn trong hội thoại 1-1 | `403 BLOCKED` |
| Người bị chặn gọi | `403 BLOCKED` |
| Người bị chặn thêm người chặn vào nhóm | Bị từ chối |
| Cùng ở trong một nhóm có sẵn | Vẫn thấy tin của nhau trong nhóm |
| Bỏ chặn | Mọi thứ như cũ, tin bị từ chối lúc chặn **không** được gửi lại |

### 11.3 Quyền xem tin trong nhóm

Mỗi thành viên có một **khoảng được xem**: từ tin đầu tiên sau lúc được thêm (`visible_from_message_id`) tới tin báo mình rời hoặc bị xoá (nếu có). Server áp khoảng này cho **mọi** đường đọc tin: lịch sử, `/sync`, WebSocket, tải file đính kèm. Người ngoài khoảng nhận `403`.

### 11.4 Khoá chống dò

Mọi khoá tạm gắn với **tài khoản hoặc số điện thoại**, không gắn với phiên đăng nhập, để không thể né bằng cách bắt đầu đăng nhập lại:

| Khoá | Gắn với |
|---|---|
| Sai mật khẩu | (số, thiết bị) và (số) |
| Sai OTP | mỗi mã, cộng giới hạn gửi theo số |
| Sai mã 2FA | tài khoản |
| Sai bước bạn bè 3 lần | tài khoản (`users.friend_step_locked_until`) |

### 11.5 Xoá tài khoản

Xoá mềm: ẩn tên và ảnh (hiện "Tài khoản đã xoá"), thu hồi mọi thiết bị, rời mọi nhóm (chuyển quản trị nếu cần), xoá danh bạ hai chiều trong cùng giao dịch (`DELETE FROM contacts WHERE owner_id = :id OR contact_user_id = :id`), loại khỏi danh sách bạn/mồi khi tạo thử thách, và **giải phóng số điện thoại** (`phone_e164 = NULL`) để số đó đăng ký lại được thành tài khoản mới. Tin cũ vẫn còn trong hội thoại của người khác và tiếp tục trỏ UUID cũ (không nối quan hệ sang tài khoản mới đăng ký lại bằng số này).

### 11.6 Xem OTP demo

Chỉ ở profile `demo`: `GET /demo/sms?phone=` (kèm header khoá demo riêng, đặt bằng biến môi trường) trả các tin OTP gần nhất từ `demo_sms_outbox`. App bản debug có màn hình "Hộp thư SMS giả" gọi endpoint này. Bản release và profile `prod` **không có** endpoint và màn hình này.
