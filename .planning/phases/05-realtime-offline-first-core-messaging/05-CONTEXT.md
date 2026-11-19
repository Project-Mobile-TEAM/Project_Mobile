# Phase 5: Realtime & Offline-First Core Messaging - Context

**Gathered:** 2026-11-19
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 5 xây dựng trọn vẹn phân hệ **Nhắn tin Thời gian thực & Offline-First Cốt lõi (Realtime & Offline-First Core Messaging)** cho UTEChat (`REQ-messaging-realtime`, `REQ-messaging-offline-first`, `REQ-messaging-delivery-read-status`, `REQ-messaging-group-chat`, `REQ-messaging-search` — phụ trách bởi Thành viên 4), bao gồm cả Backend Spring Boot 3 (REST + WebSocket `/ws`) và Android Client (`nav_chat.xml`, Room SQLite, WorkManager):
1. **Hội thoại 1-1 & Tạo hội thoại mới (`direct_key`):** Mở lại hội thoại 1-1 sẵn có hoặc tạo mới qua bạn bè và tìm SĐT người lạ (tái sử dụng API lookup & Repository Phase 4), thực thi kiểm tra `403 BLOCKED` trước rồi mới kiểm tra `allow_messages_from_non_contacts` (`403 CONTACT_REQUIRED`) ở cả lúc tạo hội thoại lẫn mỗi lần gửi tin. Cung cấp điểm mở chat 1-1 chuẩn theo `userId` để Phase 4 UI (Tv2) gắn nút "Nhắn tin" từ Danh bạ sau khi hoàn tất.
2. **Nhắn tin `TEXT` & Hàng đợi Offline-First (`send-messages`):** Gửi tin `TEXT` qua REST kèm `clientId` (UUID) chống trùng; lưu Room `SENDING`, xếp hàng tuần tự qua một chuỗi WorkManager `send-messages` (`NetworkType.CONNECTED`), phân loại lỗi rõ (retry có backoff cho mạng/`408`/`5xx`/`429` vs `FAILED` ngay cho `400`/`403`/`404` vs dừng phiên khi `401`), hỗ trợ Gửi lại (giữ nguyên `clientId`) và Xóa tin lỗi cục bộ.
3. **WebSocket thuần JSON (`/ws`), Bootstrap Snapshot & Đồng bộ (`/api/sync`):** Kết nối `/ws` bằng header `Authorization: Bearer <token>` trên HTTP Upgrade handshake (tuyệt đối không đặt JWT trong URL), gửi/nhận `typing`, nhận tin nhắn/biên nhận/cập nhật nhóm/thu hồi thiết bị/sự kiện danh bạ. Đồng bộ bù đơn điệu qua `GET /api/sync?after=<syncEventId>` với bộ xử lý idempotent dùng chung cho cả WS và `/sync` (gồm cả 3 sự kiện Contact của Phase 4), bootstrap snapshot+cursor nhất quán và mã `410 SYNC_CURSOR_EXPIRED`.
4. **Chat nhóm, Tin hệ thống `SYSTEM` & Quản lý thành viên (`GroupInfoFragment`):** Tạo nhóm (chọn từ bạn bè, tối đa 50 thành viên), đổi tên, thêm/xóa thành viên (chỉ thêm bạn `ACCEPTED`), phân quyền `ADMIN`, bàn giao `ADMIN` + rời nhóm nguyên tử, cô lập lịch sử theo `visible_from_message_id` (`serverId > visible_from_message_id`), sinh tin `SYSTEM` đa ngôn ngữ EN/VI, và vòng đời biên nhận `SENDING -> SENT -> DELIVERED -> READ` (`last_delivered >= last_read`).
5. **Tìm kiếm Offline FTS4 (`message_fts`) & Ranh giới Phase 6:** Tìm kiếm tiếng Việt có/không dấu (chuẩn hóa chữ thường, bỏ dấu, `đ/Đ -> d`) dùng chung `message_fts` ở cả trong `ChatRoomFragment` lẫn toàn cục trên `ChatListFragment`. Phase 5 chỉ gửi `TEXT` (và nhận `SYSTEM` từ server), ẩn sạch nút Media/Vị trí/Gọi thoại nhưng giữ nguyên metadata và thẻ tóm tắt an toàn cho tin non-TEXT để sẵn sàng nối Phase 6.

</domain>

<decisions>
## Implementation Decisions

