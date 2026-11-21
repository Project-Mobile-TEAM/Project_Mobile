# Phase 6: Media Attachments & Creative Features - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-21
**Phase:** 06-media-attachments-creative-features
**Areas discussed:** Đa phương tiện & Lưu trữ tệp, Thông báo & Đồng bộ nền 15 phút, Cuộc gọi thoại 1-1 WebRTC & coturn, Dịch tin nhắn On-device ML Kit

---

## Đa phương tiện & Lưu trữ tệp (Media, Location, FileProvider, Offline Upload)

### Câu 1: Nơi lưu trữ tệp đa phương tiện ở Backend (`POST /api/media`)

| Option | Description | Selected |
|--------|-------------|----------|
| Lưu trên ổ đĩa cục bộ (`MEDIA_STORAGE_DIR`) | Hỗ trợ HTTP Range streaming trực tiếp — khớp `FLOWS.md` F-18, chạy mượt cả local lẫn EC2 không cần API key bên thứ 3 | ✓ |
| Lưu trên Supabase Storage bucket | Proxy luồng byte qua backend `GET /api/media/{id}` (cần cấu hình thêm Supabase Storage key) | |
| Để AI quyết định | Cho agent tự chọn phương án tối ưu | |

**User's choice:** Lưu trên ổ đĩa cục bộ (`MEDIA_STORAGE_DIR`) — (Recommended).
**Notes:** Nhất quán với `docs/FLOWS.md` F-18, hỗ trợ HTTP `Range` trực tiếp qua `GET /api/media/{id}` với header `Authorization: Bearer`.

### Câu 2: Phạm vi loại tin nhắn đa phương tiện (có gồm Vị trí `LOCATION` không)

| Option | Description | Selected |
|--------|-------------|----------|
| 4 loại cốt lõi + Vị trí tọa độ nhẹ (`LOCATION`) | Ảnh nén, Voice `.m4a`, Video, File + lấy lat/lng qua `FusedLocationProviderClient`, bấm mở Google Maps ngoài qua `geo:` Intent (không cần Google Maps SDK API key) | ✓ |
| Chỉ làm đúng 4 loại trong `ROADMAP.md` | Ảnh nén, Voice `.m4a`, Video, File; giữ thẻ tóm tắt cho `LOCATION` | |
| Đủ 5 loại + nhúng `SupportMapFragment` | Nhóm tự cấu hình Google Maps API key | |

**User's choice:** 4 loại cốt lõi + Vị trí tọa độ nhẹ (`LOCATION`) — (Recommended).
**Notes:** Tránh phụ thuộc Google Maps API key khi chạy/chấm bài nhưng vẫn hỗ trợ đầy đủ `LOCATION` (`latitude`, `longitude`).

### Câu 3: Trải nghiệm xem Ảnh, nghe Voice, phát Video và mở File trên Android

| Option | Description | Selected |
|--------|-------------|----------|
| Xem ảnh toàn màn hình + phát Voice trong bong bóng + Video bằng `Media3 ExoPlayer` (+ nút mở ngoài `FileProvider`) + mở File qua `FileProvider` | Đầy đủ trải nghiệm phát media trong app và chia sẻ an toàn qua `content://` + `FLAG_GRANT_READ_URI_PERMISSION` | ✓ |
| Chỉ xem ảnh & Voice trong app; Video & File mở bằng app ngoài qua `FileProvider` | Nhẹ hơn, không thêm ExoPlayer | |
| Để AI quyết định | Cho agent tự chọn | |

**User's choice:** Xem ảnh toàn màn hình + Voice trong bong bóng + Video `Media3 ExoPlayer` (+ mở ngoài `FileProvider`) + File qua `FileProvider` — (Recommended).

### Câu 4: Hàng đợi gửi tệp đa phương tiện khi mất mạng hoặc rớt mạng giữa chừng

| Option | Description | Selected |
|--------|-------------|----------|
| Tích hợp vào `SendMessageWorker` hiện có | Copy file vào cache riêng, hiện `SENDING`, upload trước rồi gửi tin cùng `clientId` | |
| Chỉ cho gửi tệp khi đang có mạng | Mất mạng báo lỗi ngay, không xếp hàng đợi offline | |
| Free-text / Chi tiết hóa từ người dùng | Tận dụng `SendMessageWorker` với cơ chế lưu `mediaUrl` và dọn tệp tạm chặt chẽ | ✓ |

