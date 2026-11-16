# Phase 4: Contacts & Friendships - Context

**Gathered:** 2026-11-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 4 xây dựng trọn vẹn phân hệ **Danh bạ & Kết bạn (Contacts & Friendships)** cho UTEChat (`REQ-contacts-management`, phụ trách bởi Thành viên 3), bao gồm cả Backend Spring Boot 3 REST API và Android Client UI/Room:
1. **Tìm kiếm người dùng theo SĐT E.164 (`GET /api/users/lookup?phone=`):** Chỉ khớp chính xác số điện thoại E.164, giới hạn 10 lượt/phút theo người dùng, tự động chuẩn hóa `+84...`, không tìm thấy người đã xóa hoặc tắt `discoverable`.
2. **Quy trình Lời mời Kết bạn (`contact_requests`):** Gửi (`POST /api/contact-requests`), xem danh sách Đã nhận / Đã gửi (`GET /api/contact-requests`), chấp nhận (`POST /api/contact-requests/{id}/accept`), từ chối (`POST /api/contact-requests/{id}/reject`), và hủy lời mời (`DELETE /api/contact-requests/{id}`) kèm xử lý tình huống đồng thời và idempotency qua transaction/ràng buộc.
3. **Quản lý Bạn bè & Chặn (`contacts`):** Xem danh sách bạn bè (`GET /api/contacts`), hủy kết bạn hai chiều (`DELETE /api/contacts/{userId}`), chặn một chiều dữ liệu / cản tương tác hai bên (`POST /api/contacts/{userId}/block`), xem danh sách chặn (`GET /api/contacts/blocked`), và bỏ chặn (`DELETE /api/contacts/{userId}/block`).
4. **Đồng bộ Ranh giới & Offline Cache:** Backend ghi `sync_events` trong cùng transaction với mọi thay đổi dữ liệu để sẵn sàng cho Phase 5 nối WebSocket & `/api/sync`. Android đọc cache offline qua Room (`ContactDao`, `UserDao`) và cập nhật cache ngay sau phản hồi REST thành công.

</domain>

<decisions>
## Implementation Decisions

### Ngữ nghĩa Chặn & Quan hệ Danh bạ
- **D-01:** "Chặn" được lưu **một chiều dữ liệu** trong bảng `contacts`: `(owner_id = A, contact_user_id = B, blocked = true)` — không tự động tạo thêm bản ghi B chặn A. Tuy nhiên về mặt nghiệp vụ, quan hệ chặn này **cản tương tác giữa hai bên theo chính sách** (cả A lẫn B đều không thể gửi lời mời kết bạn, nhắn tin hoặc gọi thoại cho nhau -> trả `403 BLOCKED`). Để xử lý trường hợp hai người bạn cùng chặn nhau rồi lần lượt bỏ chặn mà không mất quan hệ bạn bè, bảng `contacts` bổ sung cờ `is_friend` (qua migration mới `V4__contacts_friend_flag.sql`) lưu trạng thái bạn bè độc lập với `blocked`. Khi **bỏ chặn**: nếu `is_friend = true` thì đặt `blocked = false` (khôi phục bạn bè); nếu `is_friend = false` (block-only người lạ hoặc đã hủy kết bạn khi đang bị chặn) thì xóa hẳn dòng khỏi `contacts`; khi **hủy kết bạn** thì xóa các dòng `blocked = false` đồng thời đặt `is_friend = false` trên dòng `blocked = true` còn lại (nếu có), nên **bỏ chặn tuyệt đối không tự khôi phục quan hệ bạn bè đã hủy kết bạn trước đó**. — **Reversibility:** costly — quyết định ngữ nghĩa dữ liệu `contacts` chi phối cả Phase 4 (kết bạn) và Phase 5/6 (nhắn tin, cuộc gọi).

