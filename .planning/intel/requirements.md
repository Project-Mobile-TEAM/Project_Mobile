# Synthesized Requirements

> [!NOTE]
> **Historical Snapshot:** File này là snapshot trích xuất 18 yêu cầu thô tại thời điểm Ingest Docs ban đầu (2026-11-10). Nguồn chân lý hiện hành là danh mục 15 yêu cầu v1 bám Rubric tại [.planning/REQUIREMENTS.md](file:///C:/Users/Admin/Desktop/Project-Mobile/.planning/REQUIREMENTS.md).

## REQ-auth-registration
- source: docs/FLOWS.md
- description: Đăng ký tài khoản người dùng bằng số điện thoại chuẩn hoá E.164 và xác thực OTP demo.
- acceptance: Người dùng nhập số điện thoại; nếu số đã tồn tại trả về 409 PHONE_TAKEN; nếu số mới server tạo OTP 6 số (hết hạn 5 phút, lưu hash); xác thực OTP thành công cấp registrationToken có thời hạn 10 phút; người dùng thiết lập tên hiển thị (1-50 ký tự) và mật khẩu (>= 8 ký tự); hệ thống tạo tài khoản và tự động đánh dấu thiết bị hiện tại là tin cậy.
- scope: authentication, registration, OTP

## REQ-auth-login-password
- source: docs/FLOWS.md
- description: Đăng nhập bằng số điện thoại và mật khẩu với cơ chế bảo vệ chống brute-force và kiểm tra độ tin cậy của thiết bị.
- acceptance: Đăng nhập đúng trên thiết bị tin cậy trả về JWT token ngay; đăng nhập trên thiết bị lạ kích hoạt quy trình xác thực bổ sung (OTP hoặc TOTP 2FA nếu đã bật); sai mật khẩu 5 lần cho một thiết bị khóa 15 phút, tổng 20 lần sai mỗi giờ khóa số 1 giờ; phản hồi lỗi chung 401 không làm lộ sự tồn tại của số điện thoại.
- scope: authentication, login, security

## REQ-auth-login-otp
- source: docs/FLOWS.md
- description: Đăng nhập không cần mật khẩu thông qua mã OTP gửi đến số điện thoại.
- acceptance: Người dùng yêu cầu OTP đăng nhập; server gửi OTP và kiểm tra giới hạn tần suất; xác thực OTP thành công trên thiết bị tin cậy cấp JWT ngay; trên thiết bị lạ chuyển tiếp tới bước nhận diện bạn bè (nếu đủ điều kiện).
- scope: authentication, login-otp, security

## REQ-auth-friend-challenge
- source: docs/FLOWS.md
- description: Thử thách nhận diện bạn bè khi đăng nhập trên thiết bị chưa tin cậy.
- acceptance: Nếu tài khoản có >= 3 bạn bè trong danh bạ, hiển thị danh sách 15 người (3 bạn thật trộn ngẫu nhiên với 12 người mồi); người dùng phải chọn đúng cả 3 bạn bè; cho phép tối đa 3 lần thử, mỗi lần thất bại đổi danh sách mới hiệu lực 2 phút; sai quá 3 lần khóa thử thách 15 phút và phát thông báo bảo mật đến các thiết bị tin cậy.
- scope: authentication, challenge, security

## REQ-auth-totp-2fa
- source: docs/FLOWS.md
- description: Bật, tắt và xác thực bảo vệ hai lớp (2FA) bằng TOTP chuẩn RFC 6238 và mã dự phòng.
- acceptance: Người dùng có thể bật 2FA trong cài đặt bằng cách quét mã QR hoặc nhập secret key; hệ thống phát sinh 8 mã dự phòng dùng một lần; khi đăng nhập bằng mật khẩu trên thiết bị lạ, hệ thống yêu cầu mã TOTP 6 số (chu kỳ 30s) hoặc 1 mã dự phòng hợp lệ; tắt 2FA yêu cầu xác thực lại mật khẩu.
- scope: 2FA, TOTP, security

## REQ-auth-session-devices
- source: docs/FLOWS.md
- description: Quản lý đa thiết bị đăng nhập đồng thời và thu hồi quyền truy cập từ xa.
- acceptance: Người dùng xem được danh sách thiết bị đang hoạt động (tên thiết bị, IP, thời điểm truy cập gần nhất, trạng thái thiết bị hiện tại/tin cậy); người dùng có thể xóa/thu hồi thiết bị khác; khi thu hồi thiết bị hoặc đổi mật khẩu, server cập nhật token_valid_after khiến token cũ của thiết bị bị vô hiệu lập tức và đóng kết nối WebSocket tương ứng.
- scope: device-management, session, token-revocation

## REQ-contacts-management
- source: docs/FLOWS.md
- description: Quản lý danh bạ bạn bè và tìm kiếm người dùng mới bằng số điện thoại chính xác.
- acceptance: Tìm kiếm người dùng mới chỉ cho phép tìm chính xác số điện thoại (E.164), không hỗ trợ tìm kiếm mờ hoặc ký tự đại diện để tránh rà quét; người dùng có thể gửi yêu cầu kết bạn, chấp nhận hoặc từ chối; chặn người dùng ngăn chặn đối phương gửi tin nhắn hoặc cuộc gọi.
- scope: contacts, friendships, block-user

## REQ-messaging-realtime-send-receive
- source: docs/FLOWS.md
- description: Gửi và nhận tin nhắn văn bản thời gian thực trong hội thoại 1-1 và hội thoại nhóm.
- acceptance: Màn hình đọc dữ liệu hoàn toàn từ Room SQLite; tin nhắn mới gửi qua REST API kèm clientId (UUID tạo tại client) chống trùng lặp; server lưu tin và phát sự kiện qua WebSocket tới các thành viên hội thoại; app nhận sự kiện WebSocket và cập nhật vào Room.
- scope: messaging, realtime, WebSocket, Room

## REQ-messaging-offline-first
- source: docs/FLOWS.md
- description: Hỗ trợ gửi tin nhắn và xem lịch sử khi mất kết nối mạng (Offline-first).
- acceptance: Khi mất mạng, tin nhắn tạo trong Room với trạng thái SENDING; WorkManager quản lý hàng đợi gửi tuần tự (APPEND_OR_REPLACE, NetworkType.CONNECTED, backoff 10s, tối đa 3 lần thử); khi có mạng trở lại tin nhắn tự động được đẩy lên server; nếu thất bại vĩnh viễn hiển thị trạng thái FAILED kèm tùy chọn gửi lại.
- scope: offline-first, WorkManager, queue

## REQ-messaging-delivery-read-status
- source: docs/FLOWS.md
- description: Hiển thị trạng thái tin nhắn (đã gửi, đã nhận, đã đọc) và chỉ báo đang nhập (typing indicator).
- acceptance: Sử dụng cơ chế watermark mốc tin nhắn (last_delivered_message_id, last_read_message_id); tin nhắn được coi là đã đọc khi mốc của người nhận >= message id; trong chat nhóm hiển thị trạng thái đã đọc khi tất cả thành viên khác đã xem; trạng thái đang nhập truyền qua WebSocket và tự động tắt sau khoảng thời gian ngưng gõ.
- scope: message-status, typing-indicator, read-receipts

## REQ-messaging-group-chat
- source: docs/FLOWS.md
- description: Quản lý hội thoại nhóm: tạo nhóm, thêm/xóa thành viên, rời nhóm và phân quyền quản trị viên.
- acceptance: Tạo nhóm chat từ danh sách bạn bè; quản trị viên có quyền thêm thành viên mới, xóa thành viên, đổi tên nhóm và chuyển quyền quản trị; thành viên mới vào nhóm không được xem lịch sử tin nhắn trước thời điểm gia nhập; thành viên rời nhóm hoặc bị xóa mất quyền truy cập hội thoại.
- scope: group-chat, conversation-members, RBAC

## REQ-messaging-media-attachments
- source: docs/FLOWS.md
- description: Gửi và nhận các loại tin nhắn đa phương tiện: hình ảnh, ghi âm giọng nói (voice), video, tệp tin đính kèm và vị trí.
- acceptance: Tải tệp lên server qua endpoint multipart POST /api/media; server kiểm tra định dạng MIME hợp lệ và giới hạn kích thước (ảnh <= 10MB, voice <= 10MB/5phút, video <= 50MB, file <= 25MB); server trả về mediaUrl được bảo vệ quyền truy cập; client nén ảnh trước khi tải lên; gửi tin nhắn chứa mediaUrl và metadata đính kèm.
- scope: media-attachments, file-upload

## REQ-messaging-search
- source: docs/FLOWS.md
- description: Tìm kiếm tin nhắn nội bộ trong hội thoại hoặc toàn bộ lịch sử trò chuyện.
- acceptance: Tìm kiếm nội dung tin nhắn chạy hoàn toàn trên thiết bị thông qua bảng ảo Room FTS4; kết quả hiển thị khớp từ khóa nhanh chóng mà không cần kết nối mạng.
- scope: search, Room-FTS4

## REQ-voice-call-webrtc
- source: docs/FLOWS.md
- description: Thực hiện cuộc gọi thoại 1-1 thời gian thực trong ứng dụng bằng giao thức WebRTC.
- acceptance: Báo hiệu kết nối cuộc gọi (SDP Offer/Answer, ICE Candidate) truyền qua WebSocket; server cấp thông tin xác thực STUN/TURN (coturn) có thời hạn qua HMAC; quản lý các trạng thái cuộc gọi RINGING, ACTIVE, ENDED; cuộc gọi không được trả lời tự động chuyển thành MISSED và hiển thị thông báo cuộc gọi nhỡ; ghi bản ghi CALL vào lịch sử trò chuyện.
- scope: WebRTC, VoIP, voice-call

## REQ-ai-assistant
- source: docs/FLOWS.md
- description: Tích hợp trợ lý ảo thông minh 'UTE AI' phản hồi tự động trong giao diện chat.
- acceptance: Hệ thống cung cấp tài khoản bot hệ thống 'UTE AI' (is_bot = true); người dùng nhắn tin trực tiếp với bot; server đóng vai trò trung gian chuyển ngữ cảnh (10 tin gần nhất, tối đa 2000 ký tự) tới mô hình ngôn ngữ lớn (LLM); áp dụng hạn mức 30 yêu cầu/ngày cho mỗi tài khoản.
- scope: AI, chatbot, LLM

## REQ-message-translation
- source: docs/FLOWS.md
- description: Dịch tin nhắn văn bản tức thì giữa tiếng Anh và tiếng Việt ngay trên thiết bị.
- acceptance: Người dùng nhấn giữ tin nhắn để chọn chức năng Dịch; quá trình dịch thực thi hoàn toàn trên thiết bị bằng Google ML Kit Translation mà không gửi dữ liệu ra bên ngoài; kết quả dịch được lưu tạm vào bảng message_translations của Room.
- scope: ML-Kit, translation, on-device

## REQ-notifications-sync
- source: docs/FLOWS.md
- description: Thông báo tin nhắn mới khi ứng dụng ở chế độ nền và đồng bộ dữ liệu sau khi mất kết nối.
- acceptance: Hiển thị thông báo NotificationChannel khi ứng dụng đang mở hoặc chạy ngầm có kết nối WebSocket; khi mất kết nối mạng và kết nối lại, client tự động gọi GET /sync để bù đắp các tin nhắn và sự kiện bị bỏ lỡ; khi app bị dừng hoàn toàn, WorkManager định kỳ gọi /sync để kiểm tra tin mới.
- scope: notifications, sync, background

## REQ-ui-standards
- source: docs/FLOWS.md
- description: Tuân thủ tiêu chuẩn giao diện người dùng: đa ngôn ngữ, chế độ tối và thích ứng xoay màn hình.
- acceptance: Hỗ trợ đầy đủ tiếng Việt và tiếng Anh qua tài nguyên chuỗi (values và values-vi); giao diện hỗ trợ Material 3 DayNight; trạng thái màn hình và bản nháp tin nhắn được bảo toàn qua các sự kiện cấu hình (xoay màn hình) bằng ViewModel và SavedStateHandle; kích thước vùng chạm tối thiểu 48dp.
- scope: UI/UX, localization, dark-mode, screen-orientation