**User's choice:** Tận dụng hàng đợi `SendMessageWorker` theo phiên đăng nhập của Phase 5. Sao chép tệp vào thư mục riêng bền vững của app, lưu tiến độ trong Room `attachment_transfer` và hiện tin ở trạng thái `SENDING`. Worker upload trước, lưu `mediaUrl` ngay khi nhận được rồi gửi tin với cùng `clientId`. Nếu đã có `mediaUrl`, lần thử lại dùng kết quả upload đã lưu. Lỗi mạng thử lại có giới hạn theo quy tắc Phase 5. Lỗi 413 hoặc 415 chuyển tin sang `FAILED` và cho hàng đợi tiếp tục xử lý tin khác. Dọn tệp tạm sau khi server xác nhận đã nhận tin hoặc khi người dùng xóa tin chờ. Kiểm thử hai tình huống demo gồm chọn tệp khi đang offline và rớt mạng sau khi upload xong nhưng trước khi gửi tin thành công.
**Notes:** Ghi nhận thành ràng buộc cụ thể trong `D-04` và `<specifics>`.

---

## Thông báo & Đồng bộ nền 15 phút (NotificationChannel & WorkManager)

### Câu 5: Quy tắc lọc và gom nhóm thông báo tin nhắn mới

| Option | Description | Selected |
|--------|-------------|----------|
| Bỏ qua tin của mình, hội thoại `muted`, phòng đang mở; gom bằng `MessagingStyle`, bấm mở thẳng `ChatRoomFragment` | Trải nghiệm chuẩn app chat | ✓ |
| Thông báo đơn giản từng tin, bấm mở Danh sách chat | Bỏ qua tin của mình và `muted` | |
| Để AI quyết định | Cho agent tự chọn | |

**User's choice:** Bỏ qua tin của mình, hội thoại `muted`, phòng đang mở; gom bằng `NotificationCompat.MessagingStyle`, bấm mở thẳng `ChatRoomFragment` — (Recommended).

### Câu 6: Phân loại `NotificationChannel` và cô lập phiên đăng nhập

| Option | Description | Selected |
|--------|-------------|----------|
| 3 `NotificationChannel` (`messages`, `calls`, `social`) | Bao gồm cả thông báo lời mời kết bạn | |
| Chỉ dùng 1 kênh `messages` | Gộp chung thông báo | |
| Free-text / Chốt 2 `NotificationChannel` V1 | Chỉ dùng 2 kênh `messages` và `calls`; giữ lời mời kết bạn trong Danh bạ; hủy sạch & bỏ qua kết quả trễ khi đổi/đăng xuất phiên | ✓ |

**User's choice:** Dùng hai `NotificationChannel` trong V1. Kênh `messages` báo tin nhắn mới, hiển thị tên người gửi và đoạn tóm tắt nội dung hoặc loại tệp. Kênh `calls` dành cho cuộc gọi thoại đến qua WebSocket và cuộc gọi nhỡ. Giữ lời mời kết bạn trong màn Danh bạ. Khi đăng xuất, hết phiên hoặc đổi tài khoản, hủy thông báo của phiên cũ và bỏ qua kết quả đến muộn từ phiên đó.
**Notes:** Ghi nhận trong `D-06`.

### Câu 7: Thời điểm xin quyền `POST_NOTIFICATIONS` trên Android 13+

| Option | Description | Selected |
|--------|-------------|----------|
| Xin 1 lần sau khi đăng nhập thành công vào `MainActivity` | Nếu từ chối không hỏi lặp lại, app vẫn đồng bộ bình thường | ✓ |
| Xin ngay khi vừa mở app (trước đăng nhập) | Hiện dialog quyền ngay màn đầu tiên | |

**User's choice:** Xin 1 lần sau khi đăng nhập thành công vào `MainActivity` — (Recommended).

### Câu 8: Phối hợp giữa `PeriodicWorkRequest` 15 phút và `SyncEngine`

| Option | Description | Selected |
|--------|-------------|----------|
| `PeriodicWorkRequest` 15 phút theo phiên gọi qua `SyncEngine` single-flight | `ExistingPeriodicWorkPolicy.KEEP`, `NetworkType.CONNECTED`, hủy khi logout/`401`/`4401`, áp dụng Room xong mới phát thông báo | ✓ |
| Chỉ lên lịch khi app xuống nền và hủy khi mở lại | Không dùng chính sách KEEP theo phiên | |