### Trạng thái UI & Kiến trúc Điều hướng (`nav_contacts.xml`)
- **D-02:** Giao diện (trên màn Tìm SĐT, Hồ sơ người dùng và Danh bạ) phân định rành mạch đúng **5 trạng thái quan hệ** và chỉ hiện thao tác hợp lệ cho từng trạng thái:
  1. **Người lạ (`STRANGER`):** Nút `Kết bạn` (+ menu/nút `Chặn`).
  2. **Đã gửi lời mời (`OUTGOING_PENDING`):** Nút `Hủy lời mời` (+ menu/nút `Chặn`).
  3. **Nhận lời mời (`INCOMING_PENDING`):** Nút `Chấp nhận` và `Từ chối` (+ menu/nút `Chặn`).
  4. **Bạn bè (`FRIEND`):** Cung cấp nút `Hủy kết bạn` và `Chặn`. **Tạm ẩn nút "Nhắn tin"** trong Phase 4 khi chưa có màn hội thoại thực tế (tuyệt đối không hiển thị thông báo kỹ thuật kiểu "Sẽ ghép ở Phase 5" trên app); Phase 5 sẽ bật nút và nối luồng mở/tạo hội thoại.
  5. **Đang chặn (`BLOCKED`):** Chỉ hiển thị nút `Bỏ chặn`.
- **D-03:** Trong `nav_contacts.xml`, **`ContactsListFragment`** là màn hình chính của tab Danh bạ (hiển thị danh sách bạn bè kèm lối tắt tới Lời mời và Danh sách chặn). Các màn **Lời mời kết bạn** (`FriendRequestsFragment`), **Tìm kiếm SĐT** (`SearchUserFragment`) và **Danh sách chặn** (`BlockedUsersFragment`) là các màn hình con trong `nav_contacts.xml`, mỗi màn đều có nút quay lại trên Toolbar. Badge trên lối tắt Lời mời chỉ đếm **lời mời nhận đến còn `PENDING`**; màn Lời mời chia rõ 2 phần **Đã nhận** và **Đã gửi**.

### Tìm kiếm SĐT, Bảo mật & Phân quyền
- **D-04:** `GET /api/users/lookup?phone=` chỉ khớp chính xác SĐT E.164, giới hạn **10 lượt/phút** cho mỗi `userId` (vượt ngưỡng trả `429`). Tự tìm số của chính mình trả `400 SELF`; người dùng không tồn tại, đã xóa mềm (`DELETED`), hoặc tắt `discoverable = false` trả `404 NOT_FOUND`.
- **D-05:** Kiểm thử và thực thi chặt chẽ quyền hạn ở server: chỉ `addressee_id` mới có quyền `accept` hoặc `reject` lời mời gửi cho mình; chỉ `requester_id` mới có quyền `cancel` lời mời do mình gửi; người thứ ba không thể thao tác lên lời mời của cặp người dùng khác (`403`/`404`).

### Xử lý Đồng thời & Nhất quán Transaction trên Server
- **D-06:** Hai người gửi lời mời cho nhau cùng lúc: tận dụng ràng buộc duy nhất `idx_contact_requests_pending_pair` và giao dịch `@Transactional`; nếu B → A đã `PENDING`, server trả `409 REQUEST_ALREADY_PENDING` (kèm thông tin lời mời hiện có nếu phù hợp) để UI chuyển sang trạng thái *Nhận lời mời* cho phép bấm Chấp nhận (không tự động ghép bạn).
- **D-07:** Thao tác `accept` phải kiểm tra người thao tác là `addressee_id` và **kiểm tra không có quan hệ chặn giữa hai bên trước khi chấp nhận**, rồi thực thi **nguyên tử trong một `@Transactional`**: cập nhật lời mời `ACCEPTED`, chèn 2 dòng `contacts (blocked = false)`, và ghi `sync_events`.
  - Bấm `accept` hai lần / request lặp: nếu lời mời đã `ACCEPTED` với đúng cặp đó, xử lý idempotent trả `200`, **không tạo dữ liệu `contacts` hoặc `sync_events` trùng**. Nếu lời mời đã bị `CANCELED` hoặc `REJECTED`, từ chối nhất quán bằng `409`.

