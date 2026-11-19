# Phase 5: Realtime & Offline-First Core Messaging - Research

**Date:** 2026-11-19  
**Author:** GSD Phase Researcher  
**Phase:** 05 — Realtime & Offline-First Core Messaging  
**Requirement IDs:** `REQ-messaging-realtime`, `REQ-messaging-offline-first`, `REQ-messaging-delivery-read-status`, `REQ-messaging-group-chat`, `REQ-messaging-search`  
**Status:** COMPLETE

---

<user_constraints>
## User Constraints (from 05-CONTEXT.md)

### Locked Decisions (`D-01`..`D-17`)

#### Luồng tạo hội thoại mới & Ghép nối với Phase 4
- **D-01:** Từ nút tạo hội thoại mới ở `ChatListFragment`, hỗ trợ cả chọn từ danh bạ bạn bè, ô nhập SĐT nhắn người lạ và lối tắt "Tạo nhóm" (chỉ chọn thành viên từ danh sách bạn bè):
  - Bắt buộc **dùng lại API `GET /api/users/lookup?phone=` và `ContactsRepository` của Phase 4**, tuyệt đối không viết lại logic tìm SĐT.
  - Nếu người nhận tắt nhận tin người lạ (`allow_messages_from_non_contacts = false` / `403 CONTACT_REQUIRED`), hiển thị *"Người này chỉ nhận tin nhắn từ bạn bè"* và cho phép gửi lời mời kết bạn; nếu bị chặn, hiển thị thông báo phù hợp.
  - Server kiểm quyền ở cả bước tạo hội thoại 1-1 lẫn mỗi lần gửi tin. Chọn lại cùng người phải mở lại hội thoại 1-1 hiện có (nhờ `direct_key` duy nhất).
- **D-02:** Phase 5 cung cấp một điểm mở chat 1-1 nhận `userId`, tái sử dụng hội thoại hiện có hoặc tạo mới qua server; kiểm chứng ngay bằng luồng tạo chat từ `ChatListFragment`. Thành viên làm UI Phase 4 (Tv2) sẽ nối nút *"Nhắn tin"* vào điểm này sau khi hoàn tất giao diện danh bạ. Chốt trước người phụ trách các file navigation/DI dùng chung (`app/src/main/res/navigation/nav_graph.xml`, `app/src/main/java/com/utechat/di/Injection.java`); chỉ xem tích hợp hoàn tất khi bấm từ Danh bạ mở đúng phòng chat. — **Reversibility:** costly — định nghĩa hợp đồng điều hướng giữa `nav_contacts` và `nav_chat`.
- **D-03:** Dùng chung **một bộ xử lý sự kiện idempotent** cho WebSocket `/ws` và `GET /api/sync`, tái sử dụng DAO/Repository Phase 4 (`ContactDao`, `UserDao`). Áp dụng đầy đủ các sự kiện Chat (`MESSAGE_CREATED`, `CONVERSATION_UPDATED`, `RECEIPT_UPDATED`, `DEVICE_REVOKED`) và cả 3 sự kiện Contact (`CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED` — kể cả hủy kết bạn, bỏ chặn và xóa dòng cache `removed = true`). Xử lý lặp không tạo dữ liệu trùng; chỉ nâng cursor sau khi thay đổi Room được lưu thành công trong cùng transaction. **Không bỏ qua event Contact rồi vẫn nâng cursor**, vì có thể mất cập nhật vĩnh viễn. — **Reversibility:** costly — khóa kiến trúc Event Applier dùng chung giữa Phase 4, Phase 5 và Phase 6; dữ liệu bền vững áp dụng theo thứ tự batch /sync, WebSocket chỉ báo thay đổi để gọi sync (typing và auth.revoked xử lý trực tiếp).
- **D-04:** Trong phòng chat 1-1, khi bị chặn (`403 BLOCKED`) hoặc đối phương tắt nhận tin người lạ (`403 CONTACT_REQUIRED`), giữ lịch sử cũ, đánh dấu tin vừa gửi `FAILED` kèm lý do rõ (Bị chặn / Cần kết bạn để nhắn tin) và hiện banner cảnh báo trên khung nhập.

#### Hàng đợi Offline, tin `FAILED` & Đồng bộ (`Bootstrap`, `/sync`, `/ws`)
- **D-05:** Hàng đợi gửi ngoại tuyến qua WorkManager (`send-messages`, `ExistingWorkPolicy.APPEND_OR_REPLACE`, chỉ truyền `clientId`; tên chuỗi thực tế `send-messages:<sessionKey>`, gắn tag phiên để cách ly lần đăng nhập) và thao tác trên tin `FAILED`:
  - Khi thiết bị mất mạng: giữ tin chờ gửi (`SENDING`), WorkManager đợi có mạng (`NetworkType.CONNECTED`); không tính thời gian offline vào số lần thử.
  - Lỗi mạng khi đang gửi (`IOException`), `408`, `5xx`, `429`: retry có backoff, tối đa 3 lượt gửi tự động; với `429`, tôn trọng `Retry-After`.
  - Lỗi nghiệp vụ `400`/`403`/`404`: chuyển `FAILED` ngay, không tự retry; hiển thị lý do cụ thể như bị chặn, cần kết bạn hoặc không còn trong nhóm (`NOT_A_MEMBER`).
  - `401`: dừng đúng chuỗi của phiên gửi request; chỉ yêu cầu đăng nhập lại khi token + version vẫn thuộc phiên hiện tại. Phản hồi trễ không được hủy chuỗi, xóa cache hay đăng xuất lần đăng nhập mới; request giữ Bearer của phiên ban đầu.
  - Trên UI, bấm vào tin `FAILED` hỗ trợ **"Gửi lại"** (giữ nguyên `clientId` để server chống trùng, kể cả khi lần trước server đã lưu nhưng app chưa nhận được phản hồi) hoặc **"Xóa tin lỗi"** (chỉ xóa bản cục bộ FAILED/serverId=NULL; không cancel node trong chuỗi vì có thể hủy các tin phụ thuộc phía sau; Worker gặp dòng đã xóa trả success, không có nghĩa thu hồi tin trên server).
- **D-06:** Bootstrap Snapshot + Cursor nhất quán khi mới đăng nhập hoặc cursor hết hạn:
  - Server cung cấp **snapshot và cursor nhất quán** (không tải snapshot xong rồi mới lấy cursor mới nhất, vì có thể bỏ qua thay đổi phát sinh giữa hai bước).
  - Snapshot gồm: danh sách hội thoại, thông tin thành viên cần thiết, bạn bè, danh sách chặn và lời mời đang chờ (`PENDING`). Lịch sử tin nhắn tải phân trang (`?before=<messageId>&limit=50`) khi mở phòng chat.
  - Lưu snapshot và cursor vào Room trong cùng transaction; giữ các tin cục bộ đang chờ gửi (`SENDING`) hoặc `FAILED`.
  - Sau bootstrap, chạy `/sync` phân trang từ cursor được cấp; phối hợp WebSocket để nhận đủ sự kiện phát sinh trong thời gian chuyển tiếp, xử lý trùng an toàn.
  - Server báo rõ khi cursor không còn dùng được, chẳng hạn **`410 SYNC_CURSOR_EXPIRED`**; client thực hiện bootstrap lại. — **Reversibility:** one-way — bổ sung endpoint/hợp đồng bootstrap snapshot+cursor và mã `410 SYNC_CURSOR_EXPIRED` vào `docs/API_CONTRACT.md`.
- **D-07:** Phối hợp WebSocket `/ws` và `GET /api/sync` khi kết nối & tái kết nối (backoff 1, 2, 4, 8… tối đa 30s):
  - WebSocket báo có thay đổi và kích hoạt `/api/sync`. **Chỉ kết quả `/sync` được dùng để cập nhật cursor bền vững (`sync_state.lastSyncEventId`); không nâng cursor theo ID lớn nhất nhận qua WebSocket.**
  - Khi kết nối lại: thiết lập WebSocket sẵn sàng nhận sự kiện, rồi chạy `/sync` phân trang đến hết.
  - Chỉ có một lượt sync chạy tại một thời điểm (single-flight); nếu WebSocket báo thay đổi trong lúc sync, đánh dấu để chạy tiếp trước khi kết thúc.
  - Áp dụng sự kiện idempotent; lưu thay đổi dữ liệu và `nextCursor` do server trả trong cùng transaction Room.
  - **Không dùng điều kiện `eventId == lastSyncEventId + 1`** để phát hiện thiếu sự kiện; phía server xử lý an toàn trường hợp các transaction commit khác thứ tự ID. — **Reversibility:** costly — khóa giao thức đồng bộ xuyên suốt Backend & Android.
- **D-08:** Khi WebSocket mất kết nối (`Disconnected` / `Reconnecting`), hiện thanh trạng thái nhỏ dưới Toolbar trên **cả `ChatListFragment` và `ChatRoomFragment`**, tự ẩn ngay khi nối lại & đồng bộ xong.

#### Chat nhóm, Tin hệ thống `SYSTEM`, Biên nhận & Quản lý thành viên
- **D-09:** Server sinh tin `SYSTEM` (`systemEvent` + `systemParams`) trong cùng transaction với thay đổi nhóm (tạo nhóm, đổi tên, thêm/xóa/rời nhóm, đổi quyền `ADMIN`) và `sync_events`; Android hiển thị qua chuỗi EN/VI. Thao tác thất bại, không thay đổi dữ liệu hoặc request lặp **không được tạo thêm tin `SYSTEM`** (hoặc `sync_events`). Thiết lập `muted` là cài đặt cá nhân, không sinh tin `SYSTEM`.
- **D-10:** Có màn `GroupInfoFragment` riêng trong `nav_chat.xml` (sửa tên nhóm, bật/tắt `muted` cá nhân, xem danh sách thành viên + nhãn `ADMIN`, thêm thành viên từ bạn bè, xóa/cấp quyền `ADMIN`, rời nhóm). Server kiểm quyền cho mọi thao tác quản trị (nhóm tối đa 50 thành viên, chỉ thêm bạn bè `ACCEPTED`). Khi rời nhóm:
  - `ADMIN` duy nhất và còn thành viên khác: chọn người kế nhiệm; **bàn giao quyền và rời nhóm trong cùng transaction**.
  - Đã có `ADMIN` khác: cho rời trực tiếp.
  - Là thành viên cuối cùng: cho rời trực tiếp, không bắt chọn người kế nhiệm.
- **D-11:** Hiển thị trạng thái `SENDING / SENT / DELIVERED / READ` (hoặc `FAILED`) nhất quán cho cả chat 1-1 và nhóm (trong nhóm lên `DELIVERED`/`READ` khi ít nhất 1 thành viên khác đạt mốc theo ADR-010); gọi `POST /conversations/{id}/read` khi phòng chat đang RESUMED, với mốc cao nhất thực sự nằm trong vùng nhìn sau layout, cao hơn lastReadId; không lấy max của toàn bộ danh sách đã tải, không xóa unread của tin còn chưa nhìn thấy. Bảo đảm watermark không giảm và `last_delivered_message_id >= last_read_message_id`.
- **D-12:** Khi thành viên rời/bị xóa khỏi nhóm (`left_at != NULL`): người bị xóa vẫn phải nhận được **sự kiện cuối** thông báo mất tư cách thành viên để app chuyển hội thoại sang **chế độ chỉ đọc** (ẩn khung nhập tin, hiện thông báo *"Bạn không còn là thành viên của nhóm này"*, giữ lịch sử tin trước thời điểm rời trong Room); sau đó server ngừng cấp sự kiện mới của nhóm và chặn gửi/tải tin sau mốc rời bằng `403 NOT_A_MEMBER`. Các tin đang chờ gửi không được retry vô hạn khi gặp `NOT_A_MEMBER`.

#### Tìm kiếm FTS4 tiếng Việt, Cô lập lịch sử & Ranh giới Phase 6
- **D-13:** Hỗ trợ tìm kiếm offline dùng chung Room FTS4 `message_fts` (không xây hai cơ chế tìm kiếm riêng) ở cả hai phạm vi:
  - Trong `ChatRoomFragment`: tìm nội dung tin nhắn của phòng hiện tại, hiển thị số kết quả và cho phép chuyển đến kết quả trước/sau.
  - Trong `ChatListFragment`: tìm theo tên hội thoại và tìm nội dung tin nhắn trên các hội thoại (phân biệt rõ hai nhóm kết quả); bấm kết quả tin nhắn phải mở đúng phòng và đưa tin khớp vào vùng hiển thị, **kể cả khi tin nằm ngoài trang đang tải**.
  - Chuẩn hóa cả nội dung được lập chỉ mục và từ khóa: chuyển chữ thường, bỏ dấu tiếng Việt, chuyển `đ/Đ` thành `d`; xử lý an toàn ký tự đặc biệt của cú pháp `MATCH`; đầu vào rỗng không gây lỗi.
  - Chỉ tìm trong dữ liệu đã cache trên thiết bị và thuộc phạm vi lịch sử người dùng được phép xem; giao diện ghi rõ **"Tin nhắn trên thiết bị"** (không ngụ ý tìm được toàn bộ lịch sử server); giới hạn/phân trang kết quả; FTS cập nhật đúng khi thêm, sửa hoặc xóa cache.
- **D-14:** Cô lập lịch sử nhóm theo lần tham gia hiện tại, dùng `visible_from_message_id` do server quyết định làm nguồn sự thật:
  - Rời/bị xóa giữ lịch sử đã cache ở chế độ chỉ đọc theo D-12. Khi được **thêm lại** với mốc `visible_from_message_id` mới: chỉ hiển thị, tìm kiếm và tải các tin có **`serverId > visible_from_message_id`** (**không dùng `>=`** vì mốc là ID cuối trước thời điểm được thêm lại).
  - Truyền và lưu mốc cần thiết trên Android để áp dụng cả khi offline; nếu đổi schema `ConversationMemberEntity` (hiện chưa có cột này) phải bổ sung version và migration Room phù hợp.
  - Khi nhận mốc mới, cập nhật trạng thái thành viên và dọn các tin cache ngoài phạm vi trong cùng transaction; bảo đảm chỉ mục FTS và dữ liệu phụ liên quan không còn làm lộ nội dung đã loại bỏ.
  - Mọi đường nạp dữ liệu (lịch sử REST, bootstrap, `/sync`, WebSocket) phải tuân thủ mốc hiện tại; server kiểm quyền độc lập với client; phản hồi hoặc sự kiện cũ đến muộn không được hạ mốc hay chèn lại lịch sử đã bị loại khỏi phạm vi.
  - Xử lý riêng tin chờ gửi/`FAILED` có `serverId = NULL`: không xóa nhầm bằng điều kiện lọc lịch sử và không tự gửi lại tin của lần tham gia cũ chỉ vì người dùng vừa được thêm lại. Lưu queuedVisibleFromMessageId khi xếp hàng; gửi expectedVisibleFromMessageId để server so với mốc hiện tại dưới khóa hội thoại, lệch trả 409 MEMBERSHIP_CHANGED. Tin nhóm cũ chưa có mốc xác minh không tự gửi; chỉ thao tác Gửi lại chủ động mới được nhận mốc mới, giữ clientId. — **Reversibility:** one-way — thay đổi DTO thành viên và schema Room (`AppDatabase` cần tăng version + migration).