### Luồng tạo hội thoại mới & Ghép nối với Phase 4
- **D-01:** Từ nút tạo hội thoại mới ở `ChatListFragment`, hỗ trợ cả chọn từ danh bạ bạn bè, ô nhập SĐT nhắn người lạ và lối tắt "Tạo nhóm" (chỉ chọn thành viên từ danh sách bạn bè):
  - Bắt buộc **dùng lại API `GET /api/users/lookup?phone=` và `ContactsRepository` của Phase 4**, tuyệt đối không viết lại logic tìm SĐT.
  - Nếu người nhận tắt nhận tin người lạ (`allow_messages_from_non_contacts = false` / `403 CONTACT_REQUIRED`), hiển thị *"Người này chỉ nhận tin nhắn từ bạn bè"* và cho phép gửi lời mời kết bạn; nếu bị chặn, hiển thị thông báo phù hợp.
  - Server kiểm quyền ở cả bước tạo hội thoại 1-1 lẫn mỗi lần gửi tin. Chọn lại cùng người phải mở lại hội thoại 1-1 hiện có (nhờ `direct_key` duy nhất).
- **D-02:** Phase 5 cung cấp một điểm mở chat 1-1 nhận `userId`, tái sử dụng hội thoại hiện có hoặc tạo mới qua server; kiểm chứng ngay bằng luồng tạo chat từ `ChatListFragment`. Thành viên làm UI Phase 4 (Tv2) sẽ nối nút *"Nhắn tin"* vào điểm này sau khi hoàn tất giao diện danh bạ. Chốt trước người phụ trách các file navigation/DI dùng chung (`app/src/main/res/navigation/nav_graph.xml`, `app/src/main/java/com/utechat/di/Injection.java`); chỉ xem tích hợp hoàn tất khi bấm từ Danh bạ mở đúng phòng chat. — **Reversibility:** costly — định nghĩa hợp đồng điều hướng giữa `nav_contacts` và `nav_chat`.
- **D-03:** Dùng chung **một bộ xử lý sự kiện idempotent** cho WebSocket `/ws` và `GET /api/sync`, tái sử dụng DAO/Repository Phase 4 (`ContactDao`, `UserDao`). Áp dụng đầy đủ các sự kiện Chat (`MESSAGE_CREATED`, `CONVERSATION_UPDATED`, `RECEIPT_UPDATED`, `DEVICE_REVOKED`) và cả 3 sự kiện Contact (`CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED` — kể cả hủy kết bạn, bỏ chặn và xóa dòng cache `removed = true`). Xử lý lặp không tạo dữ liệu trùng; chỉ nâng cursor sau khi thay đổi Room được lưu thành công trong cùng transaction. **Không bỏ qua event Contact rồi vẫn nâng cursor**, vì có thể mất cập nhật vĩnh viễn. — **Reversibility:** costly — khóa kiến trúc Event Applier dùng chung giữa Phase 4, Phase 5 và Phase 6; dữ liệu bền vững áp dụng theo thứ tự batch /sync, WebSocket chỉ báo thay đổi để gọi sync (typing và auth.revoked xử lý trực tiếp).
- **D-04:** Trong phòng chat 1-1, khi bị chặn (`403 BLOCKED`) hoặc đối phương tắt nhận tin người lạ (`403 CONTACT_REQUIRED`), giữ lịch sử cũ, đánh dấu tin vừa gửi `FAILED` kèm lý do rõ (Bị chặn / Cần kết bạn để nhắn tin) và hiện banner cảnh báo trên khung nhập.

### Hàng đợi Offline, tin `FAILED` & Đồng bộ (`Bootstrap`, `/sync`, `/ws`)
- **D-05:** Hàng đợi gửi ngoại tuyến qua WorkManager (`send-messages`, `ExistingWorkPolicy.APPEND_OR_REPLACE`, chỉ truyền `clientId`; tên chuỗi thực tế `send-messages:<sessionKey>`, gắn tag phiên để cách ly lần đăng nhập; sessionKey được lưu bền vững qua process restart của cùng phiên) và thao tác trên tin `FAILED`:
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
  - Khi kết nối lại: thiết lập WebSocket sẵn sàng nhận sự kiện, rồi chạy `/sync` phân trang đến hết. Nếu retryAfterMs khác null, còn sự kiện bị barrier giữ: hẹn một lần thử lại không chặn luồng, tối thiểu 1000ms, giữ cursor và trạng thái đang đồng bộ; dừng timer khi đổi phiên/ngắt kết nối, tiếp tục khi foreground/reconnect. Không cần đợi thêm WS frame mới, chỉ báo bắt kịp khi hasMore=false và retryAfterMs=null.
  - Chỉ có một lượt sync chạy tại một thời điểm (single-flight); nếu WebSocket báo thay đổi trong lúc sync, đánh dấu để chạy tiếp trước khi kết thúc.
  - Áp dụng sự kiện idempotent; lưu thay đổi dữ liệu và `nextCursor` do server trả trong cùng transaction Room.
  - **Không dùng điều kiện `eventId == lastSyncEventId + 1`** để phát hiện thiếu sự kiện; phía server xử lý an toàn trường hợp các transaction commit khác thứ tự ID. — **Reversibility:** costly — khóa giao thức đồng bộ xuyên suốt Backend & Android.