### Ranh giới Đồng bộ, Offline & Tích hợp Phase 3/5
- **D-08:** **Ranh giới đồng bộ giữa Phase 4 và Phase 5:** Backend Phase 4 bắt buộc ghi đầy đủ bản ghi `sync_events` (`CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED`) trong cùng `@Transactional` với thay đổi dữ liệu; Android Phase 4 cập nhật Room cache ngay sau phản hồi REST thành công (kèm tải lại khi mở màn / `SwipeRefreshLayout`). **Phần tích hợp còn chờ Phase 5:** Đẩy realtime qua WebSocket `/ws` (`contact_request.new`, `contact_request.updated`) và đồng bộ bù ngoại tuyến qua `GET /api/sync?after=<syncEventId>` thuộc Phase 5. — **Reversibility:** costly — chốt ranh giới hợp đồng sự kiện với Phase 5.
- **D-09:** **Hành vi khi mất mạng:** Mất mạng vẫn xem được danh bạ bạn bè, lời mời và danh sách chặn đã cache trong Room SQLite (`ContactDao`, `UserDao`), không làm trống giao diện hoặc tự đăng xuất. Thao tác gửi/chấp nhận/hủy lời mời, chặn/bỏ chặn, tìm kiếm SĐT yêu cầu có mạng; khi gặp lỗi mạng, hiển thị lỗi rõ ràng và cho phép thử lại (không đưa vào hàng đợi nền WorkManager của Phase 5 để giữ V1 gọn).
- **D-10:** **Tái sử dụng Hạ tầng Auth Phase 3 & Cô lập Tài khoản:** Phase 4 tái sử dụng `SessionManager` (gồm `getMeId()`), `AuthInterceptor`, `Injection.DISK` và cơ chế dọn phiên `clearAllTables()` của Phase 3 (không tạo cơ chế JWT riêng). Vì `ContactEntity` và `ContactRequestEntity` trong Room không chứa `ownerId`, phải kiểm chứng dọn sạch toàn bộ cache Room danh bạ và `user_cache` khi đăng xuất/đổi tài khoản để không lọt dữ liệu tài khoản cũ sang tài khoản mới. — **Reversibility:** costly — hạ tầng code Phase 3 (`aaf3844`) đã hoàn tất và đã merge vào `main`, làm gốc tích hợp trực tiếp cho Phase 4.

### Quy chuẩn Công nghệ, Kiểm thử & Comment
- **D-11:** Toàn bộ code tuân thủ `GEMINI.md` & `AGENTS.md`: 100% Java 17 (tuyệt đối không dùng Kotlin), Views + XML, ViewBinding, MVVM LiveData, Seam `ContactsRepository` (`FakeContactsRepository` & `RealContactsRepository`), Material 3 DayNight, i18n Anh-Việt (`strings_contacts.xml`), và 100% chú thích Tiếng Việt sư phạm. Bảo đảm tuân thủ `docs/DEPLOYMENT_SECURITY.md`.

### the agent's Discretion
- Bố cục chi tiết thẻ người dùng, thanh tìm kiếm/lọc nội bộ trong danh sách bạn bè đã tải, animation chuyển màn và dialog xác nhận trước khi hủy kết bạn/chặn theo chuẩn Material 3.
- Cấu trúc chi tiết bộ đếm giới hạn `10 lượt/phút` cho `/api/users/lookup` ở backend (token bucket / sliding window theo `userId`, lưu ý bảng `auth_throttles` của `V1__initial_schema.sql` chỉ nhận `purpose` thuộc `'PASSWORD_LOGIN', 'OTP_VERIFY', 'PASSWORD_RESET'`).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing:**

### Architecture, Contracts & Security Specs
- `docs/API_CONTRACT.md` — Mục 1 (Quy ước chung, định dạng lỗi) và Mục 2.3 (Toàn bộ endpoints `/users/lookup`, `/contact-requests/*`, `/contacts/*`).
- `docs/FLOWS.md` — Mục 2 / F-09 (Sequence diagram và quy tắc quản lý danh bạ, `contacts` semantics, chống trùng ngược chiều).
- `docs/ERD.md` — Bảng server `contact_requests`, `contacts`, `sync_events` và bảng Room client `contact`, `contact_request`, `user_cache`.
- `docs/ARCHITECTURE_DECISIONS.md` — ADR-001 (SĐT E.164), ADR-009 (Offline-first), ADR-018 (Token Auth), ADR-021 (UI i18n & DayNight), ADR-023 (Kiến trúc Android chuẩn slide).
- `docs/DEPLOYMENT_SECURITY.md` — Quy chuẩn HTTPS/WSS, Bearer header, không lộ secret/credential.
- `GEMINI.md` & `AGENTS.md` — Bắt buộc 100% Java 17, 100% comment Tiếng Việt sư phạm.