- **D-15:** Ranh giới giao diện và dữ liệu với Phase 6:
  - Trong Phase 5, ẩn sạch nút đính kèm Media, gửi vị trí và gọi thoại trên `ChatRoomFragment` (không hiển thị nút disabled, nút không hoạt động hoặc thông báo kỹ thuật kiểu *"Chờ Phase 6"*).
  - Phạm vi gửi tin của Phase 5 là `TEXT` và các tin `SYSTEM` do server tạo.
  - Adapter vẫn phải hiển thị an toàn dữ liệu `IMAGE`/`AUDIO`/`VIDEO`/`FILE`/`LOCATION`/`CALL` đã tồn tại từ seed hoặc đồng bộ bằng thẻ tóm tắt tối giản với icon và nhãn EN/VI phù hợp (không gắn thao tác phát/mở/gọi khi chưa có chức năng xử lý), và có giao diện dự phòng an toàn cho loại tin chưa nhận diện để không làm crash danh sách chat.
  - DTO, Repository và Room phải giữ nguyên toàn bộ metadata đã được hợp đồng API định nghĩa, kể cả trường Phase 5 chưa dùng để hiển thị; không biến tin khác loại thành `TEXT` hoặc làm mất metadata khi đồng bộ. Tách view type đủ rõ để Phase 6 bổ sung trình bày và thao tác, không tạo framework thừa.

#### Kiểm thử bắt buộc, Hợp đồng API & Quy chuẩn Code
- **D-16:** Bắt buộc kiểm thử các bất biến dữ liệu, quyền truy cập và race condition ngay trong Phase 5 (không dồn sang Phase 7):
  - Backend (JUnit/MockMvc + `EmbeddedPostgres` thật, **không yêu cầu Docker**): `direct_key` (kể cả tạo đồng thời), `BLOCKED`/`CONTACT_REQUIRED`/`NOT_A_MEMBER` và quyền quản trị nhóm, giới hạn 50 người, thêm thành viên đúng điều kiện bạn bè `ACCEPTED`, phạm vi `visible_from_message_id` (`>`), bàn giao `ADMIN` và rời nhóm nguyên tử + trường hợp thành viên cuối cùng, chống trùng `clientId`, request lặp không sinh trùng tin `SYSTEM`/`sync_events`, watermark không giảm & `READ <= DELIVERED`, bootstrap snapshot/cursor, phân trang `/sync`, `410 SYNC_CURSOR_EXPIRED`, sự kiện trùng, transaction commit khác thứ tự ID (không giả định ID sự kiện của một người dùng liên tiếp), WebSocket Bearer handshake & chặn JWT trong query.
  - Android: Unit test JVM cho Repository/ViewModel/Worker + instrumentation test Room/FTS thật trên emulator/thiết bị (tìm kiếm tiếng Việt có/không dấu, phạm vi từng phòng vs toàn cục, ký tự đặc biệt `MATCH`, cập nhật FTS khi xóa cache, mở đúng tin ngoài trang đang tải từ tìm kiếm toàn cục, render mẫu non-TEXT & bảo toàn metadata REST→Room→UI, rời→thêm lại nhóm trên cùng thiết bị/thiết bị mới/offline/sự kiện muộn, retry giữ `clientId`, phân loại lỗi, phản hồi đến muộn, đổi tài khoản, kết nối lại & đồng bộ sự kiện Contact/Chat). Giữ `FakeRepositoriesTest` xanh.
  - Nghiệm thu thực tế bằng 2 tài khoản trên app dùng backend thật: chat 1-1/nhóm, mất mạng → gửi → kết nối lại, thay đổi danh bạ realtime, rời/tham gia lại nhóm và tìm kiếm (không chấp nhận chỉ chạy Fake hoặc mock).
  - Trước khi báo hoàn tất: `mvn test`, Android unit test, `lintDebug` và các instrumentation test liên quan phải pass; ghi rõ kết quả cùng phần chưa kiểm chứng.
- **D-17:** Tuân thủ tuyệt đối `GEMINI.md`, `AGENTS.md`, `docs/DEPLOYMENT_SECURITY.md`: 100% Java 17 (tuyệt đối không dùng Kotlin), Views + XML, ViewBinding, MVVM + LiveData, Room SQLite, WorkManager, Material 3 DayNight, i18n EN/VI (`strings_chat.xml`), và 100% comment Tiếng Việt sư phạm. Cập nhật đầy đủ các phần hợp đồng mở rộng trong `docs/API_CONTRACT.md` (bootstrap snapshot+cursor, `410 SYNC_CURSOR_EXPIRED`, `visibleFromMessageId`, bàn giao `ADMIN` khi rời nhóm, bảng danh mục `systemEvent`/`systemParams`).

### the agent's Discretion
- Bố cục chi tiết bong bóng tin nhắn, phân nhóm theo ngày (date separator), hiển thị chỉ báo đang nhập (`typing`) và chi tiết tạo kênh thông báo cục bộ tối giản nếu cần hoặc để trọn phần `NotificationChannel` sang Phase 6.
- Thiết kế cụ thể cơ chế server xử lý/chặn nhảy cóc khi transaction commit khác thứ tự `sync_events.id` và cấu trúc cụ thể view types của `MessageAdapter`.

### Deferred Ideas
- Tải lên/tải xuống/mở tệp đa phương tiện (`POST /api/media`, `GET /api/media/{id}`, nén ảnh, ghi âm voice `.m4a`, video ExoPlayer, vị trí bản đồ, `FileProvider`) → thuộc **Phase 6**.
- `NotificationChannel` chi tiết + đồng bộ nền `WorkManager` định kỳ 15 phút (best-effort) → thuộc **Phase 6**.
- Cuộc gọi thoại WebRTC 1-1 (`POST /api/calls/*`, coturn, `call.incoming`/`offer`/`answer`/`ice`/`ended`) → thuộc **Phase 6**.
- Dịch tin nhắn Anh–Việt on-device bằng Google ML Kit (`message_translation`) → thuộc **Phase 6**.
- Gắn nút "Nhắn tin" từ màn hình Danh bạ (`ContactsListFragment`) sau khi Tv2 hoàn tất UI Phase 4 → nghiệm thu tích hợp liên kết Phase 4 ↔ Phase 5 khi Tv2 xong 3 màn con danh bạ.
- Kiểm thử hosted HTTPS/WSS toàn hệ thống, tổng nghiệm thu và đóng gói nộp bài → thuộc **Phase 7**.

</user_constraints>

---

## Project Constraints (from GEMINI.md & AGENTS.md)

1. **Ngôn ngữ & Stack bắt buộc:**
   - **Android (`com.utechat`):** 100% **Java 17** (`compileSdk 36`, `minSdk 26`, `targetSdk 36`), Views + XML, ViewBinding, MVVM + LiveData, Navigation Component (Single Activity `MainActivity`), Room SQLite (`annotationProcessor`, KHÔNG Kapt/KSP), WorkManager, Retrofit + OkHttp. Tuyệt đối **KHÔNG** dùng Kotlin, Coroutine, Flow, Compose, Hilt/Dagger. [VERIFIED: `GEMINI.md#L17`, `AGENTS.md#L17`, `app/build.gradle.kts#L10-L54`]
   - **Backend (`com.utechat.backend`):** Spring Boot `3.3.5`, Java 17 (`backend/pom.xml#L9,L17`), Spring Web, Spring Security (stateless JWT), Spring WebSocket thuần JSON (KHÔNG STOMP), Spring JDBC (`JdbcTemplate`, KHÔNG Hibernate/JPA), Flyway migration, PostgreSQL driver kết nối Supabase Transaction Pooler (`port 6543`, `prepareThreshold=0`). [VERIFIED: `GEMINI.md#L18`, `backend/pom.xml#L9-L40`, `docs/ARCHITECTURE_DECISIONS.md#ADR-006`]