- **D-08:** Khi WebSocket mất kết nối (`Disconnected` / `Reconnecting`), hiện thanh trạng thái nhỏ dưới Toolbar trên **cả `ChatListFragment` và `ChatRoomFragment`**, tự ẩn ngay khi nối lại & đồng bộ xong.

### Chat nhóm, Tin hệ thống `SYSTEM`, Biên nhận & Quản lý thành viên
- **D-09:** Server sinh tin `SYSTEM` (`systemEvent` + `systemParams`) trong cùng transaction với thay đổi nhóm (tạo nhóm, đổi tên, thêm/xóa/rời nhóm, đổi quyền `ADMIN`) và `sync_events`; Android hiển thị qua chuỗi EN/VI. Thao tác thất bại, không thay đổi dữ liệu hoặc request lặp **không được tạo thêm tin `SYSTEM`** (hoặc `sync_events`). Thiết lập `muted` là cài đặt cá nhân, không sinh tin `SYSTEM`.
- **D-10:** Có màn `GroupInfoFragment` riêng trong `nav_chat.xml` (sửa tên nhóm, bật/tắt `muted` cá nhân, xem danh sách thành viên + nhãn `ADMIN`, thêm thành viên từ bạn bè, xóa/cấp quyền `ADMIN`, rời nhóm). Server kiểm quyền cho mọi thao tác quản trị (nhóm tối đa 50 thành viên, chỉ thêm bạn bè `ACCEPTED`). Khi rời nhóm:
  - `ADMIN` duy nhất và còn thành viên khác: chọn người kế nhiệm; **bàn giao quyền và rời nhóm trong cùng transaction**.
  - Đã có `ADMIN` khác: cho rời trực tiếp.
  - Là thành viên cuối cùng: cho rời trực tiếp, không bắt chọn người kế nhiệm.
- **D-11:** Hiển thị trạng thái `SENDING / SENT / DELIVERED / READ` (hoặc `FAILED`) nhất quán cho cả chat 1-1 và nhóm (trong nhóm lên `DELIVERED`/`READ` khi ít nhất 1 thành viên khác đạt mốc theo ADR-010); gọi `POST /conversations/{id}/read` khi phòng chat đang RESUMED, với mốc cao nhất thực sự nằm trong vùng nhìn sau layout, cao hơn lastReadId; không lấy max của toàn bộ danh sách đã tải, không xóa unread của tin còn chưa nhìn thấy. Bảo đảm watermark không giảm và `last_delivered_message_id >= last_read_message_id`.
- **D-12:** Khi thành viên rời/bị xóa khỏi nhóm (`left_at != NULL`): người bị xóa vẫn phải nhận được **sự kiện cuối** thông báo mất tư cách thành viên để app chuyển hội thoại sang **chế độ chỉ đọc** (ẩn khung nhập tin, hiện thông báo *"Bạn không còn là thành viên của nhóm này"*, giữ lịch sử tin trước thời điểm rời trong Room); sau đó server ngừng cấp sự kiện mới của nhóm và chặn gửi/tải tin sau mốc rời bằng `403 NOT_A_MEMBER`. Các tin đang chờ gửi không được retry vô hạn khi gặp `NOT_A_MEMBER`.

### Tìm kiếm FTS4 tiếng Việt, Cô lập lịch sử & Ranh giới Phase 6
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