### Database & Existing Code Seams
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — Schema `contact_requests` (kèm `idx_contact_requests_pending_pair`), `contacts`, `sync_events`.
- `app/src/main/java/com/utechat/data/repository/ContactsRepository.java` — Seam interface hiện có.
- `app/src/main/java/com/utechat/data/repository/fake/FakeContactsRepository.java` — Mock repository hiện có (giữ tương thích `FakeRepositoriesTest`).
- `app/src/main/java/com/utechat/data/repository/real/RealContactsRepository.java` — Production repository khung hiện có.
- `app/src/main/java/com/utechat/data/remote/api/ContactApi.java` — Interface Retrofit danh bạ hiện có.
- `app/src/main/java/com/utechat/data/local/dao/ContactDao.java` — Room DAO cho `contact`, `contact_request`, `user_cache`.
- `app/src/main/java/com/utechat/data/local/entity/ContactEntity.java` & `ContactRequestEntity.java` — Room Entities chuẩn ERD.
- `app/src/main/res/navigation/nav_contacts.xml` — Graph con của phân hệ Danh bạ.
- `app/src/main/java/com/utechat/di/Injection.java` — Service Locator ghép `ContactsRepository` và cơ chế phiên từ Phase 3.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ContactDao`, `UserDao`, `ContactEntity`, `ContactRequestEntity` đã định nghĩa sẵn theo đúng 10 bảng Room chuẩn ERD.
- `FakeContactsRepository` đang được kiểm thử bởi `FakeRepositoriesTest#contactsRespectDirectionAndPrivacyFixture` (3 cặp bạn Alice-Bob-Charlie, Alice chặn Eve 1 chiều, David bật `allowMessagesFromNonContacts = false`) — cần giữ tương thích ngược khi mở rộng.
- `nav_contacts.xml` và `ContactsListFragment` đã tạo sẵn trong Single Activity.
- `AuthValidator` (Phase 3) chuẩn hóa E.164 `+84...` và tạo chữ cái đầu avatar có thể tái sử dụng hoặc tách helper chung.

### Established Patterns
- Tách `interface` Repository khỏi `Fake...` và `Real...` qua `Injection.java`.
- DTO JSON `camelCase` tách riêng khỏi Room `@Entity`.
- `ListAdapter + DiffUtil` cho mọi `RecyclerView`; không dùng `notifyDataSetChanged()`.
- Tái sử dụng `SessionManager` + `AuthInterceptor` (JWT Bearer token) từ Phase 3, không tự tạo cơ chế xác thực riêng.

### Integration Points
- Màn hình `ContactsListFragment` điều hướng sang các màn con `FriendRequestsFragment`, `SearchUserFragment`, `BlockedUsersFragment` trong `nav_contacts.xml`.
- Backend ghi `sync_events` (`CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED`) để Phase 5 đọc khi đồng bộ WebSocket / `/api/sync`.
- Khi nhấn "Đăng xuất" hoặc đổi tài khoản (Phase 3), Room `clearAllTables()` xóa sạch cache danh bạ để bảo đảm không lẫn dữ liệu.

</code_context>

<specifics>
## Specific Ideas

- Đảm bảo kiểm thử quyền hạn và đồng thời thật trên Backend (ví dụ Embedded PostgreSQL trong `@SpringBootTest` như `AuthApiTest` của Phase 3): test A & B gửi lời mời cho nhau cùng lúc, bấm accept 2 lần (idempotent), accept sau khi requester đã cancel (`409`), chặn 1 chiều nhưng cản tương tác 2 bên, và không thể accept/cancel lời mời của người khác.
- Giữ giao diện sạch cho người dùng cuối (ẩn nút "Nhắn tin" thay vì hiện câu thông báo kỹ thuật lộ Phase).

</specifics>

<deferred>
## Deferred Ideas

- Nút "Nhắn tin" trên thẻ Bạn bè mở/tạo hội thoại (`POST /api/conversations/direct`) → thuộc **Phase 5: Core Messaging**.
- Nhận lời mời / cập nhật bạn bè tự động theo thời gian thực qua WebSocket `/ws` và đồng bộ bù `GET /api/sync?after=<syncEventId>` → thuộc **Phase 5: Core Messaging**.
- Hàng đợi gửi thao tác kết bạn/chặn khi offline qua WorkManager → giữ V1 gọn bằng thao tác online + xem cache offline.
- Nút "Gọi thoại" trên thẻ Bạn bè / hồ sơ → thuộc **Phase 6: WebRTC Voice Call**.

</deferred>

---

*Phase: 04-contacts-friendships*
*Context gathered: 2026-11-17*
