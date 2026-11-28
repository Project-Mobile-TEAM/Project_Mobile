# Hướng dẫn kiểm thử thủ công Plan 08-03

Đối chiếu [plan gốc](../../../08-03-PLAN.md) và [báo cáo lần trước](reports/08-03-tv3-report.md). Tên nút tiếng Anh trong ngoặc dùng khi app đang để English.

## Chuẩn bị

- **Máy A và Máy B:** Hai thiết bị hoặc máy ảo, mỗi máy dùng một tài khoản phụ. Ghi số điện thoại của A và B. Bật **Cho phép tìm qua SĐT** trên B. Ban đầu A và B chưa kết bạn, chưa chặn nhau và không có lời mời đang chờ. Nếu dùng lại dữ liệu lượt trước, hủy kết bạn trước khi chạy ca 02.
- **Tài khoản C:** Một tài khoản phụ tìm được bằng SĐT, ban đầu chưa kết bạn với A. Có thiết bị thứ ba thì dùng C để thử gửi lời mời khi bị chặn và thử gọi bận.
- Backend Spring Boot chạy cổng 8080, **GET /api/health/ready** trả **UP / CONNECTED**. Dùng APK Debug kết nối backend thật với **-PuseFakeData=false**. Nếu thử hai thiết bị trên hai mạng khác nhau, xem cấu hình Coturn trong plan.
- Cấp quyền **Micro** trên hai máy và **Thông báo** trên máy nhận cuộc gọi. Bật tự động xoay màn hình. Dùng tài khoản phụ cho các thao tác chặn và hủy kết bạn.
- Chạy theo thứ tự 01 → 08. Nếu danh sách chưa cập nhật, kéo làm mới một lần và ghi rõ kết quả trước và sau khi làm mới.

## TC-8.3.01 — Tìm chính xác theo SĐT

Trên A, vào **Bạn bè → Tìm qua SĐT** (*Friends → Find by phone*). Xóa giá trị cũ trước mỗi lần nhập rồi bấm **Tìm** (*Search*).

| Nhập gì | Kết quả phải thấy |
|---|---|
| Số thiếu chữ số, ví dụ 0912 | Báo số không hợp lệ, không hiện tài khoản. |
| Một số đúng định dạng nhưng chưa đăng ký trong môi trường test | Báo không tìm thấy tài khoản. |
| Số của chính A | Không hiện nút gửi lời mời cho chính mình. |
| Số B ở dạng nội địa 09… | Hiện đúng hồ sơ B. |
| Cùng số B ở dạng +84…, bỏ số 0 đầu | Hiện đúng cùng hồ sơ và số E.164. |

Chụp kết quả tìm được và ít nhất một trường hợp bị từ chối.

## TC-8.3.02 — Gửi, hủy, từ chối và chấp nhận lời mời

1. A tìm B và bấm **Thêm bạn** (*Add friend*). Vào **Bạn bè → Lời mời kết bạn** trên hai máy. A thấy **Đã gửi** (*Sent*), B thấy **Đã nhận** (*Received*). Có thể kéo làm mới nếu chưa thấy.
2. Khi lời mời còn chờ, A tìm lại B và B tìm A. Nếu còn nút gửi, thử gửi lại và thử gửi ngược. Phải chỉ có một lời mời đang chờ. Nếu nút gửi đã bị ẩn hoặc đổi thành thao tác xử lý lời mời hiện có, ghi “UI ngăn gửi trùng/ngược”; chưa xác minh mã lỗi backend bằng thao tác UI này.
3. A bấm **Hủy lời mời** (*Cancel request*). Lời mời phải biến khỏi cả hai phía.
4. A gửi lại. B bấm **Từ chối** (*Decline*, nút có thể hiện **Delete**). Lời mời phải biến mất và hai người chưa có trong danh bạ của nhau.
5. A gửi lần nữa. B bấm **Chấp nhận** (*Accept*). Về **Bạn bè** và kiểm tra cả hai thấy người kia. Giữ quan hệ này cho ca 03. Chụp màn lời mời đi, đến và danh bạ sau khi chấp nhận.

## TC-8.3.03 — Danh bạ offline và mở phòng chat

1. Khi có mạng, trên A kéo làm mới **Bạn bè** và xác nhận có B. Có thể chuẩn bị trước phòng A–B từ tab **Tin nhắn** để đối chiếu khi offline.
2. Tắt cả Wi-Fi lẫn dữ liệu di động trên A. Buộc dừng UTEChat rồi mở lại. Nếu hiện **Tiếp tục offline** (*Continue offline*), bấm nút đó.
3. Vào **Bạn bè**. Xác nhận vẫn thấy B rồi chạm trực tiếp vào **tên B**. Kỳ vọng màn tiếp theo là phòng chat riêng A–B.
4. Nếu vẫn ở Danh bạ, chụp màn hình sau khi chạm. Bật lại mạng rồi thử chạm tên một lần nữa. Lượt trước danh bạ giữ được dữ liệu nhưng chạm tên không mở chat, cả khi có mạng. Để mạng bật cho các ca tiếp theo.

## TC-8.3.04 — Chặn bạn và chặn người lạ

1. Trên A, vào **Bạn bè**, mở **Thao tác** (*Actions*) của B, chọn **Chặn** (*Block*) rồi xác nhận. B phải rời danh sách bạn và xuất hiện trong **Người đã chặn** (*Blocked people*).
2. Trên A, tìm số của C. Từ kết quả, bấm **Thao tác → Chặn** và xác nhận. C cũng phải có trong danh sách chặn.
3. Qua phòng chat A–B đã có, thử gửi một tin từ B sang A. Nếu có thiết bị đăng nhập C, thử gửi lời mời từ C sang A. Tin không được chuyển tới A và lời mời không được tạo. Ghi rõ lỗi hiển thị hoặc việc nút gửi bị khóa/ẩn. **BLOCKED** là mã backend trong plan.
4. Chụp kết quả tìm C và danh sách chặn. Giữ hai mục chặn cho ca 05.