**User's choice:** `PeriodicWorkRequest` 15 phút theo phiên gọi qua `SyncEngine` single-flight — (Recommended).

---

## Cuộc gọi thoại 1-1 WebRTC & coturn (Voice Call & Signaling)

### Câu 9: Cấu hình STUN/TURN (`coturn`) và sinh `iceServers[]` ở Backend

| Option | Description | Selected |
|--------|-------------|----------|
| Đọc biến môi trường (`COTURN_URLS`, `COTURN_SHARED_SECRET`, `COTURN_TTL_SECONDS`) sinh HMAC-SHA1 credential; fallback STUN công khai cho local dev + `turnserver.conf` mẫu cho EC2 | Đáp ứng `API_CONTRACT.md` và `DEPLOYMENT_SECURITY.md` | ✓ |
| Chỉ trả STUN công khai cứng | Không sinh HMAC TURN credential | |
| Để AI quyết định | Cho agent tự chọn | |

**User's choice:** Đọc biến môi trường sinh HMAC-SHA1 credential cho coturn + fallback STUN công khai cho local dev + `turnserver.conf` mẫu cho EC2 — (Recommended).

### Câu 10: Vòng đời cuộc gọi thoại, timeout 45s và xử lý đa thiết bị

| Option | Description | Selected |
|--------|-------------|----------|
| Đầy đủ `FLOWS.md`: `ACCEPTED`/không block, `409 CALL_BUSY`, 45s timeout, `call.handled_elsewhere`, chèn tin `CALL` | Theo luồng chuẩn F-21/F-22 | |
| Bản rút gọn 1 thiết bị/tài khoản | Bỏ `call.handled_elsewhere`, chỉ chèn `CALL` khi nhỡ | |
| Free-text / Chi tiết hóa bất biến đồng bộ & signaling | Bổ sung yêu cầu chuyển trạng thái nguyên tử, khóa signaling đúng 2 thiết bị sau accept, 1 tin `CALL` idempotent và đồng bộ `API_CONTRACT.md`/`FLOWS.md` | ✓ |

**User's choice:** Giữ vòng đời gọi thoại 1–1 theo F-21 và F-22. Server chỉ cho gọi giữa hai người đã kết bạn `ACCEPTED` và không bị chặn. Khi một bên đang bận, trả HTTP 409 `CALL_BUSY` và không đổ chuông. Sau 45 giây chưa có phản hồi, server chuyển `RINGING` sang `MISSED`. Thao tác nhận, từ chối và hết hạn phải cập nhật trạng thái nguyên tử để chỉ một kết quả được ghi nhận. Khi một thiết bị nhận hoặc từ chối thành công, gửi `call.handled_elsewhere` tới các thiết bị còn lại của người nhận để dừng chuông. Sau khi nhận, chỉ hai thiết bị đang tham gia cuộc gọi được trao đổi signaling cho `callId` đó. Với mỗi cuộc gọi đã lưu, chỉ ghi một tin `CALL` khi kết thúc, kể cả khi yêu cầu được gửi lại. Đồng bộ `API_CONTRACT` và `FLOWS` với các quy tắc này trước khi triển khai.
**Notes:** Đánh dấu `one-way` trong `D-10` vì yêu cầu khóa/đồng bộ hợp đồng `API_CONTRACT.md` và `FLOWS.md` trước khi triển khai.

### Câu 11: Giao diện và vòng đời cuộc gọi trên Android

| Option | Description | Selected |
|--------|-------------|----------|
| Màn hình gọi riêng + `Foreground CallService` giữ `PeerConnection` qua xoay màn hình, 3 nút cốt lõi (Mute, Loa, Kết thúc), đồng hồ `mm:ss`, chuông/rung, quyền `RECORD_AUDIO` | Tuân thủ ADR-021 giữ cuộc gọi khi xoay màn hình | ✓ |
| Giao diện tối giản trong Fragment không `Foreground Service` | Chỉ có nút Tắt mic và Kết thúc | |

**User's choice:** Màn hình gọi riêng + `Foreground CallService` — (Recommended).

### Câu 12: Thư viện WebRTC Android và seam kiểm thử