### Kiểm thử bắt buộc, Hợp đồng API & Quy chuẩn Code
- **D-16:** Bắt buộc kiểm thử các bất biến dữ liệu, quyền truy cập và race condition ngay trong Phase 5 (không dồn sang Phase 7):
  - Backend (JUnit/MockMvc + `EmbeddedPostgres` thật, **không yêu cầu Docker**): `direct_key` (kể cả tạo đồng thời), `BLOCKED`/`CONTACT_REQUIRED`/`NOT_A_MEMBER` và quyền quản trị nhóm, giới hạn 50 người, thêm thành viên đúng điều kiện bạn bè `ACCEPTED`, phạm vi `visible_from_message_id` (`>`), bàn giao `ADMIN` và rời nhóm nguyên tử + trường hợp thành viên cuối cùng, chống trùng `clientId`, request lặp không sinh trùng tin `SYSTEM`/`sync_events`, watermark không giảm & `READ <= DELIVERED`, bootstrap snapshot/cursor, phân trang `/sync`, `410 SYNC_CURSOR_EXPIRED`, sự kiện trùng, transaction commit khác thứ tự ID (không giả định ID sự kiện của một người dùng liên tiếp), WebSocket Bearer handshake & chặn JWT trong query.
  - Android: Unit test JVM cho Repository/ViewModel/Worker + instrumentation test Room/FTS thật trên emulator/thiết bị (tìm kiếm tiếng Việt có/không dấu, phạm vi từng phòng vs toàn cục, ký tự đặc biệt `MATCH`, cập nhật FTS khi xóa cache, mở đúng tin ngoài trang đang tải từ tìm kiếm toàn cục, render mẫu non-TEXT & bảo toàn metadata REST→Room→UI, rời→thêm lại nhóm trên cùng thiết bị/thiết bị mới/offline/sự kiện muộn, retry giữ `clientId`, phân loại lỗi, phản hồi đến muộn, đổi tài khoản, kết nối lại & đồng bộ sự kiện Contact/Chat). Giữ `FakeRepositoriesTest` xanh.
  - Nghiệm thu thực tế bằng 2 tài khoản trên app dùng backend thật: chat 1-1/nhóm, mất mạng → gửi → kết nối lại, thay đổi danh bạ realtime, rời/tham gia lại nhóm và tìm kiếm (không chấp nhận chỉ chạy Fake hoặc mock).
  - Trước khi báo hoàn tất: `mvn test`, Android unit test, `lintDebug` và các instrumentation test liên quan phải pass; ghi rõ kết quả cùng phần chưa kiểm chứng.
- **D-17:** Tuân thủ tuyệt đối `GEMINI.md`, `AGENTS.md`, `docs/DEPLOYMENT_SECURITY.md`: 100% Java 17 (tuyệt đối không dùng Kotlin), Views + XML, ViewBinding, MVVM + LiveData, Room SQLite, WorkManager, Material 3 DayNight, i18n EN/VI (`strings_chat.xml`), và 100% comment Tiếng Việt sư phạm. Cập nhật đầy đủ các phần hợp đồng mở rộng trong `docs/API_CONTRACT.md` (bootstrap snapshot+cursor, `410 SYNC_CURSOR_EXPIRED`, `visibleFromMessageId`, bàn giao `ADMIN` khi rời nhóm, bảng danh mục `systemEvent`/`systemParams`).

### the agent's Discretion
- Bố cục chi tiết bong bóng tin nhắn, phân nhóm theo ngày (date separator), hiển thị chỉ báo đang nhập (`typing`) và chi tiết tạo kênh thông báo cục bộ tối giản nếu cần hoặc để trọn phần `NotificationChannel` sang Phase 6.
- Thiết kế cụ thể cơ chế server xử lý/chặn nhảy cóc khi transaction commit khác thứ tự `sync_events.id` và cấu trúc cụ thể view types của `MessageAdapter`.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing:**

