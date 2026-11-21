# Báo cáo làm việc: Giao diện chat Phase 5 (05-07, 05-08, 05-09)

Ngày: 21/11/2026 · Nhánh: `feature/phase-5-core`

## 1. Tóm tắt

Phase 5 có sẵn phần nền (backend, Room, WebSocket, hàng đợi gửi offline) tới plan 05-06. Đợt này làm toàn bộ giao diện chat còn thiếu:

| Plan | Nội dung | Trạng thái |
|---|---|---|
| 05-07 | Danh sách chat, banner mất kết nối, tìm kiếm toàn cục, màn Tin nhắn mới | Xong |
| 05-08 | Phòng chat: 6 kiểu tin, trạng thái gửi, gửi lại tin lỗi, tìm trong phòng, báo đã đọc | Xong |
| 05-09 | Tạo nhóm, thông tin nhóm, chuyển quyền quản trị khi rời nhóm, test ViewModel | Xong (trừ test trên máy thật, xem mục 6) |
| Thêm | Icon app ngoài màn hình chính đổi sang logo UTE | Xong |

## 2. Thiết kế Figma (trước khi code)

File: *UTEChat – UI/UX Design*. Phần lớn màn chat đã có sẵn ("Tin nhắn", "Chat 1-1", "Chat nhóm", "Tìm tin nhắn", "Tạo nhóm", "Thông tin nhóm"). Vẽ thêm các phần plan cần mà Figma còn thiếu (bản light):

- Trang **Trang chủ**: `L · 7 · Tin nhắn mới`, `L · 8 · Mất kết nối`, `L · 9 · Tìm kiếm toàn cục`, `L · 10 · Tìm không có kết quả`
- Trang **Chat nâng cao**: `L · 8 · Tin hệ thống & tin không hỗ trợ`
- Trang **Danh bạ & Nhóm**: `L · 11 · Chuyển quyền quản trị`

Rà soát theo checklist UI/UX (vùng chạm 48dp, tương phản chữ ≥ 4.5:1) và đã sửa:
- Nút back và lựa chọn trong hộp thoại nâng lên 48dp; thêm nút xóa từ khóa trong ô tìm kiếm.
- 24 nút hộp thoại trên 4 trang nâng từ 40dp lên 48dp.
- Giờ gửi trong bong bóng chat đậm hơn (light: `#6B6F76`; dark: `#9A9EA5`, khoảng 5.9:1).

## 3. Chức năng đã làm

### 05-07: Danh sách chat và Tin nhắn mới
- Danh sách hội thoại: tin chưa đọc (tên đậm, huy hiệu đỏ), biểu tượng tắt thông báo, xem trước tin cuối ("Bạn: …", "Tên: …" trong nhóm, nhãn "Hình ảnh", "Tệp tin"…, câu tin hệ thống).
- Banner "Mất kết nối. Đang thử kết nối lại…" dưới ô tìm kiếm, tự ẩn khi đã kết nối.
- Tìm kiếm toàn cục chia 2 mục "Hội thoại" và "Tin nhắn trên thiết bị", tìm không dấu, tô đậm phần khớp, có màn "Không tìm thấy".
- Tin nhắn mới: một ô tìm cả tên bạn bè và số điện thoại; tra số người lạ qua `ContactsRepository` của Phase 4; người chỉ nhận tin từ bạn bè thì hiện nút **Kết bạn**.
- `nav_chat.xml` có deep link `utechat://chat/direct/{peerUserId}` và `ChatNavigation.openDirectChat()` để màn Danh bạ mở chat 1-1.
- `MainActivity` mở WebSocket và đồng bộ khi vào các tab chính.

