# UTEChat — Quy tắc bảo mật khi triển khai V1

> **ACCEPTED — 2026-11-11.** Cụ thể hóa ADR-017/018/019 cho tất cả các phase tiếp theo. Đây là yêu cầu phải triển khai và nghiệm thu, không phải xác nhận hệ thống hiện đã cấu hình đầy đủ. Không mở rộng phạm vi V1 sang E2EE, SMS thật hoặc FCM.

## 1. Kênh truyền và địa chỉ dịch vụ

- Bản Release kết nối REST/media bằng **HTTPS**, WebSocket bằng **WSS**; TLS 1.2/1.3, chứng chỉ CA hợp lệ và đúng hostname. Không dùng TrustAll, bỏ kiểm hostname hoặc chấp nhận chứng chỉ lỗi. V1 không yêu cầu certificate pinning.
- HTTP chỉ dùng trong Debug với backend phát triển nội bộ (localhost, emulator, IP LAN được cấu hình). Không gửi mật khẩu/OTP/JWT thật qua HTTP trên Internet; redirect HTTP sang HTTPS không bảo vệ request đã gửi bằng HTTP.
- Một cấu hình gốc `BACKEND_BASE_URL` có dạng `https://<api-domain>/`, kết thúc bằng `/`; Retrofit dùng đường dẫn `api/...`. WebSocket lấy cùng host/port, đổi scheme sang `wss` và dùng `/ws` (Debug HTTP tương ứng `ws`). Không hardcode IP/domain ở Fragment, Repository hoặc từng API.
- `mediaUrl` giữ dạng đường dẫn tương đối `/api/media/{attachmentId}`, được ghép với origin backend; không tải URL tùy ý rồi gắn Bearer. Backend không trả link chứa IP nội bộ.
- Release phải tắt Fake và dùng URL HTTPS. Cần kiểm tra cấu hình URL khi build/phát hành; không coi việc build thành công là bằng chứng kết nối production đã đúng.

## 2. Ranh giới triển khai

- Nginx nhận HTTPS/WSS trên IP chính, cổng 443; proxy `/api` tới Spring Boot và chuyển tiếp Upgrade/Connection cho `/ws`. Timeout proxy phải hỗ trợ heartbeat 25 giây của WebSocket.
- Spring Boot trên cùng EC2 chỉ lắng nghe loopback cho cổng 8080; firewall/security group không mở cổng này ra Internet. HTTP Nginx → Spring qua loopback là kết nối nội bộ được chấp nhận.
- Header forwarded chỉ được tin từ proxy do nhóm kiểm soát; Nginx phải ghi đè thông tin scheme/host/IP được dùng, backend không tin giá trị do client tự khai.
- Profile `prod` phải được chọn rõ khi host; không giữ profile mặc định `dev`. Endpoint `/api/demo/sms` không tồn tại ở prod, CORS wildcard dev không áp dụng ở prod. Android native không cần CORS để xác thực.
- PostgreSQL vẫn kết nối từ backend tới Supabase, không từ APK. Production dùng TLS xác minh hostname/chứng chỉ (`sslmode=verify-full` với CA tin cậy); Transaction Pooler 6543 và `prepareThreshold=0` giữ nguyên. Migration dùng kết nối riêng theo hướng dẫn Supabase hiện có.
- Coturn dùng secondary IP riêng cho TCP/TLS 443 và UDP 3478/443 theo ADR-013, không tranh IP:443 với Nginx. `turns:<turn-domain>:443?transport=tcp` cần chứng chỉ đúng hostname; credential có hạn do backend cấp, shared secret chỉ ở server. Kiểm thử relay trên mạng demo; TURNS không bảo đảm vượt mọi chính sách tường lửa.
- WSS bảo vệ signaling; âm thanh WebRTC dùng DTLS-SRTP. E2EE của tin nhắn vẫn nằm ngoài V1.

## 3. Token, quyền truy cập và bí mật

- REST có bảo vệ và handshake WebSocket dùng `Authorization: Bearer <token>`. Với Android OkHttp, kết nối `wss://<api-domain>/ws` kèm header; **không đặt JWT vào query URL** hoặc WebSocket subprotocol.
- Backend kiểm chữ ký, hạn dùng, thiết bị và thu hồi theo ADR-018; lấy actor từ JWT. Mỗi thao tác REST/WS vẫn phải kiểm quyền thành viên, block và chính sách liên hệ. Socket phải đóng khi token hết hạn hoặc bị thu hồi; handshake hợp lệ không cấp quyền vĩnh viễn.
- JWT chỉ lưu trong kho bảo mật phía Android theo Phase 3, không trong Room, URL hay log. Không log password, OTP, Authorization hoặc secret ở cả app, backend và proxy; Release tắt HTTP debug logging.
- DB credential, khóa ký JWT, Supabase service-role và TURN shared secret chỉ ở biến môi trường/secret của backend hoặc CI; không commit hoặc đóng gói trong APK. Cấu hình URL công khai không phải secret.
- Media được tải qua endpoint kiểm quyền; Nginx không public thư mục upload bằng alias/static URL để bỏ qua backend. Giữ cách tải có Bearer rồi mở file cục bộ bằng FileProvider, không thêm query ticket hay đưa token cho app ngoài.

## 4. Áp dụng vào các phase hiện có

| Mốc | Yêu cầu phải mang vào phần triển khai |
|---|---|
| Phase 3 — Auth/Profile | Lưu token an toàn, Bearer, thu hồi thiết bị, tách dev/prod, không lộ credential qua log |
| Phase 4 — Contacts | Kiểm quyền tại server, không chỉ ẩn nút trên UI |
| Phase 5 — Messaging | WSS cùng origin, Bearer handshake, hết hạn/thu hồi socket, quyền trên từng loại sự kiện |
| Phase 6 — Media/Calls | Media có kiểm quyền, FileProvider, TURN credential có hạn, TURNS và thử relay thật |
| Phase 7 — Release | Kiểm URL HTTPS của Release, cấu hình Nginx/prod/firewall/chứng chỉ và nghiệm thu trên host |

Không đợi Phase 7 mới sửa hợp đồng bảo mật. Các phase chức năng phải tuân thủ từ khi viết API/client; Phase 7 tích hợp và kiểm tra cấu hình triển khai.

## 5. Điều kiện trước khi phát hành bản hosted

- Release dùng domain HTTPS đúng cấu hình, Fake tắt; REST và WSS hoạt động trên ít nhất hai thiết bị. Chứng chỉ hợp lệ và cơ chế gia hạn đã được cấu hình/kiểm tra.
- Token thiếu/sai/hết hạn/bị thu hồi bị từ chối ở REST và WS; thử thu hồi khi socket đang mở. Người không có quyền không tải được media hoặc thao tác trên hội thoại.
- Cổng Spring 8080 không truy cập được từ Internet; prod không có demo SMS; cấu hình/log/APK không chứa secret. Kiểm tra HTTPS không bị hạ cấp và không gửi credential tới HTTP.
- Thử cuộc gọi qua TURN relay, gồm TURNS TCP 443; ghi nhận giới hạn mạng thực tế và phương án demo dự phòng.

**Hiện trạng sau Phase 2:** Release đã chặn cleartext bằng Network Security Config và tắt Fake; Debug cho HTTP nội bộ. Chưa có gate build kiểm URL HTTPS, cấu hình prod/Nginx/chứng chỉ và kiểm thử hosted; Auth, WebSocket và Media nghiệp vụ chưa được triển khai. Không xem tài liệu này là bằng chứng các mục đó đã hoàn tất.
