# Phase 4: Contacts & Friendships - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-17
**Phase:** 04-contacts-friendships
**Areas discussed:** Ngữ nghĩa chặn, 5 trạng thái UI & Điều hướng nav_contacts.xml, Xử lý đồng thời & Transaction, Ranh giới đồng bộ & Mất mạng, Kiểm thử quyền & Tái sử dụng hạ tầng Phase 3

---

## Kiến trúc màn hình & Điều hướng nav_contacts.xml

| Option | Description | Selected |
|--------|-------------|----------|
| Lối tắt trên `ContactsListFragment` (có badge) + các màn con Lời mời, Tìm SĐT, Danh sách chặn trong `nav_contacts.xml` | Danh bạ là màn chính; Lời mời, Tìm SĐT, Danh sách chặn là màn con có nút quay lại; badge chỉ đếm lời mời đến `PENDING` | ✓ |
| Gom 3 tab/chip ngay trên 1 màn `ContactsListFragment` + thanh tìm kiếm SĐT trên cùng | Không tách màn con Lời mời / Danh sách chặn | |
| Tách riêng 4 Fragment ngang hàng trong `nav_contacts.xml` | Không có lối tắt tập trung từ màn chính | |

**User's choice:** Chọn phương án 1. Danh bạ là màn hình chính; Lời mời, Tìm SĐT và Danh sách chặn là các màn hình con trong `nav_contacts.xml`. Badge chỉ đếm lời mời nhận đến còn `PENDING`. Lời mời chia Đã nhận/Đã gửi. Các màn hình con có nút quay lại.
**Notes:** Giao diện cần phân định rõ 5 trạng thái (`STRANGER`, `OUTGOING_PENDING`, `INCOMING_PENDING`, `FRIEND`, `BLOCKED`), mỗi trạng thái chỉ hiển thị đúng nút thao tác tương ứng.

---

## Xử lý đồng thời, Idempotency & Ngữ nghĩa Chặn

| Option | Description | Selected |
|--------|-------------|----------|
| Trả `409 REQUEST_ALREADY_PENDING` khi gửi chéo chiều; kiểm tra người nhận + block + transaction nguyên tử; bấm Accept lặp trả `200` idempotent | Khóa chặt qua `idx_contact_requests_pending_pair` và cập nhật trạng thái nguyên tử; không tạo trùng `contacts`/`sync_events` | ✓ |
| Tự động chấp nhận ghép bạn khi hai người gửi lời mời cho nhau; Accept lặp trả `409` | Có nguy cơ gộp ngoài ý muốn hoặc không idempotent khi mạng lag | |

**User's choice:** Theo phương án 1. Server luôn kiểm tra người thao tác là người nhận lời mời; kiểm tra block trước khi chấp nhận. Accept phải nguyên tử: cập nhật lời mời, tạo hai dòng `contacts` và ghi `sync_events` trong cùng transaction. Request lặp không tạo dữ liệu hoặc sự kiện trùng.
**Notes:** Làm rõ ngữ nghĩa chặn: A chặn B cản tương tác giữa hai bên theo chính sách (trả `403 BLOCKED`), nhưng chỉ lưu bản ghi chặn 1 chiều từ A (không tự tạo bản ghi B chặn A). Bỏ chặn không tự khôi phục quan hệ bạn bè đã hủy kết bạn.

---

## Tích hợp Phase 3, Phase 5 & Hành vi Mất mạng

| Option | Description | Selected |
|--------|-------------|----------|
| Tái sử dụng `SessionManager`/`AuthInterceptor` Phase 3; tạm ẩn nút "Nhắn tin" (Phase 5 bật); offline xem cache, ghi cần mạng | Giữ giao diện sạch (không hiện thông báo kỹ thuật lộ Phase), ghi `sync_events` sẵn cho Phase 5 nối `/ws` & `/sync` | ✓ |
| Hiện nút Nhắn tin dạng thông báo "Sẽ ghép ở Phase 5", tách cơ chế Auth riêng | Làm lộ thông tin kỹ thuật ra UI và trùng lặp hạ tầng xác thực | |

**User's choice:** Phase 4 tái sử dụng `SessionManager`, `AuthInterceptor` và cơ chế dọn phiên của Phase 3 qua `Injection`; không tạo cơ chế JWT riêng. Tạm ẩn nút "Nhắn tin" khi chưa có chức năng thực tế. Phase 5 bổ sung nút và kết nối luồng mở/tạo hội thoại với người được chọn. Không hiển thị thông báo kỹ thuật "Sẽ ghép ở Phase 5" trong giao diện app.
**Notes:** Khi mất mạng vẫn xem được danh bạ đã cache; thao tác kết bạn/chặn cần mạng, báo lỗi và cho thử lại để giữ V1 gọn. Kiểm thử bắt buộc: phân quyền accept/cancel, giới hạn 10 lượt tìm SĐT/phút, và cô lập cache Room khi đăng xuất/đổi tài khoản.

---

## the agent's Discretion
- Bố cục chi tiết thẻ người dùng, thanh lọc bạn bè cục bộ trong danh sách đã tải, dialog xác nhận trước khi hủy kết bạn hoặc chặn theo chuẩn Material 3.
- Bộ đếm rate limit 10 lượt/phút cho `/api/users/lookup` ở backend (tách khỏi `auth_throttles` vốn giới hạn `purpose` cho Auth).

## Deferred Ideas
- Nút "Nhắn tin" mở/tạo hội thoại (`POST /api/conversations/direct`) → Phase 5 (`Core Messaging`).
- Đẩy realtime `/ws` (`contact_request.new`, `contact_request.updated`) & đồng bộ bù `/api/sync` → Phase 5 (`Core Messaging`).
- Hàng đợi WorkManager cho thao tác kết bạn/chặn ngoại tuyến → giữ V1 gọn bằng xem cache offline + thao tác online.
- Nút "Gọi thoại" trên thẻ bạn bè → Phase 6 (`WebRTC Voice Call`).