### Architecture, Contracts & Security Specs
- `docs/API_CONTRACT.md` — Mục 1 (Quy ước chung, định dạng lỗi), Mục 2.3 (Danh bạ & `sync_events` Contact), Mục 2.4 (`conversations`, `messages`, `/sync`), Mục 3 (WebSocket `/ws` server↔client), Mục 4 (Rate limits), Mục 5 (Danh mục mã lỗi).
- `docs/FLOWS.md` — Mục 3 (F-10..F-14: gửi tin, hàng đợi WorkManager `send-messages`, receipts, typing, reconnect `/sync`), Mục 4 (F-15..F-16: nhóm), Mục 5 (F-17: tìm kiếm FTS4), Mục 10 (F-25..F-26), Mục 11.
- `docs/ERD.md` — Server tables (`conversations`, `conversation_members`, `messages`, `sync_events`, `contacts`, `users`) và Room 10 bảng (`conversation`, `member`, `message`, `message_fts`, `sync_state`, `user_cache`, `contact`, `contact_request`).
- `docs/ARCHITECTURE_DECISIONS.md` — ADR-008 (REST + WS thuần + `sync_events`), ADR-009 (Offline-first Room & WorkManager), ADR-010 (Watermark Receipts `last_delivered >= last_read`), ADR-016 (FTS4 tiếng Việt), ADR-018 (JWT & RBAC), ADR-020 (Room migration), ADR-021 (i18n & DayNight), ADR-023 (Kiến trúc Android), ADR-024 (Kiểm thử), ADR-025 (Anti-conflict), ADR-026 (Comment Tiếng Việt).
- `docs/DEPLOYMENT_SECURITY.md` — Bắt buộc HTTPS/WSS trên Release, URL cấu hình tập trung, Bearer header trên WS handshake (tuyệt đối không JWT trên URL), phân quyền tại server.
- `.planning/phases/04-contacts-friendships/04-CONTEXT.md` — D-01 (`is_friend` & block semantics), D-02 (ẩn nút Nhắn tin trong Phase 4), D-08 (ranh giới `sync_events`), D-10 (cô lập tài khoản khi đổi phiên).
- `GEMINI.md` & `AGENTS.md` — Ràng buộc 100% Java 17, 100% comment Tiếng Việt sư phạm.

### Database & Existing Codebase Assets
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` & `V4__contacts_friend_flag.sql` — Schema PostgreSQL hiện có (`conversations`, `conversation_members`, `messages`, `sync_events`, `contacts`).
- `backend/pom.xml` — Phụ thuộc Maven hiện tại (cần bổ sung `spring-boot-starter-websocket` khi lập kế hoạch backend Phase 5).
- `backend/src/main/java/com/utechat/backend/config/SecurityConfig.java` & `backend/src/main/java/com/utechat/backend/security/JwtService.java` — Chuỗi lọc Spring Security & JWT dùng lại cho REST + WS handshake.
- `app/build.gradle.kts` — Dependencies đã khóa sẵn (`room`, `work-runtime`, `okhttp`, `retrofit`, `lifecycle`, `espresso`, `room-testing`).
- `app/src/main/java/com/utechat/data/local/AppDatabase.java` — `version = 1` với đủ 10 entities; có `SyncStateDao.advance` và `MessageDao.insert`/`search`.
- `app/src/main/java/com/utechat/data/local/MessageText.java` — Sẵn `normalize(text)` (NFD + `đ/Đ -> d` + lowercase) và `matchQuery(text)`.
- `app/src/main/java/com/utechat/data/local/dao/ConversationDao.java`, `MessageDao.java`, `SyncStateDao.java`, `ContactDao.java`, `UserDao.java`.
- `app/src/main/java/com/utechat/data/local/entity/ConversationEntity.java`, `ConversationMemberEntity.java`, `MessageEntity.java`, `MessageFtsEntity.java`, `SyncStateEntity.java`.
- `app/src/main/java/com/utechat/data/repository/ChatRepository.java` & `app/src/main/java/com/utechat/data/remote/api/ChatApi.java` — Seam interfaces hiện có.
- `app/src/main/java/com/utechat/di/Injection.java` — Service Locator ghép `ChatRepository` và shared `DISK` executor.
- `app/src/main/res/navigation/nav_chat.xml` — Graph con của phân hệ Chat (hiện chỉ có `chatListFragment`).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MessageText.normalize(String)` và `MessageText.matchQuery(String)` đã cài đặt sẵn chuẩn hóa NFD, chuyển `đ/Đ -> d`, viết thường và thoát dấu ngoặc kép cho `MATCH`.
- `MessageDao.insert(MessageEntity)` đã tự xử lý giữ `localId` khi trùng `clientId` và tự điền `normalized_text` trong `@Transaction`, kèm sẵn câu truy vấn `MessageDao.search(conversationId, query)` JOIN `message_fts`.
- Đủ 10 Room Entities và DAOs đã tạo sẵn trong `app/src/main/java/com/utechat/data/local/`.
- `work-runtime`, `okhttp`, `retrofit`, `room-runtime`, `room-testing` đã có sẵn trong `app/build.gradle.kts`.
- Backend đã có sẵn hạ tầng Spring Security JWT (`SecurityConfig`, `JwtService`, `JwtAuthenticationFilter`) và migration `V1` + `V4` trên `main` (cùng `ContactPolicyService`/`SyncEventWriter` từ công việc Phase 4 trên `origin/phase4_NP`).