2. **Comment Tiếng Việt Sư phạm (QUAN TRỌNG NHẤT):** Toàn bộ code mới/chỉnh sửa (Java, XML, SQL, Retrofit, Room, WorkManager, Controller/Service) BẮT BUỘC phải có comment ngắn gọn, súc tích bằng **Tiếng Việt** giải thích bản chất *"dòng code / khối lệnh này làm gì và nhằm mục đích gì trong hệ thống"* để phục vụ bảo vệ đồ án. [VERIFIED: `GEMINI.md#L3-L15`, `AGENTS.md#L3-L15`]
3. **Bảo mật triển khai V1 (`docs/DEPLOYMENT_SECURITY.md`):**
   - Release bắt buộc HTTPS (`https://`) và WSS (`wss://`), cấm TrustAll hoặc bỏ kiểm hostname; HTTP/WS chỉ dùng cho Debug nội bộ (`10.0.2.2`, LAN). [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L5-L11`, `app/src/main/res/xml/network_security_config.xml#L3-L6`]
   - WebSocket dùng cùng origin `BACKEND_BASE_URL` (đổi scheme `https -> wss` / `http -> ws`, path `/ws`), xác thực qua header `Authorization: Bearer <token>` trên HTTP Upgrade handshake; **tuyệt đối không đặt JWT vào query URL hoặc subprotocol**. [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L9,L25`]
   - Không log JWT, `Authorization` header, mật khẩu, hoặc OTP; Release giữ `HttpLoggingInterceptor.Level.NONE`. [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L27`, `app/src/main/java/com/utechat/data/remote/api/ApiClient.java#L16-L17`]
4. **Kiến trúc chống xung đột (`ADR-025`):**
   - Tách riêng file chuỗi i18n module Chat: `app/src/main/res/values/strings_chat.xml` và `app/src/main/res/values-vi/strings_chat.xml`. [VERIFIED: `docs/ARCHITECTURE_DECISIONS.md#L156`]
   - Khai báo màn hình Chat mới bên trong `app/src/main/res/navigation/nav_chat.xml`; dùng `ChatApi` và `ChatRepository` interface + `FakeChatRepository` + `RealChatRepository` qua `Injection.java`. [VERIFIED: `app/src/main/res/navigation/nav_chat.xml`, `app/src/main/java/com/utechat/di/Injection.java#L41-L51`]
   - Điểm chạm dùng chung (`nav_graph.xml`, `Injection.java`, `MainActivity.java`, `SessionManager.java`) phải giữ tương thích ngược 100% với Phase 3, Phase 4 và `FakeRepositoriesTest`. [VERIFIED: `05-CONTEXT.md#D-02,D-16`]
5. **Quy chuẩn Lint & Môi trường Test (bài học xác minh từ Phase 4):**
   - **CẤM dùng `androidx.arch.core.executor.ArchTaskExecutor.getInstance()`** trong `app/src/main/**` (dù xuất hiện trong nhánh nháp `origin/phase4_NP`), vì API này mang `@RestrictTo` và làm **`lintDebug` lỗi `RestrictedApi`**. Thay bằng `Executor mainExecutor = command -> new Handler(Looper.getMainLooper()).post(command)` (inject được `Runnable::run` trong JVM test). [VERIFIED: `origin/phase4_NP:RealContactsRepository.java#L34`, `origin/phase4_NP:SessionManager.java#L53`]
   - Backend `mvn test` / `mvnw.cmd test` bắt buộc chạy bằng `io.zonky.test:embedded-postgres` (`2.0.7`, đã có sẵn trong `backend/pom.xml#L39`) — **không yêu cầu Docker**. [VERIFIED: `backend/pom.xml#L39`, `05-CONTEXT.md#D-16`]

---

## Architectural Responsibility Map

Bảng phân định trách nhiệm rõ ràng giữa các tầng để tránh trùng lặp hoặc đẩy logic sai tầng:

| Tầng / Thành phần | Trách nhiệm chính xác trong Phase 5 | Tuyệt đối KHÔNG làm |
|---|---|---|
| **Backend Controllers** (`ConversationController`, `MessageController`, `SyncController`) | Parse path/query/body, trích xuất `@AuthenticationPrincipal AuthenticatedActor` từ SecurityContext (`ADR-018`), validate định dạng thô, ủy quyền xuống Service. | Không đọc `userId` từ body/query; không chứa SQL trực tiếp hoặc tự xử lý transaction. |
| **Backend Messaging/Group Services** (`ConversationService`, `MessageService`, `ReceiptService`) | Thực thi transaction nguyên tử (`@Transactional` / `TransactionTemplate`): kiểm quyền (`NOT_A_MEMBER`, `NOT_ADMIN`, `BLOCKED`, `CONTACT_REQUIRED`), khóa `ContactPolicyService.lockPair` / lock row `FOR UPDATE`, giới hạn 50 thành viên, chống trùng `clientId`, watermark đơn điệu, sinh tin `SYSTEM` và gọi `SyncEventWriter`. | Không đẩy WebSocket frame trước khi transaction commit (`afterCommit` bắt buộc); không cho client tự gửi `type=SYSTEM`; không tạo `SYSTEM`/`sync_events` cho `muted` hoặc thao tác lặp/lỗi. |
| **Backend Sync & WS Layer** (`SyncService`, `SyncEventWriter`, `SyncRetentionJob`, `ChatWebSocketHandler`, `WebSocketSessionRegistry`, `WsAuthHandshakeInterceptor`) | `SyncEventWriter` ghi `sync_events` (`barrier_xid`) trong transaction hiện tại và đăng ký `afterCommit()` đẩy WS. `SyncService` cung cấp `bootstrap` (`REPEATABLE READ READ ONLY`) và `/api/sync?after=` gap-safe (`barrier_xid <= xmin`) + `410 SYNC_CURSOR_EXPIRED`. `ChatWebSocketHandler` giữ session theo `(userId, deviceIdHash)`, từ chối JWT query param, ping/pong 25s, throttle typing 1/3s, đóng `4401` khi revoke/expire. | Không đẩy `typing` vào `sync_events`; không suy đoán thứ tự sự kiện bằng `id + 1`; không giữ session mở sau `DEVICE_REVOKED` hoặc token hết hạn. |
| **Android Local / Room** (`AppDatabase` v2, `ConversationDao`, `MessageDao`, `SyncStateDao`, `UserDao`, `ContactDao`, `MessageText`) | Nguồn hiển thị duy nhất (Single Source of Truth) cho UI offline-first. Migration `1 -> 2` thêm `member.visibleFromMessageId`, `message.errorCode` và `message.queuedVisibleFromMessageId`. Lọc/xóa tin `serverId <= visibleFromMessageId` cùng trigger `message_fts`, bảo vệ tin `serverId IS NULL`. Chỉ `SyncStateDao.advance` trong cùng transaction ghi sự kiện `/sync`. | Không bao giờ `fallbackToDestructiveMigration()` ở Release; không xóa tin `SENDING`/`FAILED` (`serverId IS NULL`) khi cập nhật `visibleFromMessageId`; không nâng `lastSyncEventId` từ WebSocket frame. |
| **Android Sync / Queue / WS** (`SyncEngine`, `SyncEventApplier`, `ChatWebSocketManager`, `SendMessageWorker`, `TypingTracker`) | `SendMessageWorker` chạy chuỗi WorkManager `send-messages` (`APPEND_OR_REPLACE`, chỉ truyền `clientId`, `NetworkType.CONNECTED`), retry tối đa 3 lần (tôn trọng `Retry-After` với `429`), đánh dấu `FAILED` + lý do và trả `Result.success()` để không chặn tin sau (trừ `401` dừng phiên). `SyncEngine` single-flight chạy bootstrap → `/sync` phân trang; `SyncEventApplier` xử lý idempotent cả Chat lẫn Contact events. | Không truyền JWT/nội dung tin vào WorkManager `Data`; không tự retry vô hạn lỗi `400`/`403`/`404`; không bỏ qua Contact events mà vẫn nâng cursor; không tự resend tin `FAILED` cũ khi re-add vào nhóm. |
| **Android UI / Navigation** (`nav_chat.xml`, `ChatListFragment`, `ChatRoomFragment`, `NewChatFragment`, `GroupCreateFragment`, `GroupInfoFragment`, `MessageAdapter`, `ChatViewModel`) | Render danh sách hội thoại, thanh trạng thái WS dưới Toolbar (`D-08`), bong bóng `TEXT`/`SYSTEM` + thẻ tóm tắt an toàn cho `IMAGE`/`AUDIO`/`VIDEO`/`FILE`/`LOCATION`/`CALL` (`D-15`), ẩn sạch nút Phase 6, tìm kiếm FTS4 trong phòng & toàn cục nhảy tới đúng tin ngoài trang (`D-13`), cung cấp điểm mở chat 1-1 theo `userId` (`D-02`). | Không giữ logic mạng/SQLite trong Fragment; không dùng `notifyDataSetChanged()` (dùng `ListAdapter + DiffUtil`); không lộ thông báo kỹ thuật *"Chờ Phase 6"*. |

---

## Security Domain

| Rủi ro / Bề mặt tấn công | Biện pháp kiểm soát bắt buộc trong Phase 5 | Nguồn quy định |
|---|---|---|
| **JWT rò rỉ trong URL / Log / Room** | Client gửi JWT chỉ qua header `Authorization: Bearer <token>` trên cả REST và HTTP Upgrade `wss://<host>/ws`. Server `WsAuthHandshakeInterceptor` từ chối mọi request `/ws` chứa query param nhạy cảm (`token`, `jwt`, `access_token`, `auth`) với HTTP `400`/`401` và không log query string. Release tắt `HttpLoggingInterceptor` (`NONE`), `SessionManager` giữ token trong Android Keystore AES-256-GCM. | [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L25,L27`], [VERIFIED: `ApiClient.java#L17`] |
| **Socket sống sót sau thu hồi thiết bị / đổi mật khẩu / hết hạn JWT** | `JwtAuthenticationFilter` kiểm tra `revoked_at IS NULL`, `status = 'ACTIVE'`, `iatMs >= token_valid_after` khi Upgrade `/ws`. Khi gọi `revokeDevice`, `logout`, `changePassword`, `completePasswordReset`, `scheduleDeletion`, `purgeExpiredDeletions`, server gửi `auth.revoked` (nếu revoke) và đóng ngay session WS bằng mã `4401`. Khi nhận `ping`/frame hoặc trong reaper 30s, kiểm `exp` (JWT expiration) và trạng thái thiết bị; đóng `4401` nếu hết hiệu lực. Android nhận `4401`/`auth.revoked` → dọn phiên, dừng WorkManager và về màn Login. | [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L26`], [VERIFIED: `docs/FLOWS.md#L105,L440`], [VERIFIED: `JwtAuthenticationFilter.java#L54-L66`] |
| **Vượt quyền xem/gửi/quản trị hội thoại (IDOR / Broken Access Control)** | Mọi endpoint `/api/conversations/**`, `/api/sync*` và WS `typing` đều kiểm tra `actor` từ `AuthenticatedActor` có `left_at IS NULL` (`403 NOT_A_MEMBER`), vai trò `ADMIN` cho đổi tên/thêm/xóa/đổi role (`403 NOT_ADMIN`), và kiểm `BLOCKED` trước `CONTACT_REQUIRED` cho 1-1 (`POST /conversations/direct` & `POST /messages`). Kiểm tra `visible_from_message_id` trên mọi truy vấn lịch sử & `lastMessage` preview. | [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L26`], [VERIFIED: `05-CONTEXT.md#D-01,D-10,D-12,D-14`] |
| **Giả mạo tin `SYSTEM`, giả mạo SĐT người lạ, hoặc lạm dụng rate limit** | `POST /conversations/{id}/messages` chỉ chấp nhận `type = "TEXT"` trong Phase 5 (từ chối `SYSTEM` bằng `400 VALIDATION`). Giới hạn `30 tin / 10 giây / userId` (`429 RATE_LIMITED` + `Retry-After`). Các DTO thành viên nhóm / bootstrap chỉ trả `{id, displayName, avatarUrl}` cho người không phải bạn bè, không rò rỉ `phone` của người lạ hoặc `allowMessagesFromNonContacts` của người khác. | [VERIFIED: `docs/API_CONTRACT.md#L218`], [VERIFIED: `origin/phase4_NP:ContactDtos.java#L8`] |
| **Lộ dữ liệu giữa các tài khoản trên cùng thiết bị Android** | `SessionManager` cấp `sessionKey()` theo `generation:userId`; `AuthRepository.logout()` / `401` / `4401` chạy `clearAllTables()` trên single-thread `DISK` executor và hủy chuỗi WorkManager `"send-messages"`. Mọi callback/SyncEngine/Worker đều đối chiếu `sessionKey` / `senderId == currentUserId` trước khi ghi Room hoặc gửi HTTP. | [VERIFIED: `05-CONTEXT.md#D-05`], [VERIFIED: `origin/phase4_NP:SessionManager.java#L64-L76`] |
| **Lộ Supabase Public Data API cho bảng mới (`V5`)** | Bảng mới `sync_retention_state` (nếu tạo trong `V5__sync_and_messaging.sql`) BẮT BUỘC bật `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;` không tạo policy (theo `ADR-007` và cổng bảo mật Phase 1). | [VERIFIED: `V1__initial_schema.sql#L188-L200`], [VERIFIED: `docs/ARCHITECTURE_DECISIONS.md#ADR-007`] |

---

## Phase Requirements

| Requirement ID | Mô tả & Phạm vi trong Phase 5 | Ánh xạ Thiết kế Chính |
|---|---|---|
| **REQ-messaging-realtime** | Nhắn tin 1-1 trực tiếp: tạo/mở hội thoại qua `direct_key`, gửi `TEXT` qua `POST /api/conversations/{id}/messages` (`clientId` UUID), nhận realtime qua WS thuần JSON `/ws` mang `syncEventId`, thực thi `403 BLOCKED` trước `403 CONTACT_REQUIRED` ở cả mở chat & gửi tin, giới hạn 30 tin/10s. | `ConversationController/Service`, `MessageController/Service`, `ChatWebSocketHandler`, `RealChatRepository`, `NewChatFragment`, `ChatRoomFragment` (`D-01`..`D-04`). |
| **REQ-messaging-offline-first** | Hàng đợi gửi bền vững Room (`SENDING`/`FAILED`) + WorkManager chuỗi theo phiên `"send-messages:<sessionKey>"` (`APPEND_OR_REPLACE`, chỉ truyền `clientId`, `NetworkType.CONNECTED`), phân loại lỗi retry/fail/401, Gửi lại giữ `clientId` / Xóa tin lỗi; `GET /api/sync/bootstrap` nhất quán + `GET /api/sync?after=` gap-safe + `410 SYNC_CURSOR_EXPIRED`; Event Applier idempotent nhận batch `/sync` theo thứ tự; WS chỉ requestSync cho cả Chat và Phase 4 Contact events; banner mất kết nối trên `ChatListFragment` & `ChatRoomFragment`. | `SendMessageWorker`, `SyncEngine`, `SyncEventApplier`, `ChatWebSocketManager`, `SyncService`, `SyncEventWriter`, `V5__sync_and_messaging.sql`, `AppDatabase` v2 (`D-03`, `D-05`..`D-08`). |
| **REQ-messaging-delivery-read-status** | Vòng đời `SENDING -> SENT -> DELIVERED -> READ` (nhóm lên `DELIVERED`/`READ` khi ít nhất 1 thành viên khác đạt mốc); `POST /delivered` khi lưu Room và `POST /read` khi `ChatRoomFragment` đang `RESUMED`; watermark đơn điệu & `last_delivered_message_id >= last_read_message_id`; chỉ báo `typing` (`typing.start` max 1/3s, tự tắt 5s). | `ReceiptService`, `MessageEntity.status`, `ChatWebSocketHandler` typing throttle, `TypingTracker` (`D-11`). |
| **REQ-messaging-group-chat** | Tạo nhóm từ bạn bè (`ACCEPTED`, >= 2 người được chọn, tối đa 50 thành viên), `GroupInfoFragment` riêng trong `nav_chat.xml`, đổi tên nhóm, `muted` cá nhân, thêm thành viên (chỉ bạn `ACCEPTED`), xóa/cấp quyền `ADMIN`, bàn giao `ADMIN` + rời nhóm nguyên tử trong 1 transaction (3 trường hợp), tin `SYSTEM` EN/VI idempotent, chế độ chỉ đọc khi `left_at != NULL` (`403 NOT_A_MEMBER`), cô lập lịch sử nghiêm ngặt `serverId > visible_from_message_id` + purge Room & FTS4 an toàn cho `serverId = NULL`. | `ConversationService` group ops, `GroupInfoFragment`, `GroupCreateFragment`, `AppDatabase` `Migration(1, 2)` (`D-09`, `D-10`, `D-12`, `D-14`). |
| **REQ-messaging-search** | Tìm kiếm offline dùng chung Room FTS4 `message_fts` với `MessageText.normalize` (lowercase, bỏ dấu kết hợp, `đ/Đ -> d`), an toàn ký tự đặc biệt `MATCH` & đầu vào rỗng; tìm theo phòng (đếm + Prev/Next) và toàn cục trên `ChatListFragment` (tách tên hội thoại vs *"Tin nhắn trên thiết bị"*, bấm vào nhảy đúng vị trí tin kể cả ngoài trang đang tải); tuân thủ `serverId > visible_from_message_id` và tự dọn FTS khi xóa cache. | `MessageDao` FTS4 queries, `MessageText`, `ChatListFragment`, `ChatRoomFragment` (`D-13`, `D-14`). |

---

## 1. Hiện trạng Codebase: `main`/`planning` vs `origin/phase4_NP`

Việc nắm rõ trạng thái nhánh Git hiện tại là **bắt buộc** để lập kế hoạch tích hợp Phase 5 không bị lệch ranh giới:

1. **Đã có sẵn trên nhánh hiện tại (`planning` / `main` tại `b35cc02`..`ee23949`):**
   - **Database & Migrations (`backend/src/main/resources/db/migration/`):**
     - `V1__initial_schema.sql`: đủ 13 bảng (`users`, `user_devices`, `contacts`, `contact_requests`, `conversations`, `conversation_members`, `messages`, `sync_events`, v.v.), đầy đủ index (`idx_conv_members_user`, `idx_messages_conversation_id_desc`, `idx_sync_events_user_cursor`, `idx_sync_events_created`) và bật RLS trên mọi bảng. [VERIFIED: `V1__initial_schema.sql#L69-L200`]
     - `V3__account_deletion_grace.sql` & `V4__contacts_friend_flag.sql` (thêm `contacts.is_friend BOOLEAN NOT NULL DEFAULT FALSE`). [VERIFIED: `V4__contacts_friend_flag.sql#L1-L13`]
   - **Backend Auth/Security (`backend/src/main/java/com/utechat/backend/`):**
     - `SecurityConfig` (stateless JWT, `.anyRequest().authenticated()`), `JwtService` (`ACCESS` token với `sub`, `did`, `iatMs`, `exp`), `JwtAuthenticationFilter` (đọc `Authorization: Bearer`, kiểm `revoked_at IS NULL`, `status = 'ACTIVE'`, `iatMs >= token_valid_after`), `AuthenticatedActor(UUID userId, String deviceIdHash)`, `GlobalExceptionHandler`, `AccountService` (đã ghi `DEVICE_REVOKED` vào `sync_events` tại dòng 91-92), `AuthService`. [VERIFIED: `SecurityConfig.java#L18-L36`, `JwtService.java#L39-L64`, `JwtAuthenticationFilter.java#L33-L67`, `AccountService.java#L85-L93`]
     - `backend/pom.xml` hiện có `spring-boot-starter-parent:3.3.5`, `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `spring-boot-starter-security`, `flyway-core`, `jjwt:0.12.6`, `embedded-postgres:2.0.7` — **chưa có `spring-boot-starter-websocket`**. [VERIFIED: `backend/pom.xml#L6-L40`]
   - **Android Foundation & Auth (`app/`):**
     - `app/build.gradle.kts` + `gradle/libs.versions.toml` đã khóa sẵn: `room:2.6.1`, `work-runtime:2.10.0`, `okhttp:4.12.0`, `retrofit:2.11.0`, `lifecycle:2.8.7`, `navigation:2.8.5`, `room-testing:2.6.1`, `espresso-core:3.7.0`. [VERIFIED: `gradle/libs.versions.toml#L3-L18`, `app/build.gradle.kts#L57-L80`]
     - `AppDatabase` (`version = 1, exportSchema = true`) với 10 entities, `1.json`, `MessageText` (`normalize`, `matchQuery`), `MessageDao` (`insert`, `search`), `ConversationDao`, `SyncStateDao` (`advance`), `UserDao`, `ContactDao`. [VERIFIED: `AppDatabase.java#L13-L41`, `MessageDao.java#L13-L27`, `SyncStateDao.java#L8-L24`]
     - `ChatApi` (interface rỗng), `ChatRepository` + `FakeChatRepository` (có test trong `FakeRepositoriesTest`) + `RealChatRepository` stub, `Injection.java`, `nav_chat.xml` (mới có `chatListFragment`). [VERIFIED: `ChatApi.java`, `ChatRepository.java`, `FakeChatRepository.java`, `RealChatRepository.java`, `nav_chat.xml`]

2. **Đang nằm trên nhánh remote `origin/phase4_NP` (CHƯA merge vào `main`/`planning`):**
   - **Backend Phase 4:**
     - `ContactPolicyService`: `lockPair(UUID a, UUID b)` (dùng `pg_advisory_xact_lock` SHA-256 trên `(lo, hi)` trong transaction), `isBlockedEitherWay(a, b)`, `isBlockedBy(blocker, target)`, `areAcceptedFriends(a, b)` (kiểm tra đủ 2 dòng `is_friend AND NOT blocked` bằng 1 câu SQL). [VERIFIED: `origin/phase4_NP:ContactPolicyService.java#L13-L49`]
     - `SyncEventWriter`: `@Transactional(propagation = Propagation.MANDATORY) write(UUID recipient, String event, String entity, String id, Object payload)` dùng `JdbcTemplate` + `ObjectMapper` (`?::jsonb`). [VERIFIED: `origin/phase4_NP:SyncEventWriter.java#L12-L31`]
     - `ContactsService` & `ContactController` (11 endpoint danh bạ + ghi `sync_events` cho `CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED` với payload `{contactUserId, isFriend, blocked, removed, user?}`). [VERIFIED: `origin/phase4_NP:ContactsService.java#L104-L236`, `origin/phase4_NP:ContactController.java#L12-L44`]
     - `LookupRateLimiter`, `TimeConfig` (`@Bean Clock`), `GlobalExceptionHandler` xử lý thêm thiếu param.
   - **Android Phase 4:**
     - `SessionManager` mở rộng thành `CurrentUserIdProvider` (`sessionKey()`, `sessionChanges()`, `runIfCurrent()`, `setOnSessionChanged()`, `saveAuthenticated()`, `versionKey()`). [VERIFIED: `origin/phase4_NP:SessionManager.java#L28-L162`]
     - `UserDao.upsertPreservingPrivacy(UserEntity)` bảo toàn `allowMessagesFromNonContacts` và `phone` khi API danh bạ không trả các trường đó. [VERIFIED: `origin/phase4_NP:UserDao.java#L24-L40`]
     - `ContactDao` bổ sung `deleteContact`, `deleteFriend`, `deleteRequest`, `deleteRequestsFor`, `getPendingRequestsWithUsers`, `incomingCount`. [VERIFIED: `origin/phase4_NP:ContactDao.java#L11-L21`]
     - `RealContactsRepository`, `ContactApi`, `ContactDtos`, `ContactErrorMapper`, `ContactsViewModel`, `ContactsListFragment` (3 màn con trong `nav_contacts.xml` vẫn đang trỏ `ContactsDestinationFragment` tạm do Tv2 tiếp tục hoàn thiện UI). [VERIFIED: `origin/phase4_NP:nav_contacts.xml#L12-L14`]
   - **Lưu ý quan trọng khi tích hợp với `origin/phase4_NP`:**
     1. Nếu Phase 5 triển khai trước khi `origin/phase4_NP` được merge vào `main` (hoặc rebase lên sau khi merge), Phase 5 dùng chung trực tiếp hợp đồng `ContactPolicyService`, `SyncEventWriter`, `UserDao.upsertPreservingPrivacy`, `ContactDao`, `CurrentUserIdProvider`, và `GET /api/users/lookup?phone=`. Tránh tạo class trùng tên/xung đột với `origin/phase4_NP`; mọi thay đổi ở `SyncEventWriter` phải giữ nguyên chữ ký `write(UUID, String, String, String, Object)` và **giữ `SyncEventWriterTest` trên H2 xanh** (không nhét SQL đặc thù PostgreSQL như `pg_advisory_xact_lock` thẳng vào code bắt buộc chạy trên H2).
     2. `origin/phase4_NP` hiện dùng `ArchTaskExecutor.getInstance().executeOnMainThread(...)` trong `RealContactsRepository` và `SessionManager` — **Phase 5 tuyệt đối không lặp lại lỗi này**, luôn dùng `Handler(Looper.getMainLooper())::post` qua `Executor mainExecutor` để pass `lintDebug`.

---

## 2. Backend Spring Boot 3: WebSocket Thuần JSON (`/ws`)

### 2.1 Cấu hình Dependency & Handshake Bảo mật
- Thêm `spring-boot-starter-websocket` vào `backend/pom.xml` (managed by Spring Boot `3.3.5`). [VERIFIED: `backend/pom.xml#L8-L9`]
- Đăng ký `WebSocketConfigurer` (`@Configuration @EnableWebSocket`) ánh xạ **`/ws`** tới `ChatWebSocketHandler` kèm `WsAuthHandshakeInterceptor` (extends `HandshakeInterceptor`) [CITED: https://docs.spring.io/spring-framework/reference/web/websocket/server.html]:
  1. **Thứ tự lọc HTTP Upgrade:** `SecurityConfig` giữ `.anyRequest().authenticated()` [VERIFIED: `SecurityConfig.java#L27`]. Khi Android OkHttp gửi `GET /ws` kèm header `Authorization: Bearer <token>`, `JwtAuthenticationFilter` chạy TRƯỚC `WebSocketHttpRequestHandler`, xác thực JWT (`typ == "ACCESS"`, `revoked_at IS NULL`, `users.status = 'ACTIVE'`, `iatMs >= token_valid_after`) và gán `AuthenticatedActor(userId, deviceIdHash)` vào `SecurityContextHolder`. [VERIFIED: `JwtAuthenticationFilter.java#L31-L67`]
  2. **Chặn JWT trong URL Query Params (`DEPLOYMENT_SECURITY.md#L25`, `D-16`):**
     Trong `WsAuthHandshakeInterceptor.beforeHandshake(...)` (và/hoặc filter đầu chuỗi), kiểm tra `request.getURI().getRawQuery()` / query parameter keys không phân biệt hoa thường (`token`, `jwt`, `access_token`, `bearer`, `authorization`): nếu tồn tại → từ chối ngay với HTTP `400` (`401` nếu thiếu header `Authorization`), trả `false`, và **tuyệt đối không ghi log query string**.
  3. **Bắt thông tin thiết bị & hạn JWT vào Session Attributes:**
     Trong `JwtAuthenticationFilter` (hoặc một truy vấn nhẹ trong `WsAuthHandshakeInterceptor` / mở rộng `AuthenticatedActor` hoặc request attribute): lưu `userId`, `deviceIdHash`, `deviceRowId` (`user_devices.id`), `issuedAtMs` (`iatMs`), và `tokenExpiresAt` (`claims.getExpiration().toInstant()`) vào `attributes` của `WebSocketSession`. Lý do cần cả `deviceRowId` lẫn `deviceIdHash`: `AccountService.revokeDevice(actor, targetId)` nhận `user_devices.id` (`targetId`), còn JWT mang `deviceIdHash` (`did`). [VERIFIED: `AccountService.java#L86-L90`, `JwtService.java#L39-L41`]
  4. **Origin policy:** OkHttp trên Android không gửi `Origin` header; Spring `OriginHandshakeInterceptor` mặc định cho phép request không có `Origin`. Cấu hình `.setAllowedOriginPatterns("*")` cho dev/test hoặc kiểm soát cấu hình nếu cần cho web test, không mở CORS credential cookie.

### 2.2 Quản lý Session Thread-Safe, Heartbeat 25s & Đóng Session (`4401`)
- **Session Registry keyed by `(userId, deviceIdHash)`:**
  - Một tài khoản đăng nhập được trên nhiều thiết bị (`API_CONTRACT.md#L163`), và một thiết bị có thể mở socket mới trong khi socket cũ đang đóng.
  - Cấu trúc trong `WebSocketSessionRegistry`:
    - `ConcurrentHashMap<UUID, ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>>>` (key1 = `userId`, key2 = `deviceIdHash`) + index phụ theo `deviceRowId` (`UUID`) hoặc lưu `deviceRowId` trong `ManagedWsSession`.
  - **Bọc `ConcurrentWebSocketSessionDecorator`:** `WebSocketSession` gốc của Tomcat/Servlet **không thread-safe** khi nhiều thread cùng gọi `sendMessage()` (sẽ ném `IllegalStateException: TEXT_FULL_WRITING`). Mọi session khi `afterConnectionEstablished` đều được bọc bằng `new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024)` [CITED: https://docs.spring.io/spring-framework/reference/web/websocket/server.html].
- **Heartbeat 25s (`API_CONTRACT.md#L163,L201`):**
  - Chọn một heartbeat ứng dụng thống nhất: Android gửi JSON ping mỗi 25 giây, server cập nhật lastSeen và kiểm tra phiên/thiết bị rồi trả JSON pong. OkHttp pingInterval=0; protocol PING/PONG không thay thế heartbeat ứng dụng và không được dùng để suy ra JSON ping đã chạy.
    - Protocol PING vẫn được Tomcat trả PONG theo chuẩn; không dựa vào handlePongMessage ở server để nhận biết PING do client gửi. Timer JSON ping thuộc đúng socket/session, hủy khi disconnect/logout và không nhân đôi sau reconnect.
    - App-level JSON frame: khi nhận `{"type":"ping"}`, server kiểm tra nhanh token chưa hết hạn (`Instant.now().isBefore(tokenExpiresAt)`) và thiết bị chưa bị thu hồi (`user_devices.revoked_at IS NULL AND token_valid_after <= iatMs AND users.status = 'ACTIVE'`), cập nhật `last_seen_at` (throttle ~60s/lần tránh spam DB), rồi trả `{"v":1,"type":"pong","id":"<uuid>"}`.
  - Tác vụ định kỳ `@Scheduled(fixedDelay = 30_000)` quét các session đang mở:
    - Nếu `Instant.now().isAfter(tokenExpiresAt)` → đóng ngay với `CloseStatus(4401, "SESSION_EXPIRED")`.
    - Nếu không có hoạt động > `75s` (gấp 3 lần chu kỳ ping 25s) → đóng session treo (`CloseStatus.SESSION_NOT_RELIABLE`).
- **Thu hồi thiết bị & Hết phiên tức thì (`DEVICE_REVOKED` / `4401`):**
  - Mở rộng các điểm thu hồi hiện có trong Phase 3 (`AccountService`, `AuthService`) để phát sự kiện đóng WS sau commit (`afterCommit()`):
    - `AccountService.revokeDevice(actor, targetId)` (`DELETE /api/account/devices/{id}`): sửa câu `UPDATE user_devices ... RETURNING device_id_hash` (và dùng `SyncEventWriter` để lấy `syncEventId`), sau commit gửi frame `{"v":1,"type":"auth.revoked","id":"...","syncEventId":<id>,"payload":{}}` tới đúng `(userId, revokedDeviceIdHash)` và đóng ngay với `CloseStatus(4401, "DEVICE_REVOKED")`.
    - `AuthService.logout(actor)`: sau commit (hoặc ngay lập tức) đóng WS của `(actor.userId(), actor.deviceIdHash())` với `CloseStatus(4401, "SESSION_EXPIRED")`.
    - `AccountService.changePassword(...)` & `AuthService.completePasswordReset(...)`: đóng toàn bộ WS của `userId` có `deviceIdHash != actor.deviceIdHash()` với `4401`.
    - `AccountService.scheduleDeletion(...)` & `AccountService.purgeExpiredDeletions(...)`: đóng toàn bộ WS của `userId` với `4401`.

### 2.3 Typing Indicator & Đẩy Sự Kiện Sau Commit (`afterCommit`)
- **Typing (`API_CONTRACT.md#L185,L199`, `FLOWS.md#F-13`):**
  - Client gửi `{"type":"typing.start","payload":{"conversationId":"..."}}` hoặc `typing.stop`.
  - Server kiểm tra: (1) `conversationId` hợp lệ & `actor` là thành viên đang hoạt động (`left_at IS NULL`) — không rò rỉ cho người ngoài; (2) với `typing.start`, throttle trên bộ nhớ theo khóa `(userId, conversationId)` bằng `Clock` inject được: nếu lần phát trước `< 3 giây` thì bỏ qua lặng lẽ (không lỗi socket); (3) đẩy `{"v":1,"type":"typing","id":"...","payload":{"conversationId":"...","userId":"...","state":"start"|"stop"}}` (KHÔNG có `syncEventId`) tới các thành viên `left_at IS NULL` khác đang online.
- **Quy tắc vàng `afterCommit` (`TransactionSynchronizationManager`):**
  - Mọi đẩy WebSocket cho dữ liệu có `syncEventId` (`message.new`, `message.status`, `conversation.updated`, `contact_request.new`, `contact_request.updated`, `contact.updated`, `auth.revoked`) **BẮT BUỘC** chỉ chạy trong `TransactionSynchronization.afterCommit()`. Nếu transaction rollback, không có frame giả nào được gửi; và khi người nhận nhận WS frame rồi gọi ngay `/sync` hoặc `/delivered`, dữ liệu chắc chắn đã commit xong trong PostgreSQL.
  - **Điểm ghép sạch với Phase 4:** Nếu `SyncEventWriter.write(...)` giữ danh sách sự kiện theo transaction (qua `TransactionSynchronizationManager`) và tự kích hoạt `SyncEventRealtimeDispatcher.onEventsCommitted(list)` trong `afterCommit()`, cả Phase 4 (`ContactsService`) lẫn Phase 5 (`MessageService`, `ConversationService`, `ReceiptService`, `AccountService`) đều tự động đẩy WebSocket mà không nhân đôi code!

---

## 3. PostgreSQL `/api/sync`, Bootstrap Nhất quán & Chống Nhảy Cóc

### 3.1 Xử lý Transaction Commit Khác Thứ Tự `sync_events.id` (`D-07`, `D-16`)
- **Vấn đề gốc rễ trên PostgreSQL:** `sync_events.id` là `BIGSERIAL` [VERIFIED: `V1__initial_schema.sql#L152`]. PostgreSQL cấp số sequence tại thời điểm `INSERT`, không phải lúc `COMMIT` [CITED: https://www.postgresql.org/docs/current/sql-createsequence.html]. Nếu giao dịch `T1` nhận `id = 101` (chưa commit), giao dịch `T2` nhận `id = 102` và commit trước `T1`, truy vấn ngây thơ `WHERE user_id = ? AND id > 100` sẽ chỉ thấy `102`, nâng `nextCursor = 102`, và **bỏ sót vĩnh viễn `101`** khi `T1` commit sau đó!
- **Giải pháp chuẩn không cần khóa toàn cục (Lock-free MVCC Horizon via `barrier_xid`):**
  1. Trong Flyway `V5__sync_and_messaging.sql`:
     - Thêm cột `barrier_xid xid8` vào `public.sync_events` (backfill các dòng cũ bằng `'0'::xid8` hoặc `xmin::text::xid8` tùy chuẩn, cụ thể `ADD COLUMN barrier_xid xid8 NOT NULL DEFAULT pg_snapshot_xmax(pg_current_snapshot())`; các dòng sẵn có trước migration đặt `DEFAULT '0'::xid8` khi `ALTER TABLE`, rồi đổi `DEFAULT pg_snapshot_xmax(pg_current_snapshot())`!).
     - Thêm trigger `BEFORE INSERT` trên `public.sync_events`:
       ```sql
       CREATE OR REPLACE FUNCTION public.fn_sync_events_set_barrier() RETURNS TRIGGER AS $$
       BEGIN
           PERFORM pg_current_xact_id();
           NEW.barrier_xid := pg_snapshot_xmax(pg_current_snapshot());
           RETURN NEW;
       END;
       $$ LANGUAGE plpgsql;
       ```
       *Lưu ý cực hay:* Vì `SyncEventWriter` trên Java (`SyncEventWriterTest` chạy H2!) không cần sửa câu SQL `INSERT`, trigger chỉ chạy trên PostgreSQL thật (`EmbeddedPostgres` + Supabase), hoàn toàn tương thích với test H2 của Phase 4!
  2. **Tại sao `barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())` an toàn tuyệt đối hơn `tx_xid < xmin`?**
     - Giả sử `T_old` (`xid=499`) bắt đầu trước nhưng chèn `sync_events` (`id=102`) *sau* khi `T_new` (`xid=500`) đã chèn `sync_events` (`id=101`) mà `T_new` chưa commit:
       - Tại thời điểm `T_old` chèn `id=102`, `T_new` đã gọi `pg_current_xact_id()` nên `xid=500` nằm trong `pg_current_snapshot()`, khiến `pg_snapshot_xmax` của `T_old` ít nhất là `501` → `102.barrier_xid = 501`.
       - Khi `T_old` commit mà `T_new` (`xid=500`) vẫn mở, `pg_snapshot_xmin(pg_current_snapshot())` của `/api/sync` là `500`.
       - Điều kiện `barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())` đánh giá `501 <= 500` → **FALSE** → giữ `102` lại cho đến khi `T_new` (`xid=500`, `id=101`) kết thúc (commit hoặc rollback)!
       - Khi `T_new` commit, `pg_snapshot_xmin` tăng lên `>= 501` → cả `101` và `102` đều thỏa mãn `barrier_xid <= xmin` và được trả ra cùng lúc theo `ORDER BY id ASC`!
     - Đồng thời, trên single-threaded JDBC tests dùng 2 connection song song mô phỏng `T1` mở chưa commit (`id=101`) và `T2` commit (`id=102`), giải pháp này **không bao giờ block/deadlock Connection 2** như `pg_advisory_xact_lock` toàn cục!
  3. **Lưu ý vận hành nhỏ:** `pg_snapshot_xmin` sẽ tạm giữ không cho `/sync` vượt qua một giao dịch **đang ghi** kéo dài. Vì tất cả các service ghi của UTEChat chạy giao dịch ngắn (`< 50ms`, `query-timeout: 5s`), thời gian chờ là mili-giây; trong `AccountService.purgeExpiredDeletions()` (`@Scheduled`), nên xử lý từng user trong một transaction ngắn thay vì giữ 1 transaction lớn cho toàn bộ danh sách user hết hạn.

### 3.2 Bootstrap Snapshot + Cursor Nhất quán (`D-06`) & `410 SYNC_CURSOR_EXPIRED`
- **Endpoint mới:** `GET /api/sync/bootstrap`
- **Cô lập giao dịch (`REPEATABLE READ READ ONLY`):**
  Trong PostgreSQL, giao dịch `REPEATABLE READ` đóng băng một MVCC snapshot duy nhất ngay tại câu lệnh SQL đầu tiên trong giao dịch và dùng lại đúng snapshot đó cho mọi câu lệnh tiếp theo [CITED: https://www.postgresql.org/docs/current/transaction-iso.html].
  1. **Câu lệnh 1 (lấy cursor an toàn thuộc chính snapshot vừa đóng băng):**
     ```sql
     SELECT GREATEST(
         COALESCE((SELECT MAX(id) FROM public.sync_events WHERE barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())), 0),
         COALESCE((SELECT min_available_cursor FROM public.sync_retention_state WHERE id = 1), 0)
     )
     ```
     *Tại sao dùng MAX toàn cục (không lọc `WHERE user_id = ?`)?* Vì nếu user mới (hoặc user lâu không có event) bootstrap khi `sync_events` đã chạy đến ID `5000` và từng purge tới `3000`, `syncCursor = 5000` đảm bảo sau này gọi `/sync?after=5000` sẽ không bị nhầm là cursor `0 < 3000` (`410`)! Và mọi event chưa commit tại thời điểm snapshot của `bootstrap` chắc chắn có `barrier_xid > xmin`, nên ID của chúng **lớn hơn** `syncCursor` (trừ trường hợp đã phân tích ở mục 3.1 mà `barrier_xid` đã chặn sạch) → `/sync?after=syncCursor` chắc chắn nhận đủ mọi thay đổi phát sinh đồng thời/sau snapshot!
  2. **Các câu lệnh tiếp theo trong cùng `REPEATABLE READ` transaction:**
     - `me`: hồ sơ hiện tại (`UserDto`, gồm `allowMessagesFromNonContacts`, `discoverable`).
     - `conversations`: các hội thoại đang tham gia (`cm.left_at IS NULL`), kèm `myMembership` (`role`, `visibleFromMessageId`, `lastDeliveredMessageId`, `lastReadMessageId`, `muted`, `leftAt`), `members[]` rút gọn (`{userId, displayName, avatarUrl, role, lastDeliveredMessageId, lastReadMessageId, leftAt}` — giữ thành viên active hoặc tất cả tùy nhu cầu hiển thị tin cũ, lưu ý không trả `phone` của người lạ), `lastMessage` (**CHỈ trả nếu `m.id > cm.visible_from_message_id`**, ngược lại `null`), `unreadCount` (`SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id AND m.sender_id <> ? AND m.id > GREATEST(cm.last_read_message_id, cm.visible_from_message_id)`).
     - `friends`, `blocked`, `contactRequests` (`PENDING`), và `users` (hồ sơ các peer cần thiết, tuân thủ chính sách chỉ lộ `phone` cho bạn bè).
- **Cơ chế Retention 30 ngày & `410 SYNC_CURSOR_EXPIRED`:**
  - Bảng mới trong `V5__sync_and_messaging.sql` (nhớ bật RLS):
    ```sql
    CREATE TABLE public.sync_retention_state (
        id INTEGER PRIMARY KEY CHECK (id = 1),
        min_available_cursor BIGINT NOT NULL DEFAULT 0 CHECK (min_available_cursor >= 0),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    INSERT INTO public.sync_retention_state(id, min_available_cursor) VALUES (1, 0) ON CONFLICT DO NOTHING;
    ALTER TABLE public.sync_retention_state ENABLE ROW LEVEL SECURITY;
    ```
  - Job `@Scheduled` mỗi ngày (`SyncRetentionService`) xóa trong transaction: xác định `safeCutoffId = MAX(id)` của các dòng `created_at < now() - interval '30 days' AND barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())`, xóa `WHERE id <= safeCutoffId`, và cập nhật `min_available_cursor = GREATEST(min_available_cursor, safeCutoffId)`.
  - Trong `GET /api/sync?after=<cursor>&limit=<1..100>`:
    - Nếu `after < 0` → `400 VALIDATION`.
    - Đọc `min_available_cursor` và trạng thái sequence (`SELECT last_value, is_called FROM public.sync_events_id_seq`):
      - Nếu `after < min_available_cursor` → **`410 GONE`** (`error.code = "SYNC_CURSOR_EXPIRED"`).
      - Nếu `is_called AND after > last_value` (cursor tương lai vô lý / sau reset DB) → **`410 SYNC_CURSOR_EXPIRED`**.
    - Truy vấn `limit + 1` dòng:
      ```sql
      SELECT id, event_type, entity_type, entity_id, payload_json, created_at
      FROM public.sync_events
      WHERE user_id = ? AND id > ? AND barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())
      ORDER BY id ASC
      LIMIT ?
      ```
    - Tính hasMore và nextCursor như cũ. Trong cùng snapshot REPEATABLE READ của sync, dùng cùng safeXmin để kiểm EXISTS sự kiện của actor sau nextCursor có barrier_xid > safeXmin; retryAfterMs=1000 nếu có, null nếu không. Client hẹn đúng một lần sync tiếp theo, không busy-loop hoặc coi batch rỗng là đã bắt kịp khi còn retryAfterMs; hủy lịch khi đổi phiên/ngắt kết nối, tiếp tục ở foreground/reconnect.

### 3.3 Các cập nhật bắt buộc cho `docs/API_CONTRACT.md` (`D-06`, `D-09`, `D-10`, `D-14`, `D-17`)
Planner phải đưa bước cập nhật `docs/API_CONTRACT.md` vào đầu kế hoạch:
1. Thêm `GET /sync/bootstrap` (cấu trúc response `{ me, conversations, friends, blocked, contactRequests, syncCursor }` và ngữ nghĩa snapshot nhất quán `REPEATABLE READ`).
2. Cập nhật `GET /sync?after=<syncEventId>&limit=` bổ sung `hasMore` trong response và lỗi **`410 SYNC_CURSOR_EXPIRED`** (kèm giải thích khi `after` nhỏ hơn mốc giữ lại 30 ngày hoặc vượt ngưỡng hợp lệ).
3. Bổ sung trường `visibleFromMessageId` (và `leftAt`) vào DTO thành viên / hội thoại trong `/sync/bootstrap`, `GET /conversations`, `GET /conversations/{id}`, và payload `conversation.updated`, khẳng định quy tắc lọc chặt **`id > visibleFromMessageId`** (không dùng `>=`).
4. Chuẩn hóa bàn giao `ADMIN` khi rời nhóm trên `DELETE /conversations/{id}/members/{userId}?successorUserId=<uuid>`: mô tả đủ 3 trường hợp (sole `ADMIN` còn thành viên khác bắt buộc truyền `successorUserId`, trả `409 ADMIN_HANDOVER_REQUIRED` nếu thiếu; còn `ADMIN` khác; thành viên cuối cùng).
5. Bổ sung `contact.updated` vào bảng Server → Client WebSocket events (Mục 3) cho khớp `CONTACT_UPDATED` đã cài đặt ở Phase 4.
6. Bổ sung **Bảng danh mục `systemEvent` & `systemParams`** (Mục 2.4): `GROUP_CREATED`, `GROUP_RENAMED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `MEMBER_LEFT`, `ROLE_CHANGED`.
7. Cập nhật Mục 5 Danh mục mã lỗi chính: bổ sung `SYNC_CURSOR_EXPIRED`, `ADMIN_HANDOVER_REQUIRED` (và giữ đầy đủ `ALREADY_FRIENDS`, `INVALID_STATE` từ Phase 4).

---

## 4. Các Bất Biến Nhắn Tin 1-1 & Nhóm (Backend)

| Bất biến | Cách thực thi chính xác ở Backend (`JdbcTemplate` + PostgreSQL) | Mã lỗi / HTTP |
|---|---|---|
| **Hội thoại 1-1 duy nhất (`direct_key`)** | `direct_key = minUuid + ":" + maxUuid` (so sánh chuỗi lowercase chuẩn UUID trùng thứ tự PostgreSQL). Bên trong `@Transactional`: `ContactPolicyService.lockPair(actor, target)` TRƯỚC → kiểm target `ACTIVE` → kiểm policy → `INSERT INTO conversations(...) ON CONFLICT (direct_key) DO NOTHING` + `INSERT INTO conversation_members ... ON CONFLICT DO NOTHING` → `SELECT` lại hội thoại. | `400 SELF`, `404 NOT_FOUND`, `403 BLOCKED`, `403 CONTACT_REQUIRED` |
| **Thứ tự kiểm quyền `BLOCKED` trước `CONTACT_REQUIRED`** | Ở cả `POST /conversations/direct` và `POST /conversations/{id}/messages` (với `DIRECT`): luôn gọi `policy.isBlockedEitherWay(actor, other)` **trước**. Chỉ khi không chặn mới đọc `target.allow_messages_from_non_contacts`; nếu `false` và `!policy.areAcceptedFriends(actor, other)` thì từ chối. | `403 BLOCKED` ưu tiên hơn `403 CONTACT_REQUIRED` |
| **Chặn gửi/đọc sau khi rời/bị xóa (`D-12`)** | Mọi thao tác trong phòng (`GET /conversations/{id}`, `GET /messages`, `POST /messages`, `POST /delivered`, `POST /read`, `PATCH/POST/PUT/DELETE` nhóm) kiểm tra `conversation_members` có `left_at IS NULL`. Nếu không có dòng hoặc `left_at IS NOT NULL` → từ chối ngay. | `403 NOT_A_MEMBER` |
| **Giới hạn 50 thành viên & Chỉ thêm bạn `ACCEPTED` (`D-10`)** | Khi `POST /conversations/group`: bắt buộc `distinctOthers.size() >= 2` (`FLOWS.md#L271`) và `1 + distinctOthers.size() <= 50`; kiểm tra mọi target `ACTIVE`, không block và `policy.areAcceptedFriends(actor, target)` (khóa `lockPair` theo thứ tự UUID tăng dần). Khi `POST /conversations/{id}/members`: khóa `conversations` (`FOR UPDATE`), kiểm `ADMIN`, lọc các target chưa active (`absent OR left_at IS NOT NULL`); nếu rỗng (request lặp) trả `204` không sinh `SYSTEM`/`sync_events`; kiểm `activeCount + newlyAdded.size() <= 50`. | `400 VALIDATION`, `403 NOT_ADMIN`, `403 BLOCKED`, `403 CONTACT_REQUIRED`, `409 GROUP_FULL` (hoặc `400 GROUP_FULL`) |
| **`visible_from_message_id` strict `>` (`D-14`)** | Khi thêm mới/thêm lại thành viên vào nhóm đã có tin: gán `visible_from_message_id = COALESCE((SELECT MAX(id) FROM messages WHERE conversation_id = ?), 0)` **trước** khi chèn tin `SYSTEM` `MEMBER_ADDED`. Mọi truy vấn `GET /messages`, `GET /conversations`, `GET /sync/bootstrap` lọc `m.id > cm.visible_from_message_id` (tuyệt đối không `>=`). | — |
| **Bàn giao `ADMIN` + rời nhóm nguyên tử (`D-10`)** | Trong 1 `@Transactional` với `SELECT ... FROM conversations WHERE id = ? FOR UPDATE`: đếm `activeCount` và `adminCount` (`left_at IS NULL`). Nếu người rời là `ADMIN` duy nhất và `activeCount > 1`, bắt buộc `successorUserId` phải là active member khác → `UPDATE` successor `role = 'ADMIN'` + `UPDATE` caller `left_at = now()` + chèn `SYSTEM` `MEMBER_LEFT` + ghi `sync_events` (`CONVERSATION_UPDATED` + `MESSAGE_CREATED` cho các thành viên còn lại, và **1 `CONVERSATION_UPDATED` cuối có `leftAt != null`** cho chính người vừa rời theo `D-12`). | `409 ADMIN_HANDOVER_REQUIRED` (hoặc `400 VALIDATION`), `403 NOT_A_MEMBER`, `403 NOT_ADMIN` |
| **Tin `SYSTEM` idempotent & `muted` cá nhân (`D-09`)** | Tin `SYSTEM` chỉ tạo bên trong cùng transaction khi thực sự có thay đổi (tạo nhóm, đổi tên sang chuỗi khác tên cũ, có ít nhất 1 thành viên thực sự được thêm/xóa/rời, hoặc đổi sang role khác role cũ). Thao tác lỗi, no-op hoặc đổi `muted` không bao giờ chèn `SYSTEM` hay spam `sync_events`. | `200`/`204` idempotent |
| **Chống trùng `clientId` (`D-05`)** | `messages` có `UNIQUE (sender_id, client_id)` [VERIFIED: `V1__initial_schema.sql#L144`]. Kiểm tra tồn tại `(actorId, clientId)` trước (hoặc dùng `INSERT ... ON CONFLICT (sender_id, client_id) DO NOTHING RETURNING *`): nếu đã có cùng `conversation_id`, trả lại đúng bản ghi cũ (`200`/`201`) mà không tạo bản sao hay `sync_events` thừa; nếu khác `conversation_id` trả `409 CONFLICT` / `400 VALIDATION`. | `201` / `200` idempotent |
| **Watermark đơn điệu (`D-11`)** | `delivered`: chỉ `UPDATE` khi `upTo > last_delivered_message_id` (đã chặn `upTo <= conversation.last_message_id` và `upTo > 0`). `read`: cập nhật `last_read = GREATEST(last_read, upTo)` và `last_delivered = GREATEST(last_delivered, last_read, upTo)` chỉ khi tăng thực sự; ghi `RECEIPT_UPDATED` bằng `SyncEventWriter` chỉ khi có ít nhất 1 cột tăng. | `204` idempotent |
| **Xóa tài khoản hết hạn 15 ngày (`AccountService.purge`)** | Mở rộng `AccountService.purge(userId)` (`AccountService.java#L134-L135`): trước khi đặt `left_at = now()`, nếu `userId` là `ADMIN` duy nhất của một nhóm còn thành viên active khác, tự động phong `ADMIN` cho thành viên vào sớm nhất (`ORDER BY joined_at ASC, user_id ASC LIMIT 1`) và phát `SYSTEM` + `CONVERSATION_UPDATED` để không bao giờ để lại nhóm không có `ADMIN`. | — |

---

## 5. Kiến trúc Android Java 17 Offline-First (`app/`)

### 5.1 Room `AppDatabase` Migration `v1 -> v2` (`D-05`, `D-14`, `ADR-020`)
- Hiện trạng `AppDatabase.java`: `version = 1, exportSchema = true`, có `if (BuildConfig.DEBUG) builder.fallbackToDestructiveMigration();` [VERIFIED: `AppDatabase.java#L14,L35`]. Vì bản Release không bật fallback hủy dữ liệu (và `D-14` yêu cầu migration thực), bắt buộc tăng `version = 2` và gắn `.addMigrations(MIGRATION_1_2)` **cho cả Debug lẫn Release**.
- Thay đổi Entity chính xác trên nền `1.json`:
  1. `ConversationMemberEntity`: thêm `@Nullable private Long visibleFromMessageId;` (SQLite: `ALTER TABLE member ADD COLUMN visibleFromMessageId INTEGER`). Giá trị `null` hoặc `0L` nghĩa là xem từ đầu. [VERIFIED: `1.json#L204-L253`]
  2. `MessageEntity`: thêm nullable Long queuedVisibleFromMessageId (ALTER TABLE message ADD COLUMN queuedVisibleFromMessageId INTEGER), giữ nguyên qua sync/ACK; giá trị null ở tin nhóm cũ không đủ điều kiện tự gửi. Thêm `@Nullable private String errorCode;` (SQLite: `ALTER TABLE message ADD COLUMN errorCode TEXT`) để lưu lý do lỗi bền vững (`BLOCKED`, `CONTACT_REQUIRED`, `NOT_A_MEMBER`, `NETWORK`, `VALIDATION`) qua xoay màn hình & khởi động lại app (`D-04`, `D-05`). [VERIFIED: `1.json#L255-L362`]
  3. **Bảo toàn metadata Phase 6 (`D-15`) & không đụng `message_fts`:** Tất cả metadata mở rộng (`mediaUrl`, `mime`, `sizeBytes`, `fileName`, `durationMs`, `width`, `height`, `latitude`, `longitude`, `systemEvent`, `systemParams`, `call`) lưu bằng JSON chuẩn trong cột `attachmentJson` sẵn có của `MessageEntity`. Vì `MessageFtsEntity` (`@Fts4(contentEntity = MessageEntity.class)`) chỉ lập chỉ mục `text` và `normalized_text` [VERIFIED: `MessageFtsEntity.java#L8-L18`], `MIGRATION_1_2` **không phải rebuild bảng ảo `message_fts`** và các trigger `room_fts_content_sync_message_fts_*` vẫn hoạt động bình thường!
- **Kiểm chứng Migration:** Sinh `app/schemas/com.utechat.data.local.AppDatabase/2.json` qua Room annotationProcessor và kiểm thử chuyển tiếp `v1 -> v2` bằng `MigrationTestHelper` (`room-testing:2.6.1` đã có sẵn trong `app/build.gradle.kts#L80`).

Quy tắc gửi nhóm bổ sung: TEXT send, thay đổi membership và SYSTEM cùng khóa conversations FOR UPDATE trước khi đọc quyền/cấp ID; GROUP request mang expectedVisibleFromMessageId khớp mốc hiện tại, lệch trả 409 MEMBERSHIP_CHANGED. Worker không tự gửi tin nhóm thiếu hoặc lệch queuedVisibleFromMessageId; server kiểm lại để chặn trường hợp cache offline chưa thấy remove/re-add.

### 5.2 Cô lập lịch sử nhóm (`visible_from_message_id`) & Bảo vệ tin `serverId = NULL` (`D-12`, `D-14`)
- **Dọn cache khi nhận mốc mới (trong cùng `@Transaction` Room):**
  1. Mốc `visibleFromMessageId` của `me` chỉ được phép **tăng** (`newVisible = Math.max(oldVisible, incomingVisible)`), tuyệt đối không hạ xuống khi nhận event/response cũ đến muộn.
  2. Khi cập nhật mốc mới `visibleFrom > 0`:
     ```sql
     DELETE FROM message
     WHERE conversationId = :conversationId
       AND serverId IS NOT NULL
       AND serverId <= :visibleFrom
     ```
     Trigger `room_fts_content_sync_message_fts_BEFORE_DELETE` trong SQLite tự động xóa các `docid` tương ứng khỏi `message_fts` [VERIFIED: `1.json#L421`].
  3. Nếu `conversation.lastMessageId != null && conversation.lastMessageId <= visibleFrom`, xóa hoặc tính lại `lastMessageId`/`lastMessageAt` từ tin hợp lệ còn lại để danh sách chat không lộ preview cũ.
- **Bảo vệ hai lớp khi đọc và chèn:**
  - Mọi đường ghi (`insertFromServer`, `insertBatchFromHistory`) trước khi ghi tin có `serverId != null` đều đọc `member` của `me` trong cùng transaction: nếu `serverId <= visibleFromMessageId` thì bỏ qua ngay (ngăn lịch sử/WS đến muộn chèn ngược tin cũ).
  - Mọi truy vấn hiển thị (`getMessagesForConversation(conversationId, myUserId)`) và tìm kiếm FTS4 (`searchInConversation`, `searchAll`) JOIN `member` của `myUserId` với điều kiện `(m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0))`.
- **An toàn cho tin `serverId IS NULL` (`SENDING` / `FAILED`):**
  - Điều kiện `AND serverId IS NOT NULL` khi purge bảo đảm không xóa nhầm tin lỗi hoặc tin đang chờ.
  - Khi thành viên được thêm lại nhóm (`left`: `true -> false`), **tuyệt đối không** tự động đưa các tin `FAILED` cũ vào hàng đợi gửi lại; chỉ khi người dùng chủ động bấm **"Gửi lại"** trên từng tin `FAILED` mới xếp việc mới với `clientId` cũ.

### 5.3 Hàng đợi Offline WorkManager `send-messages` (`D-05`)
- **Định danh chuỗi & Input tối giản:**
  - Tên chuỗi theo phiên: send-messages:<sessionKey>, policy APPEND_OR_REPLACE, constraint CONNECTED, exponential backoff từ 10 giây. sessionKey chỉ là generation:userId, không phải token; lưu generation bền vững để process restart cùng phiên không làm rơi job; không dùng chung node phụ thuộc giữa hai lần đăng nhập.
  - Data chỉ chứa clientId; tag gồm client:<clientId>, send-messages và session:<sessionKey> của lúc xếp việc. Worker kiểm tag phiên trước HTTP, chụp token/version theo guard rồi gắn Bearer cố định; không đọc token của lần đăng nhập mới cho job cũ.
- **Bẫy chuỗi `Result.failure()` với `APPEND_OR_REPLACE` (CỰC KỲ QUAN TRỌNG):**
  - Trong WorkManager, nếu Work A nằm đầu chuỗi `"send-messages"` (đang chạy hoặc retry) và Work B đã được append phía sau, **nếu Work A trả về `Result.failure()`, WorkManager tự động đánh dấu `FAILED` cho toàn bộ chuỗi phụ thuộc phía sau (Work B không bao giờ chạy!)**.
  - **Giải pháp bắt buộc trong `SendMessageWorker.doWork()`:**
    1. Kiểm tra `SessionManager.get()` (được init ở `UteChatApp.onCreate()`): nếu `token == null` hoặc `meId == null` → trả `Result.failure()` (dừng chuỗi cũ).
    2. Đọc `MessageEntity msg = messageDao.findByClientId(clientId)` trên Room:
       - Nếu `msg == null` (đã xóa hoặc đã `clearAllTables` khi logout) hoặc `!meId.equals(msg.getSenderId())` → trả `Result.success()` (bỏ qua an toàn).
       - Nếu `msg.getServerId() != null` hoặc `!"SENDING".equals(msg.getStatus())` → đã xử lý xong qua `/sync`/WS hoặc user đã hủy → trả `Result.success()`.
    3. Chụp `sessionKey` trước khi gọi `ChatApi.sendMessage(...)` đồng bộ (`execute()`).
    4. Phân loại kết quả:
       - **Thành công (`200`/`201`):** kiểm lại `sessionKey` còn khớp; trong `@Transaction` cập nhật `serverId`, `createdAt`, nâng `status` từ `SENDING` lên `SENT` (không hạ nếu WS đã nâng `DELIVERED`/`READ`), xóa `errorCode = null`, cập nhật `conversation.lastMessageId` → trả **`Result.success()`**.
       - **401:** chỉ cancel queueName(enqueuedSessionKey); clearIfCurrent(token, version) và notifyExpired khi thành công. 401 trễ của A không được xóa phiên/cache hay hủy hàng đợi B. Chuỗi của A riêng biệt nên kết quả failure không lan sang B.
       - **Lỗi nghiệp vụ `400`/`403`/`404`:** đọc `error.code` (`BLOCKED`, `CONTACT_REQUIRED`, `NOT_A_MEMBER`, `VALIDATION`, ...), cập nhật Room `status = "FAILED"`, `errorCode = parsedCode` (trong `@Transaction` kiểm khớp `sessionKey`), và **trả `Result.success()`** để các tin gửi tới hội thoại khác phía sau trong chuỗi vẫn tiếp tục được gửi! (Nếu lỗi `NOT_A_MEMBER`, có thể cập nhật kèm `member.left = true` hoặc kích hoạt `/sync` để cập nhật chế độ chỉ đọc).
       - **Lỗi tạm thời (`IOException`, `408`, `5xx`, `429`):**
         - Note: WorkManager chỉ kích hoạt `doWork()` khi thỏa mãn `NetworkType.CONNECTED`, nên thời gian mất mạng không làm tăng `getRunAttemptCount()`.
         - Với `429`: đọc header `Retry-After` (hoặc `error.details.retryAfterSeconds`). Vì `setBackoffCriteria` của WorkManager không nhận delay động theo từng lần retry, thực hiện chờ có kiểm soát (`sleep` từng lát 500ms, tối đa 60s, thoát ngay nếu `isStopped()` hoặc đổi `sessionKey`) trước khi trả `Result.retry()`.
         - Kiểm `getRunAttemptCount()`: các lần chạy là `0`, `1`, `2` (tổng 3 lượt gửi tự động). Nếu `getRunAttemptCount() < 2` → trả `Result.retry()`. Nếu đã là lượt thứ 3 (`getRunAttemptCount() >= 2`) → cập nhật Room `status = "FAILED"`, `errorCode = (429 ? "RATE_LIMITED" : "NETWORK")`, và trả **`Result.success()`**!
- **Hành động từ UI trên tin `FAILED` (`D-05`):**
  - **"Gửi lại":** chỉ thao tác chủ động, xác minh sender + active membership, cập nhật queuedVisibleFromMessageId bằng mốc hiện tại cho GROUP rồi đặt SENDING/errorCode=null (giữ clientId/localId), enqueue vào đúng queueName(capturedSessionKey). Re-add/bootstrap không thực hiện bước này.
  - **"Xóa tin lỗi":** chỉ xóa trên DISK nếu serverId IS NULL AND status=FAILED; không cancelAllWorkByTag/client node. Worker thấy dòng đã xóa trả success để tin khác phía sau tiếp tục chạy.
- **Dọn hàng đợi khi đăng xuất / đổi phiên:** chụp departingSessionKey trước khi clear, chỉ hủy queueName(departingSessionKey); giữ dọn Room trên DISK có guard phiên. Callback dọn trễ không được tra lại phiên mới rồi hủy nhầm.

### 5.4 OkHttp WebSocket, `SyncEngine` Single-Flight & `SyncEventApplier` Dùng Chung (`D-03`, `D-06`..`D-08`)
- **OkHttp WebSocket (`ChatWebSocketManager`):**
  - Tạo URL từ `BuildConfig.BACKEND_BASE_URL` (dùng `HttpUrl.get(...)`: `https -> wss`, `http -> ws`, path `/ws`), **không hardcode URL** (`DEPLOYMENT_SECURITY.md#L9`).
  - Client riêng dùng pingInterval(0) và readTimeout(0); manager gửi JSON ping mỗi 25s, chờ pong, hủy timer khi ngắt/đổi phiên và reconnect khi thiếu pong quá 75s. Giữ Bearer header, URL tập trung và logging BASIC Debug/NONE Release.
  - Quản lý vòng đời kết nối theo phiên đăng nhập (`SessionManager` `sessionKey` / `sessionChanges()`): tự động kết nối khi có token, ngắt ngay khi `clear()` / logout; không chạy ở chế độ Fake (`USE_FAKE_DATA`).
  - Backoff kết nối lại: `1s -> 2s -> 4s -> 8s -> 16s -> 30s` (tối đa `30s` theo `D-07`).
  - Trạng thái `LiveData<WsConnectionState>` (`CONNECTED`, `RECONNECTING`, `DISCONNECTED`):
    - Cả `ChatListFragment` và `ChatRoomFragment` quan sát và hiện thanh trạng thái nhỏ ngay dưới Toolbar khi `DISCONNECTED` hoặc `RECONNECTING`, chỉ chuyển sang `CONNECTED` (ẩn thanh) sau khi `onOpen` **và** lượt `SyncEngine` bù dữ liệu xong (`D-08`).
  - Đóng bởi server `4401` hoặc nhận frame `auth.revoked` hoặc HTTP `401` khi handshake: dừng reconnect ngay lập tức, gọi `session.clearIfCurrent(token, version)` + `SessionEvents.notifyExpired(version)` để `MainActivity` đưa về Login.
- **Single-Flight `SyncEngine` (`D-06`, `D-07`):**
  - Chạy tuần tự bằng `AtomicBoolean running` + `AtomicBoolean resyncRequested` trên `SYNC_NETWORK` executor riêng (tách khỏi `DISK` để không khóa đọc Room):
    - Nếu `syncStateDao.get(userId) == null` → gọi `GET /api/sync/bootstrap` → trong một `db.runInTransaction` (với `session.runIfCurrent(key, ...)` trên `DISK`): lưu `me`, `conversations`, `members` (kèm `visibleFromMessageId`), `users` (`upsertPreservingPrivacy`), `friends`/`blocked`/`contactRequests` (giữ nguyên các dòng `message` đang `SENDING`/`FAILED` có `serverId IS NULL`!), và `syncStateDao.advance(userId, bootstrap.syncCursor, now)`. Với các hội thoại nhóm cục bộ không còn nằm trong snapshot active của `bootstrap`, đánh dấu `member(me).left = true` để giữ lịch sử chỉ đọc (`D-12`).
    - Tiếp theo chạy vòng lặp GET /api/sync theo hasMore; nếu trang cuối có retryAfterMs thì giữ trạng thái đang bắt kịp và hẹn một lần sync không chặn sau tối thiểu 1000ms. Chỉ hoàn tất khi hasMore=false và retryAfterMs=null; timer có guard phiên và được gộp với WS/single-flight.
      - Gặp `410 SYNC_CURSOR_EXPIRED` → chạy lại `bootstrap` để reset cursor hợp lệ rồi tiếp tục vòng sync.
      - Gặp `200 OK` → trên `DISK` với `session.runIfCurrent(key, () -> db.runInTransaction(...))`: gọi `SyncEventApplier.applyBatch(myUserId, events)` **và** `syncStateDao.advance(userId, nextCursor, now)` trong cùng một transaction.
    - Sau khi dọn hết sự kiện, kiểm tra và gửi bù `POST /api/conversations/{id}/delivered` cho các tin nhắn mới nhất của người khác vừa tải về nếu chưa báo `delivered`.
- **Shared Idempotent `SyncEventApplier` (`D-03`):**
  - Chỉ áp dụng dữ liệu bền vững từ batch /sync theo thứ tự id, cùng transaction nâng cursor; dùng lại mapper cho bootstrap. WS data frame chỉ requestSync, không gọi applyRealtimeEvent hay upsert Room. Typing và self auth.revoked vẫn xử lý trực tiếp.
    - `MESSAGE_CREATED`: kiểm `visibleFromMessageId`; bảo toàn toàn bộ JSON metadata vào `attachmentJson`; `MessageDao.insert` giữ `localId` theo `clientId`; nâng `lastMessageId` / `lastMessageAt` nếu mới hơn; chỉ tăng `unreadCount` khi `serverId` mới chưa từng tồn tại trong Room, `senderId != myUserId`, và `serverId > lastReadId`.
    - `CONVERSATION_UPDATED`: cập nhật `ConversationEntity`, `UserEntity` (`upsertPreservingPrivacy`), `ConversationMemberEntity`; nếu `visibleFromMessageId` tăng → purge tin `serverId <= newVisible` (và FTS4 trigger); nếu `me.left == true` → giữ cache và UI tự khóa sang chỉ đọc (`D-12`).
    - `RECEIPT_UPDATED`: nâng `lastDeliveredId`/`lastReadId` đơn điệu (`Math.max`); cập nhật `status` các tin của mình (`READ` > `DELIVERED` > `SENT`, không hạ cấp); cập nhật `unreadCount` khi `userId == myUserId`.
    - `DEVICE_REVOKED`: kiểm tra đối chiếu thiết bị hiện tại (kết hợp `auth.revoked` / `401`).
    - `CONTACT_REQUEST_CREATED` & `CONTACT_REQUEST_UPDATED`: upsert `user_cache` (`upsertPreservingPrivacy`), upsert `PENDING` hoặc xóa lời mời đã đóng (`ACCEPTED`/`REJECTED`/`CANCELED`).
    - CALL_UPDATED: nhận payload seed {callId,state,endReason}, cập nhật đúng call trong attachmentJson của CALL đã cache, giữ các metadata khác; chưa có CALL khớp thì no-op có chủ đích và vẫn nâng cursor. Không thêm bảng/call engine Phase 6; sự kiện thực sự lạ vẫn rollback.
     - `CONTACT_UPDATED`: nếu `removed == true` → `contactDao.deleteContact(contactUserId)`; nếu `removed == false` → upsert `user_cache` (nếu có `user`) và upsert `ContactEntity(contactUserId, blocked)` (nếu `blocked == false && isFriend == false` thì `deleteContact`). **Mọi event Contact đều được áp dụng hoàn chỉnh trước khi transaction commit và nâng `lastSyncEventId`.**

### 5.5 Tìm kiếm FTS4 Tiếng Việt & Jump Tới Tin Ngoài Trang (`D-13`)
- **Xử lý an toàn `MATCH` & giữ xanh `RoomConvertersTest`:**
  - `RoomConvertersTest#ftsNormalizesBothFormsAndQuotesOperators` hiện assert `MessageText.matchQuery("\" OR")` trả về `"\"\"\"\"* \"or\"*"` [VERIFIED: `RoomConvertersTest.java#L34`]. Giữ nguyên `MessageText.normalize(String)` và `MessageText.matchQuery(String)` cho tương thích ngược, đồng thời bổ sung:
    - `MessageText.safeMatchQuery(String raw)`: chuẩn hóa bằng `normalize(raw)`, trích các token chữ/số/gạch dưới (`[\\p{L}\\p{Nd}_]+`) hoặc loại bỏ token toàn dấu câu (vì tokenizer `simple` của SQLite FTS4 chỉ lập chỉ mục chữ/số và có thể báo `SQLiteException: malformed MATCH expression` khi gặp chuỗi rỗng `""` hoặc token chỉ chứa ký tự `"*()`). Nếu không còn token hợp lệ → trả `null` và tầng DAO/Repository **trả ngay `Collections.emptyList()` mà không gọi `MATCH`**.
- **Tìm kiếm 2 phạm vi dùng chung `message_fts`:**
  - **Trong `ChatRoomFragment`:** gọi `MessageDao.searchInConversation(conversationId, myUserId, safeQuery)` (`JOIN message_fts ON m.localId = message_fts.rowid` + điều kiện `m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)`), trả danh sách `localId` theo thời gian để hiển thị `"1/N"` và nhảy tới kết quả trước/sau.
  - **Trên `ChatListFragment`:**
    1. Nhóm 1 — Hội thoại khớp tên (`title` chuẩn hóa bỏ dấu ở tầng Java hoặc query Room `conversation` đang active/cached).
    2. Nhóm 2 — **"Tin nhắn trên thiết bị"** (`strings_chat.xml`): `MessageDao.searchGlobal(myUserId, safeQuery, limit, offset)` JOIN `message_fts`, `conversation`, `member` (chỉ trả tin hợp lệ thuộc lịch sử được phép xem, giới hạn ví dụ `LIMIT 50`).
- **Nhảy tới đúng tin ngoài trang đang tải (`D-13`, `D-16`):**
  - `ChatRoomFragment` nhận thêm tham số tùy chọn `targetMessageLocalId` (`long`, mặc định `-1L`) và `targetServerId` (`long`, mặc định `-1L`).
  - Nếu `ChatRoomFragment` hiển thị danh sách tin Room theo cửa sổ (hoặc tải phân trang lịch sử cũ):
    - Nếu tin đích đã có trong Room (nhưng nằm trước trang mặc định 50 tin gần nhất), `ChatRoomViewModel` tự động mở rộng truy vấn Room để bao trọn `targetMessageLocalId` (ví dụ `WHERE localId >= ...` hoặc tăng limit cục bộ tới khi chứa tin đích) → `MessageAdapter.submitList(list, () -> scrollToAndHighlight(targetPos))`!
    - Trong `ChatRoomFragment`, hỗ trợ phân trang kéo lên đầu để gọi `GET /api/conversations/{id}/messages?before=<minServerId>&limit=50` và ghi tiếp vào Room.

### 5.6 Hợp đồng Điều hướng (`nav_chat.xml`), Điểm mở 1-1 (`D-02`) & Ranh giới Phase 6 (`D-15`)
- **Các màn hình trong `nav_chat.xml`:**
  - `chatListFragment` (startDestination — giữ nguyên ID để `BottomNavigationView` & `MainActivity` hoạt động đúng). [VERIFIED: `nav_chat.xml#L4-L6`, `MainActivity.java#L59`]
  - `newChatFragment`: nút "+" / tạo chat từ `ChatListFragment` → chọn bạn bè (từ `ContactsRepository.getFriends`), nhập SĐT tìm người lạ (dùng lại `ContactsRepository.lookup`), hoặc lối tắt sang `groupCreateFragment`.
  - `groupCreateFragment`: nhập tên nhóm (1–50 ký tự), chọn >= 2 bạn bè từ `ContactsRepository.getFriends`, gọi `POST /api/conversations/group`, mở thẳng `chatRoomFragment`.
  - `chatRoomFragment`: nhận `conversationId` (nullable), `peerUserId` (nullable), `targetMessageLocalId` (`-1L`).
    - Nếu truyền `peerUserId` mà chưa có `conversationId`: kiểm tra Room xem có hội thoại `DIRECT` với `peerUserId` không; gọi `ChatRepository.openOrCreateDirect(peerUserId)` → nhận `conversationId` hoặc hiện lỗi rõ (`403 BLOCKED` / `403 CONTACT_REQUIRED` kèm nút gửi lời mời kết bạn dùng `ContactsRepository`).
  - `groupInfoFragment`: mở từ menu/tiêu đề trên Toolbar của `chatRoomFragment` (khi `type == GROUP`), hỗ trợ đổi tên (`ADMIN`), bật/tắt `muted`, xem danh sách thành viên + huy hiệu `ADMIN`, thêm thành viên từ bạn bè, xóa/cấp quyền `ADMIN`, và rời nhóm (hiện dialog chọn người kế nhiệm nếu là `ADMIN` duy nhất còn thành viên khác).
- **Điểm mở chat 1-1 thống nhất cho Phase 4 (`D-02`):**
  - Khai báo `<deepLink app:uri="utechat://chat/direct/{peerUserId}" />` ngay trên `chatRoomFragment` trong `nav_chat.xml` **và/hoặc** một hàm helper công khai `ChatNavigation.openDirectChat(NavController nav, String userId)` (kèm cân nhắc global `<action>` trong `nav_graph.xml` nếu cần chuyển tab gọn). Với `<deepLink>`, màn hình `ContactsListFragment` (khi Tv2 gắn nút *"Nhắn tin"*) có thể mở trực tiếp đúng `chatRoomFragment` theo `userId` mà không bị lỗi nested graph của Navigation Component, đồng thời `MainActivity` tự ẩn `bottomNav` khi vào `chatRoomFragment` (`MainActivity.java#L59-L60`).
- **Ranh giới Phase 6 (`D-15`):**
  - Ẩn (`View.GONE` hoặc không đặt trong XML) các nút gửi Ảnh/Video/Voice/File/Vị trí và nút Gọi thoại trên `ChatRoomFragment` (không hiển thị nút disabled hay thông báo *"Chờ Phase 6"*).
  - Trong `MessageAdapter` (`ListAdapter + DiffUtil`), tách `getItemViewType(position)` rõ ràng: `TEXT_OUT`, `TEXT_IN`, `SYSTEM`, `NON_TEXT_SUMMARY_OUT`, `NON_TEXT_SUMMARY_IN`, `UNKNOWN_FALLBACK`. Các loại `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION`, `CALL` hiển thị thẻ tóm tắt tối giản có icon + nhãn i18n EN/VI từ `strings_chat.xml` (đọc metadata từ `attachmentJson`), không gắn click phát/mở.
- **Mở rộng Seam `ChatRepository` & `Injection` (`D-16`):**
  - Giữ nguyên chữ ký 3 phương thức cũ của `ChatRepository`: `getConversations(userId)`, `getMessages(conversationId)`, `sendMessage(conversationId, senderId, text)` và giữ `FakeChatRepository` hoạt động xanh để **`FakeRepositoriesTest` tiếp tục pass 100%** [VERIFIED: `ChatRepository.java#L10-L12`, `FakeRepositoriesTest.java#L76-L94`]. Chỉ bổ sung các method mới (kèm triển khai tối giản/stub tương thích trên `FakeChatRepository` và triển khai đầy đủ trên `RealChatRepository`).

---

## 6. Validation Architecture

Hệ thống kiểm thử Phase 5 tuân thủ `D-16` và `ADR-024`, chia làm 3 lớp kiểm chứng độc lập không phụ thuộc Docker hay Supabase ngoài:

### 6.1 Khảo sát Hạ tầng Thực thi Hiện tại
- **Backend (`cd backend; ./mvnw.cmd test`):** Đã chạy kiểm chứng thực tế trên máy Windows trong phiên này → **25/25 tests PASS** (`AuthApiTest`, `FlywayMigrationTest`, `HealthControllerTest`, `LocalFileOtpDeliveryTest`), khởi động PostgreSQL thật bằng `io.zonky.test:embedded-postgres:2.0.7`, chạy Flyway V1→V3→V4 sạch, **không cần Docker**.
- **Android (`./gradlew.bat testDebugUnitTest lintDebug`):** Lưu ý môi trường Windows trong sandbox hiện tại gặp `java.nio.file.AccessDeniedException` trên thư mục `app/build/generated/...` (do thuộc tính ReadOnly/ACL chủ sở hữu cũ trên thư mục `app/build`, tương ứng ghi chú *"Windows có thể cần build sạch khi output bị khóa"* trong `STATE.md#L82`). Khi thực thi Phase 5, cần chạy trên workspace có quyền ghi sạch `app/build` để xác nhận đủ `./gradlew testDebugUnitTest lintDebug` và `connectedDebugAndroidTest`.

### 6.2 Lệnh Chạy Kiểm Thử Bắt Buộc
- **Backend Spring Boot + EmbeddedPostgres:**
  ```powershell
  cd backend; .\mvnw.cmd test
  ```
- **Android JVM Unit Test & Lint:**
  ```powershell
  .\gradlew.bat testDebugUnitTest lintDebug --no-daemon
  ```
- **Android Instrumentation Test (Room, Migration `1 -> 2`, FTS4 thật trên Emulator/Device):**
  ```powershell
  .\gradlew.bat connectedDebugAndroidTest --no-daemon
  ```

### 6.3 Ma trận Kịch bản Bắt buộc (`D-16`)

| Lớp kiểm thử | File / Suite dự kiến | Các bất biến bắt buộc phải chứng minh |
|---|---|---|
| **Backend Integration (`EmbeddedPostgres` + `MockMvc` + WebSocket client)** | `MessagingConcurrencyAndPolicyTest`, `GroupChatApiTest`, `SyncAndBootstrapApiTest`, `WebSocketAuthAndRealtimeTest` | 1. `direct_key` mở lại chat cũ & 2 thread gọi `POST /conversations/direct` đồng thời chỉ tạo 1 row.<br>2. `403 BLOCKED` ưu tiên trước `403 CONTACT_REQUIRED` (kể cả khi tắt `allow_messages_from_non_contacts` sau khi đã mở chat).<br>3. Nhóm: tối đa 50 thành viên (`GROUP_FULL`), chỉ thêm bạn `ACCEPTED`, `403 NOT_ADMIN` / `403 NOT_A_MEMBER`, strict `serverId > visible_from_message_id`, bàn giao `ADMIN` + rời nhóm 1 transaction (3 ca: sole admin có người kế nhiệm, có admin khác, thành viên cuối cùng).<br>4. Idempotency: gửi lại cùng `clientId` không trùng message; request lặp/no-op/lỗi/`muted` không tạo thêm `SYSTEM` hay `sync_events`.<br>5. Watermark: không giảm, `READ` tự kéo `DELIVERED >= READ`, không spam `RECEIPT_UPDATED` khi giá trị giữ nguyên.<br>6. Sync & Bootstrap: `GET /api/sync/bootstrap` nhất quán `REPEATABLE READ` khi có ghi đồng thời; phân trang `/api/sync?after=&limit=`; `410 SYNC_CURSOR_EXPIRED` khi vượt cửa sổ retention; **test 2 JDBC connection commit `sync_events` ngược thứ tự ID (`102` commit trước `101`) chứng minh `/sync` không bao giờ nhảy cóc bỏ sót `101`**.<br>7. WebSocket `/ws`: xác thực header Bearer, **từ chối JWT trong query URL**, `typing.start` throttle 1/3s, đẩy frame sau commit (`afterCommit`), `DEVICE_REVOKED` đẩy `auth.revoked` và đóng `4401`, thành viên bị xóa nhận đúng 1 event cuối rồi ngừng nhận event mới và bị chặn `403 NOT_A_MEMBER`. |
| **Android JVM Unit Test (`JUnit 4 + Mockito + InstantTaskExecutorRule`)** | `FakeRepositoriesTest` (giữ xanh), `SendMessageWorkerTest`, `SyncEngineAndApplierTest`, `ChatViewModelTest`, `RoomConvertersTest` | 1. `SendMessageWorker`: offline giữ `SENDING`, phân loại `IOException`/`408`/`5xx`/`429` (tôn trọng `Retry-After`, max 3 lượt) vs `400`/`403`/`404` đánh dấu `FAILED` ngay kèm `errorCode` và **trả `Result.success()` để không chặn tin sau** vs `401` dừng chuỗi phiên; Gửi lại giữ `clientId`; Xóa tin lỗi chỉ xóa `serverId == null`.<br>2. `SyncEngine`: single-flight gộp burst WS; `410 SYNC_CURSOR_EXPIRED` kích hoạt bootstrap lại; chỉ nâng cursor trong cùng transaction với event đã áp dụng; bỏ qua response/WS đến muộn khi đổi phiên.<br>3. `SyncEventApplier`: xử lý idempotent đủ 8 event (Chat + CALL_UPDATED metadata + Contact `CONTACT_REQUEST_CREATED`/`UPDATED`, `CONTACT_UPDATED` gồm unfriend, unblock, `removed=true`), không hạ `visibleFromMessageId`, không nâng cursor khi nhận WS.<br>4. `ChatViewModel` & `MessageText`: an toàn ký tự `MATCH` & input rỗng, bảo toàn metadata non-TEXT, state xoay màn hình (`SavedStateHandle`). |
| **Android Instrumentation (`Room` / `FTS4` / `MigrationTestHelper` / `Espresso`)** | `AppDatabaseMigrationTest`, `MessagingRoomAndFtsTest`, `ChatNavigationAndSearchUiTest` | 1. `Migration(1, 2)` giữ nguyên dữ liệu cũ, thêm `visibleFromMessageId` và `errorCode`, giữ nguyên `message_fts` triggers.<br>2. FTS4 thật: tìm có dấu & không dấu (`chao` ↔ `chào`, `dang` ↔ `đặng`), ký tự đặc biệt `MATCH`, đầu vào rỗng không crash, xóa/purge cache làm sạch FTS.<br>3. Cô lập lịch sử nhóm thật trên SQLite: rời (chỉ đọc giữ tin cũ) → thêm lại (`serverId > visibleFromMessageId`, purge tin cũ & FTS, giữ tin `serverId IS NULL` không tự gửi lại) → đến muộn sự kiện cũ không làm lộ lại tin đã xóa.<br>4. Tìm kiếm toàn cục `ChatListFragment` → bấm kết quả tin nằm ngoài trang đang tải → mở `ChatRoomFragment` cuộn đúng tin khớp; render thẻ tóm tắt `IMAGE`/`AUDIO`/`VIDEO`/`FILE`/`LOCATION`/`CALL` từ `V2__seed_demo_data.sql` không crash và không mất metadata. |
| **Nghiệm thu thực tế 2 thiết bị/emulator với Backend thật (`-PuseFakeData=false`)** | Checklist E2E trên app + Spring Boot + PostgreSQL | Chat 1-1/nhóm realtime, tắt mạng gửi tin rồi bật mạng lại (`send-messages`), thay đổi danh bạ/chặn realtime đồng bộ qua `/ws`, rời/bàn giao `ADMIN`/thêm lại nhóm, thu hồi thiết bị đóng WS `4401`, tìm kiếm trong phòng & toàn cục. |

---

## 7. Open Questions (RESOLVED)

Mọi điểm thiết kế còn mở trong `05-CONTEXT.md` và quá trình khảo sát mã nguồn đều đã được chốt dứt khoát như sau:

1. **RESOLVED — Cơ chế server chống bỏ sót sự kiện khi transaction commit khác thứ tự `sync_events.id` (`D-07`, Discretion):**
   - **Quyết định:** Thêm cột `barrier_xid xid8` + trigger `BEFORE INSERT` (`pg_current_xact_id()` + `pg_snapshot_xmax(pg_current_snapshot())`) trong `V5__sync_and_messaging.sql`, và mọi truy vấn `GET /api/sync` / `GET /api/sync/bootstrap` lọc `barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())`. Phương án này không khóa toàn cục các luồng ghi song song, xử lý được cả trường hợp `T_old` lấy sequence sau `T_new`, và tương thích hoàn toàn với H2 test `SyncEventWriterTest` của Phase 4.
2. **RESOLVED — Endpoint và tham số bàn giao `ADMIN` + rời nhóm nguyên tử (`D-10`, `D-17`):**
   - **Quyết định:** Dùng `DELETE /api/conversations/{id}/members/{userId}?successorUserId=<uuid>` (query param tùy chọn thay vì HTTP DELETE body để tương thích chuẩn Retrofit `@DELETE` và mọi proxy HTTP). Khi `userId` là `ADMIN` duy nhất và nhóm còn thành viên khác mà thiếu `successorUserId`, trả `409 ADMIN_HANDOVER_REQUIRED`.
3. **RESOLVED — Endpoint & cấu trúc `GET /api/sync/bootstrap` + `410 SYNC_CURSOR_EXPIRED` (`D-06`, `D-17`):**
   - **Quyết định:** Bổ sung `GET /api/sync/bootstrap` chạy trong 1 transaction `REPEATABLE READ READ ONLY`, lấy `syncCursor` toàn cục (tối đa `MAX(id)` an toàn theo `barrier_xid <= xmin`, tối thiểu `sync_retention_state.min_available_cursor`) cùng snapshot `{ me, conversations, friends, blocked, contactRequests }`. Khi `after < min_available_cursor` (hoặc vượt sequence), `GET /api/sync` trả `410 SYNC_CURSOR_EXPIRED`.
4. **RESOLVED — Thay đổi Schema Room (`AppDatabase` v1 → v2) & Bảo toàn Metadata (`D-05`, `D-14`, `D-15`):**
   - **Quyết định:** Tăng `AppDatabase` lên `version = 2` với `Migration(1, 2)` tường minh (`ALTER TABLE member ADD COLUMN visibleFromMessageId INTEGER`, `ALTER TABLE message ADD COLUMN errorCode TEXT` và `ALTER TABLE message ADD COLUMN queuedVisibleFromMessageId INTEGER`), luôn gắn `.addMigrations(MIGRATION_1_2)` cho mọi build variant. Metadata của `IMAGE`/`AUDIO`/`VIDEO`/`FILE`/`LOCATION`/`CALL`/`SYSTEM` lưu JSON chuẩn trong cột `attachmentJson` sẵn có; `MessageFtsEntity` giữ nguyên cấu trúc để không phải rebuild bảng ảo FTS4.
5. **RESOLVED — Xử lý WorkManager `APPEND_OR_REPLACE` khi một tin lỗi nghiệp vụ (`D-05`):**
   - **Quyết định:** Với `400`/`403`/`404` hoặc khi hết 3 lượt retry, `SendMessageWorker` ghi `status = "FAILED"` + `errorCode` vào Room và trả **`Result.success()`** để WorkManager không fail dây chuyền các tin nhắn phía sau trong `"send-messages"`. 401 chỉ hủy queueName(enqueuedSessionKey); clearIfCurrent(token, version) thành công mới notifyExpired, không ảnh hưởng lần đăng nhập mới.
6. **RESOLVED — Điểm mở chat 1-1 thống nhất cho Phase 4 (`D-02`):**
   - **Quyết định:** Định nghĩa `<deepLink app:uri="utechat://chat/direct/{peerUserId}" />` trên `chatRoomFragment` trong `nav_chat.xml` cùng helper `ChatNavigation.openDirectChat(NavController, String peerUserId)` (kiểm tra dùng `userId` qua Room/server `POST /api/conversations/direct`), cho phép Tv2 nối nút *"Nhắn tin"* trên `ContactsListFragment` mà không gặp lỗi encapsulated nested graph.
7. **RESOLVED — Sự kiện WebSocket cho `CONTACT_UPDATED` & Chia sẻ Event Applier (`D-03`):**
   - **Quyết định:** Bổ sung `contact.updated` vào bảng WebSocket Server → Client trong `docs/API_CONTRACT.md` và kích hoạt phát sự kiện WebSocket từ `SyncEventWriter` qua `TransactionSynchronization.afterCommit()` để cả Phase 4 (`ContactsService`) lẫn Phase 5 tự động đẩy realtime đồng bộ với `/api/sync`.
8. **RESOLVED — Thay thế `ArchTaskExecutor` (`lintDebug` `RestrictedApi`):**
   - **Quyết định:** Mọi code mới trong Phase 5 dùng `Executor mainExecutor` mặc định `command -> new Handler(Looper.getMainLooper()).post(command)` thay cho `ArchTaskExecutor.getInstance()`, và không dùng Kotlin ở bất kỳ file nào.

---

*Phase 05 Research hoàn tất. Sẵn sàng chuyển sang bước lập kế hoạch chi tiết (`/gsd-plan-phase 5`).*