## TC-8.3.05 — Bỏ chặn, hủy kết bạn hai chiều và kết bạn lại

1. Trên A, mở **Người đã chặn**, chọn **Bỏ chặn** (*Unblock*) C. C phải biến khỏi danh sách chặn và vẫn không vào danh bạ bạn bè.
2. Bỏ chặn B. B phải biến khỏi danh sách chặn và xuất hiện lại trong danh bạ của A.
3. Trên A, mở **Bạn bè → Thao tác của B → Hủy kết bạn** (*Remove friend*) rồi xác nhận. Mở hoặc làm mới danh bạ trên cả A và B. Mỗi bên phải không còn thấy người kia.
4. Gửi lại lời mời và chấp nhận trên máy kia. Kiểm tra cả hai danh bạ có người kia trở lại. Chụp trước và sau khi kết bạn lại.

## TC-8.3.06 — Gọi người lạ và người bị chặn

1. **Người lạ:** Trên A mở phòng riêng đã có với C, khi hai người chưa kết bạn và chưa chặn. Nếu chưa có phòng phù hợp, tạm hủy kết bạn A–B và mở phòng A–B cũ từ **Tin nhắn**. Bấm nút gọi thoại. Máy kia không được đổ chuông. App phải báo cần kết bạn để gọi, ví dụ **“Add as friend to make voice calls”**. Mã trong plan là **CONTACT_REQUIRED**.
2. **Người bị chặn:** A chặn người vừa thử rồi mở lại phòng cũ từ **Tin nhắn**. Bấm gọi. Máy kia không được đổ chuông. Ghi thông báo, ví dụ **“Unable to call this user”**. Mã trong plan là **BLOCKED**.
3. Chụp hai thông báo. Bỏ chặn và xác nhận A, B là bạn trước ca 07. Nếu đã hủy kết bạn ở bước 1, gửi và chấp nhận lời mời mới.

## TC-8.3.07 — Gọi thoại, điều khiển, xoay và lịch sử

1. Trong phòng A–B, đếm số mục cuộc gọi hiện có. A bấm gọi, B bấm **Trả lời** (*Accept*). Hai máy phải chuyển sang đang gọi và đồng hồ chạy.
2. Nói thử hai chiều nếu thiết bị cho phép. Bấm **Tắt micro → Bật micro** (*Mute → Unmute*), rồi bật và tắt **Loa ngoài** (*Speaker*). Kiểm tra nút phản hồi. Nếu nghe được âm thanh, kiểm tra bên kia ngừng nghe khi tắt micro, nghe lại khi bật và loa chuyển đúng đường phát.
3. Kéo thanh thông báo để kiểm tra thông báo cuộc gọi đang chạy. Quay lại màn gọi rồi xoay ngang và dọc. Cuộc gọi phải giữ kết nối và đồng hồ tiếp tục.
4. Bấm **Kết thúc** (*End*), rồi **Đóng** (*Close*) nếu có. Xem chat ở cả hai máy. Phải thêm **đúng một mục CALL** cho lượt gọi và mục đó phải **hiện thời lượng**.
5. Chụp màn đang gọi, màn xoay và mục lịch sử. Lượt trước mục mới chỉ hiện **“Voice call”**, thiếu thời lượng dù đồng hồ chạy trong cuộc gọi.

## TC-8.3.08 — Từ chối, cuộc gọi nhỡ và gọi bận

1. **Từ chối:** A gọi, B bấm **Từ chối** (*Reject*). Hai máy phải hết đổ chuông và hiện trạng thái từ chối. Bấm **Đóng** nếu có. Chat phải thêm đúng một mục cuộc gọi cho lượt này.
2. **Nhỡ:** A gọi lại. B có thể đưa app xuống nền nhưng để app chạy và không nghe khoảng 45 giây. Hai máy phải hết đổ chuông và chat có đúng một mục mới cho lượt nhỡ. Trên B, kéo thanh thông báo, tìm **Cuộc gọi thoại nhỡ** (*Missed voice call*) và chụp lại. Chạm thông báo phải mở đúng hội thoại A–B. Nếu Android cho xem chi tiết thông báo, kiểm tra kênh cuộc gọi **calls**.
3. **Bận:** Nếu có máy thứ ba, dùng C đã kết bạn với B và không bị chặn. Khi A và B đang gọi nhau, C mở chat với B và bấm gọi. C phải được báo bận (**CALL_BUSY** trong plan), B không đổ thêm chuông. Nếu chỉ có hai máy, ghi “chưa kiểm UI báo bận” và lý do.
4. Lưu ảnh từ chối, hết chuông, thông báo nhỡ, hội thoại mở từ thông báo và trạng thái bận nếu kiểm được.

## Ghi kết quả

Ghi vào [báo cáo TV3](reports/08-03-tv3-report.md) theo từng mã ca. Dùng **PASS**, **FAIL** hoặc **CHƯA KIỂM THỬ ĐẦY ĐỦ**. Với mỗi ca không đạt, ghi thiết bị, bước cuối đã bấm, kết quả mong đợi, kết quả thực tế và tên ảnh. Với phần chưa chạy được, ghi điều kiện còn thiếu. Tránh đưa mật khẩu, OTP hoặc dữ liệu tài khoản chính vào ảnh.