### Established Patterns & Constraints Identified
- **Điểm cần mở rộng Schema/Dependency khi lập kế hoạch:**
  - `ConversationMemberEntity` hiện chỉ có `(conversationId, userId, role, lastDeliveredId, lastReadId, left)` — chưa có cột lưu `visibleFromMessageId`, nên áp dụng `D-14` offline sẽ cần nâng `AppDatabase` version kèm `Migration` (ADR-020).
  - `backend/pom.xml` trên `main` hiện chưa có `spring-boot-starter-websocket` — cần bổ sung trong plan Backend Phase 5.
- **Kiến trúc Seam:** Mọi Repository dùng `Interface` + `Fake...` + `Real...` qua `Injection.java`; giữ `FakeRepositoriesTest` hoạt động khi mở rộng `ChatRepository`.
- **Cô lập phiên (Phase 3/4):** Mọi `SyncStateEntity`, `ConversationDao`, `MessageDao`, `WorkManager` job đều phải dọn sạch hoặc dừng an toàn khi đăng xuất/đổi tài khoản (`SessionManager`) để không gửi tin bằng tài khoản khác hoặc lộ cache.

### Integration Points
- `ChatListFragment` là tab gốc của `nav_chat.xml`; sẽ thêm `ChatRoomFragment`, `NewChatFragment`/`GroupCreateFragment` (hoặc tương đương), `GroupInfoFragment` bên trong `nav_chat.xml`.
- Điểm mở chat 1-1 theo `userId` thuộc `nav_chat.xml` là nơi `ContactsListFragment` (Phase 4 UI do Tv2 hoàn thiện) sẽ điều hướng tới sau này qua action trên `nav_graph.xml`.
- Bộ Event Applier của Phase 5 nối `SyncEngine`/`WebSocketManager` với cả `ConversationDao`/`MessageDao`/`SyncStateDao` lẫn `ContactDao`/`UserDao` của Phase 4.

</code_context>

<specifics>
## Specific Ideas

- Thử nghiệm & bảo vệ rõ ràng từng invariant ngay ở tầng Backend và Room thay vì đẩy các ca khó (concurrent `direct_key`, commit `sync_events.id` khác thứ tự, `visible_from_message_id` khi rời→vào lại nhóm, replay `clientId`, 410 cursor expired) về Phase 7.
- Giữ trải nghiệm demo mượt mà và sạch: không hiện nút disabled hoặc nhãn "Chờ Phase 6"; thanh trạng thái WebSocket mất/nối lại hiển thị trực quan dưới Toolbar ở cả danh sách chat và phòng chat; tìm kiếm toàn cục ghi rõ "Tin nhắn trên thiết bị" và cuộn tới đúng tin khớp dù nằm ngoài trang đang tải.

</specifics>

<deferred>
## Deferred Ideas

- Tải lên/tải xuống/mở tệp đa phương tiện (`POST /api/media`, `GET /api/media/{id}`, nén ảnh, ghi âm voice `.m4a`, video ExoPlayer, vị trí bản đồ, `FileProvider`) → thuộc **Phase 6**.
- `NotificationChannel` chi tiết + đồng bộ nền `WorkManager` định kỳ 15 phút (best-effort) → thuộc **Phase 6**.
- Cuộc gọi thoại WebRTC 1-1 (`POST /api/calls/*`, coturn, `call.incoming`/`offer`/`answer`/`ice`/`ended`) → thuộc **Phase 6**.
- Dịch tin nhắn Anh–Việt on-device bằng Google ML Kit (`message_translation`) → thuộc **Phase 6**.
- Gắn nút "Nhắn tin" từ màn hình Danh bạ (`ContactsListFragment`) sau khi Tv2 hoàn tất UI Phase 4 → nghiệm thu tích hợp liên kết Phase 4 ↔ Phase 5 khi Tv2 xong 3 màn con danh bạ.
- Kiểm thử hosted HTTPS/WSS toàn hệ thống, tổng nghiệm thu và đóng gói nộp bài → thuộc **Phase 7**.

</deferred>

---

*Phase: 05-realtime-offline-first-core-messaging*
*Context gathered: 2026-11-19*