### 05-08: Phòng chat
- 6 kiểu hiển thị: tin chữ của mình/người khác, tin hệ thống, thẻ tóm tắt ảnh/thoại/video/file/vị trí/cuộc gọi (không mở được gì, để phase sau), thẻ "Tin nhắn không hỗ trợ hiển thị" cho kiểu lạ.
- Mốc ngày "Hôm nay / Hôm qua", trạng thái Đang gửi / Đã gửi / Đã nhận / Đã xem.
- Chạm tin lỗi: hộp thoại **Gửi lại** hoặc **Xóa tin lỗi**.
- Tìm trong phòng có bộ đếm X/Y; mở từ tìm kiếm toàn cục thì cuộn tới đúng tin, kể cả tin cũ hơn 50 tin gần nhất.
- Báo đã đọc chỉ khi màn đang mở và chỉ tới tin thật sự hiện trên màn hình.
- Banner bị chặn / chỉ nhận tin từ bạn bè (có nút **Kết bạn** ngay trong phòng), thanh chỉ đọc khi đã rời nhóm, "Đang nhập…", cuộn lên để tải tin cũ.

### 05-09: Nhóm
- Tạo nhóm: tên 1–50 ký tự, chọn 2–49 bạn, tạo xong mở phòng chat nhóm.
- Thông tin nhóm: đổi tên (admin), tắt thông báo cá nhân, thêm thành viên (admin), đặt/bỏ quản trị viên và xóa khỏi nhóm (admin).
- Rời nhóm theo 3 trường hợp: admin duy nhất còn người khác phải chọn người kế nhiệm; còn admin khác hoặc là người cuối thì chỉ xác nhận; server báo cần chọn admin thì hộp chọn hiện lại.

### Icon app
- Icon thích ứng: logo UTE trên nền trắng, nằm trong vùng an toàn để launcher cắt tròn/vuông không mất mép. Xóa các icon robot `.webp` cũ (app chạy từ Android 8, không còn dùng).

## 4. Kiểm thử

| Bộ kiểm tra | Kết quả |
|---|---|
| Backend Maven (EmbeddedPostgres) | 298 test, 0 lỗi |
| Android unit test (JVM) | 236 test, 0 lỗi |
| Android lint | 0 lỗi |
| Build APK debug | Thành công |

Test mới: `ChatTextTest` (định dạng tin hệ thống, tô từ khóa, lọc tên, nhận diện SĐT) và `ChatViewModelTest` (giữ trạng thái khi xoay màn hình, nới cửa sổ khi nhảy tới tin cũ, báo đã đọc, banner chính sách, nút Kết bạn, chỉ đọc, quy tắc tạo nhóm, chuyển quyền admin, 6 kiểu tin).

## 5. Khác với plan

- Màn Tin nhắn mới dùng một ô tìm chung theo Figma, thay vì ô SĐT riêng + nút Tìm.
- Không tạo `menu_chat_list.xml` và `item_search_message_result.xml`: tìm kiếm và nút bút đã nằm trên màn, kết quả tin nhắn dùng lại layout dòng hội thoại.
- 6 kiểu tin dùng chung 3 layout (`item_message_out`, `item_message_in`, `item_message_system`).
- Khung soạn tin chỉ có chữ và nút gửi (plan yêu cầu bỏ các nút đính kèm/camera/mic của Figma).
- Hộp chọn admin mới không có dòng giải thích như Figma (hộp thoại danh sách của Material không hiện được cả hai).
- Các file `05-0x-SUMMARY.md` chưa tạo vì thư mục `.planning` nằm ở nhánh `planning`; báo cáo này thay thế.

## 6. Chưa làm / cần làm tiếp

- **Test instrumentation** `ChatNavigationAndSearchUiTest` và **E2E 2 tài khoản** trên backend thật: máy chưa có emulator/điện thoại. Theo plan 05-09, Phase 5 chỉ coi là xong khi chạy được 2 phần này.
- Bản dark của 6 màn Figma vẽ thêm.

## 7. Commit

- `9e0926f` 05-07 + icon logo UTE
- `e244c2f` 05-08 + 05-09 + `ChatViewModelTest`
- Commit cuối: nút Kết bạn trong phòng chat + báo cáo này