| Option | Description | Selected |
|--------|-------------|----------|
| `io.getstream:stream-webrtc-android` (Maven Central, SDK 36, Java 17) bọc sau interface `WebRtcCallEngine` | Unit test state machine 100% trên JVM + test gọi thật 2 máy qua LAN/coturn | ✓ |
| Để AI tự khảo sát thư viện Maven | Cho agent tự chọn | |

**User's choice:** `io.getstream:stream-webrtc-android` bọc sau interface `WebRtcCallEngine` — (Recommended).

---

## Dịch tin nhắn On-device ML Kit (Translation UX & Model Management)

### Câu 13: Cách xác định chiều dịch Anh – Việt

| Option | Description | Selected |
|--------|-------------|----------|
| Tự động nhận diện bằng ML Kit Language Identification | Thêm dependency nhận diện ngôn ngữ | |
| Không thêm Language ID: menu nhấn giữ chọn trực tiếp *"Dịch sang Tiếng Việt"* hoặc *"Dịch sang Tiếng Anh"* | Nhẹ APK hơn, không nhận diện nhầm câu ngắn | ✓ |

**User's choice:** Không thêm thư viện nhận diện ngôn ngữ: trong menu nhấn giữ cho chọn trực tiếp *"Dịch sang Tiếng Việt"* hoặc *"Dịch sang Tiếng Anh"*.

### Câu 14: Cách hiển thị bản dịch trên giao diện phòng chat

| Option | Description | Selected |
|--------|-------------|----------|
| Hiện bản dịch ngay bên dưới nội dung gốc trong cùng bong bóng (đường kẻ mảnh, nhãn *"Đã dịch"*, nút *"Ẩn bản dịch"*), chỉ áp dụng tin `TEXT`, cache Room `message_translation` | Gắn liền ngữ cảnh tin nhắn | ✓ |
| Hiện bản dịch trong BottomSheet/Dialog riêng | Tách khỏi bong bóng | |

**User's choice:** Hiện bản dịch ngay trong bong bóng tin nhắn dưới nội dung gốc — (Recommended).

### Câu 15: Chính sách tải gói ngôn ngữ ML Kit (~30MB) lần đầu

| Option | Description | Selected |
|--------|-------------|----------|
| Tải cả Wi-Fi lẫn di động có thanh tiến độ | Tự tải trên mọi mạng | |
| Chỉ cho tải qua Wi-Fi (`requireWifi`) | Chặn tải khi dùng 4G | |
| Free-text / Chi tiết hóa UX tải gói ngôn ngữ | Tự tải qua Wi-Fi, hỏi xác nhận nếu đang dùng dữ liệu di động, hiển thị *"Đang tải gói ngôn ngữ…"* + chỉ báo chờ vô định (không % ước lượng), lỗi/offline giữ tin gốc và cho thử lại | ✓ |

**User's choice:** Tải gói ngôn ngữ ML Kit khi người dùng yêu cầu dịch lần đầu. Cho phép tải qua Wi‑Fi và hỏi xác nhận nếu đang dùng dữ liệu di động. Hiển thị “Đang tải gói ngôn ngữ…” cùng chỉ báo chờ, không hiển thị phần trăm ước lượng. Nếu chưa có mạng hoặc tải thất bại, giữ tin gốc và cho thử lại. Khi gói cần thiết đã tải xong, dịch Anh–Việt trực tiếp trên thiết bị và lưu kết quả vào Room theo tin nhắn và ngôn ngữ đích.

---

## the agent's Discretion

- Bố cục chi tiết thẻ ảnh/video/voice/file/location, thiết kế cụ thể nút đính kèm trên khung soạn tin `ChatRoomFragment`, cấu trúc thư mục con bên trong `MEDIA_STORAGE_DIR`, và chi tiết màn hình xem ảnh toàn màn hình / trình phát `Media3 ExoPlayer`.

## Deferred Ideas

- Thông báo đẩy FCM khi ứng dụng bị force-stop hoàn toàn (`FEAT-push-fcm`) → thuộc **V2**.
- Cuộc gọi video 1-1 và gọi nhóm (`FEAT-call-video`) → thuộc **V2**.
- Nhúng bản đồ `SupportMapFragment` trực tiếp trong app (Google Maps SDK API key) → không dùng trong V1.
- Kênh thông báo riêng cho lời mời kết bạn → giữ trong màn Danh bạ ở V1.
