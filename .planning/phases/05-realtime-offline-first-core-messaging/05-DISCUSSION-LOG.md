# Phase 5: Realtime & Offline-First Core Messaging - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-19
**Phase:** 5-realtime-offline-first-core-messaging
**Areas discussed:** Luồng tạo hội thoại mới & ghép nối Phase 4, Hàng đợi Offline/tin FAILED & đồng bộ, Chat nhóm/tin hệ thống & quản lý thành viên, Tìm kiếm FTS4/cô lập lịch sử & ranh giới Phase 6

---

## Mảng 1: Luồng tạo hội thoại mới & ghép nối với Phase 4

### Câu 1: Từ nút tạo hội thoại mới (+) ở màn Danh sách Tin nhắn (`ChatListFragment`), người dùng bắt đầu cuộc trò chuyện bằng cách nào?

| Option | Description | Selected |
|---|---|---|
| Chọn từ danh bạ bạn bè + ô nhập SĐT nhắn người lạ (kèm lối tắt Tạo nhóm) | Demo được ngay cả chat bạn bè lẫn ca 403 CONTACT_REQUIRED với người lạ mà không chờ SearchUserFragment của Tv2 | ✓ |
| Chỉ chọn từ danh bạ bạn bè để chat 1-1 hoặc tạo nhóm | Nhắn người lạ sẽ đi từ màn Tìm SĐT của Phase 4 sau khi Tv2 làm xong | |
| Để agent quyết định phương án gọn và dễ demo nhất | Agent tự chọn | |

**User's choice:** Chọn phương án 1 + yêu cầu bổ sung.
**Notes:** Dùng lại API lookup và Repository của Phase 4, không viết lại logic tìm SĐT. Nếu người nhận tắt nhận tin người lạ, hiển thị “Người này chỉ nhận tin nhắn từ bạn bè” và cho phép gửi lời mời kết bạn. Nếu bị chặn, hiển thị thông báo phù hợp. Server kiểm quyền khi tạo hội thoại và mỗi lần gửi tin. Chọn lại cùng người phải mở hội thoại 1-1 hiện có. Lối tắt “Tạo nhóm” chỉ chọn thành viên từ danh sách bạn bè.

### Câu 2: Xử lý nút 'Nhắn tin' từ Danh bạ (đã tạm ẩn ở Phase 4 D-02) thế nào để không xung đột với Tv2?

| Option | Description | Selected |
|---|---|---|
| Giữ tách biệt tuyệt đối khi làm song song | Phase 5 cung cấp sẵn route/hàm mở chat 1-1 trong nav_chat, chỉ gắn nút 'Nhắn tin' vào ContactsListFragment sau khi Tv2 xong UI Phase 4 | ✓ |
| Gắn luôn nút 'Nhắn tin' vào ContactsListFragment ngay trong Phase 5 | Dùng được ngay nhưng sẽ đụng vào code ui/contacts của Tv3/Tv2 | |

**User's choice:** Bổ sung rõ hợp đồng tích hợp từ phương án 1.
**Notes:** Phase 5 cung cấp một điểm mở chat 1-1 nhận `userId`, tái sử dụng hội thoại hiện có hoặc tạo mới qua server. Kiểm chứng ngay bằng luồng tạo chat từ ChatList. Thành viên làm UI Phase 4 sẽ nối nút “Nhắn tin” vào điểm này sau khi hoàn tất giao diện. Chốt trước người phụ trách các file navigation/DI dùng chung; chỉ xem tích hợp hoàn tất khi bấm từ Danh bạ mở đúng phòng chat.

### Câu 3: Luồng WebSocket `/ws` và `GET /api/sync` của Phase 5 có cập nhật realtime luôn các sự kiện danh bạ (`CONTACT_REQUEST_*`, `CONTACT_UPDATED`) của Phase 4 không?

| Option | Description | Selected |
|---|---|---|
| Xử lý trọn gói cả 2 đầu (Backend + Android) | WebSocket /ws và GET /api/sync cập nhật đồng bộ cả sự kiện Chat lẫn 3 sự kiện Contact của Phase 4 vào Room | ✓ |
| Backend đẩy đủ mọi sync_events, Android Phase 5 chỉ áp dụng sự kiện Chat/Device | Bỏ qua an toàn sự kiện Contact và ghép sau | |

**User's choice:** Chọn phương án 1 + yêu cầu bổ sung.
**Notes:** Dùng chung bộ xử lý sự kiện cho WebSocket và `/api/sync`, tái sử dụng DAO/Repository Phase 4. Áp dụng đầy đủ `CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED`, kể cả hủy kết bạn, bỏ chặn và xóa dòng cache. Xử lý lặp không tạo dữ liệu trùng; chỉ nâng cursor sau khi thay đổi Room được lưu thành công trong cùng transaction. Không bỏ qua event Contact rồi vẫn nâng cursor, vì có thể mất cập nhật vĩnh viễn.

### Câu 4: Trong phòng chat 1-1, khi bị chặn (`403 BLOCKED`) hoặc đối phương tắt nhận tin người lạ (`403 CONTACT_REQUIRED`), giao diện xử lý thế nào?

| Option | Description | Selected |
|---|---|---|
| Giữ lịch sử cũ, đánh dấu tin vừa gửi FAILED kèm lý do rõ và hiện banner cảnh báo trên khung nhập | Bám sát FLOWS.md F-10 và dễ demo lỗi 403 | ✓ |
| Khóa luôn khung nhập tin nhắn ngay khi mở phòng chat | Chặn ngay khi phát hiện bị chặn hoặc tắt nhận tin người lạ | |

**User's choice:** Giữ lịch sử cũ, đánh dấu tin vừa gửi FAILED kèm lý do rõ và hiện banner cảnh báo trên khung nhập.
**Notes:** Áp dụng đúng theo FLOWS.md F-10.

---

## Mảng 2: Hàng đợi Offline, tin `FAILED` & đồng bộ khi mở app

### Câu 1: Phân loại lỗi trong `SendMessageWorker` và thao tác trên tin nhắn `FAILED` ở UI?

| Option | Description | Selected |
|---|---|---|
| Mạng/5xx/429 retry tối đa 3 lần rồi FAILED; 4xx nghiệp vụ FAILED ngay; 401 dừng phiên; UI hỗ trợ Gửi lại hoặc Xóa tin lỗi | Phân loại lỗi đầy đủ và thao tác trên tin lỗi | ✓ |
| Giữ phân loại lỗi ở worker, UI chỉ cho nút Gửi lại | Không cho xóa tin lỗi khỏi Room | |
| Để agent quyết định | Theo chuẩn offline-first an toàn nhất | |

**User's choice:** Chọn Phương án 1 + bổ sung quy tắc chi tiết.
**Notes:** Mất mạng giữ tin chờ gửi, WorkManager đợi có mạng, không tính thời gian offline vào số lần thử. Lỗi mạng khi đang gửi / `5xx` / `408` / `429` (tôn trọng `Retry-After`) retry có backoff tối đa 3 lượt gửi tự động. Lỗi nghiệp vụ `400`/`403`/`404` chuyển `FAILED` ngay kèm lý do cụ thể (bị chặn, cần kết bạn, không còn trong nhóm). `401` dừng công việc của phiên hết hiệu lực và yêu cầu đăng nhập lại, không gửi tiếp bằng tài khoản khác. “Gửi lại” giữ nguyên `clientId`; “Xóa tin lỗi” chỉ xóa bản cục bộ và hủy job tương ứng, không thu hồi trên server.

### Câu 2: Khi đăng nhập mới trên thiết bị trống (`lastSyncEventId = 0`) hoặc offline quá 30 ngày (`sync_events` cũ đã bị dọn), app khởi tạo đồng bộ thế nào?

| Option | Description | Selected |
|---|---|---|
| Snapshot + Cursor nhất quán từ server | Tải snapshot danh sách hiện tại + mốc cursor nhất quán, lưu sync_state rồi chạy incremental /sync và /ws | ✓ |
| Vòng lặp GET /sync?after=0 rồi bổ sung REST | Lấy từ đầu rồi gọi GET /conversations | |

**User's choice:** Chọn phương án 1 + bổ sung chi tiết nhất quán.
**Notes:** Server cung cấp snapshot và cursor nhất quán (không tải snapshot xong rồi mới lấy cursor mới nhất). Snapshot gồm hội thoại, thành viên cần thiết, bạn bè, danh sách chặn, lời mời `PENDING` (lịch sử tin tải phân trang khi mở phòng). Lưu snapshot + cursor trong cùng transaction Room, giữ tin `SENDING`/`FAILED`. Sau bootstrap chạy `/sync` phân trang phối hợp `/ws`. Server báo `410 SYNC_CURSOR_EXPIRED` khi cursor không còn dùng được để client bootstrap lại.

### Câu 3: Phối hợp con trỏ `lastSyncEventId` giữa WebSocket `/ws` và `GET /api/sync` khi vừa kết nối lại để không mất sự kiện?

| Option | Description | Selected |
|---|---|---|
| Đồng bộ chống nhảy cóc / single-flight với `/sync` là nguồn cập nhật cursor duy nhất | WS đánh thức `/sync`; chỉ `/sync` được nâng cursor bền vững | ✓ |
| Chạy xong /sync rồi mới mở /ws | Tuần tự từng bước | |

**User's choice:** Quy định rõ cơ chế phối hợp WS và `/api/sync`.
**Notes:** WebSocket báo có thay đổi và kích hoạt `/api/sync`; chỉ kết quả `/sync` được dùng để cập nhật cursor bền vững (không nâng cursor theo ID lớn nhất nhận qua WebSocket). Khi kết nối lại: mở WS sẵn sàng nhận sự kiện, rồi chạy `/sync` phân trang đến hết. Chỉ 1 lượt sync chạy tại một thời điểm; nếu WS báo thay đổi trong lúc sync thì đánh dấu chạy tiếp trước khi kết thúc. Áp dụng sự kiện idempotent; lưu dữ liệu + `nextCursor` trong cùng transaction Room. Không dùng `eventId == lastSyncEventId + 1` để phát hiện thiếu sự kiện.

### Câu 4: Khi WebSocket mất kết nối (`Disconnected` / `Reconnecting`), hiển thị trạng thái kết nối ở đâu trên UI?

| Option | Description | Selected |
|---|---|---|
| Thanh trạng thái nhỏ dưới Toolbar trên cả ChatListFragment và ChatRoomFragment | Tự ẩn ngay khi nối lại & đồng bộ xong | ✓ |
| Chỉ hiện ở ChatListFragment | Phòng chat chỉ hiện trạng thái SENDING trên bong bóng | |

**User's choice:** Hiện thanh trạng thái nhỏ dưới Toolbar trên cả `ChatListFragment` và `ChatRoomFragment`, tự ẩn khi nối lại & đồng bộ xong.

---

## Mảng 3: Chat nhóm, tin hệ thống & quản lý thành viên

### Câu 1: Khi có thay đổi trong nhóm (tạo nhóm, đổi tên, thêm/xóa/rời nhóm, đổi quyền ADMIN), server có tự sinh tin nhắn loại `SYSTEM` vào luồng chat không?

| Option | Description | Selected |
|---|---|---|
| Server tự sinh tin SYSTEM (systemEvent + systemParams) trong cùng transaction với thay đổi nhóm và sync_events | Android tự dịch đa ngôn ngữ EN/VI hiển thị giữa luồng chat | ✓ |
| Chỉ sinh tin SYSTEM cho tạo nhóm và thêm/xóa/rời thành viên | Đổi tên và đổi quyền chỉ đẩy CONVERSATION_UPDATED | |
| Không sinh tin SYSTEM trong Phase 5 | Chỉ cập nhật metadata nhóm | |

**User's choice:** Chọn phương án 1 + điều kiện chống lặp.
**Notes:** Server sinh tin `SYSTEM` cùng transaction với thay đổi nhóm và `sync_events` (`systemEvent` + `systemParams`, Android dịch EN/VI). Thao tác thất bại, không thay đổi dữ liệu hoặc request lặp không được tạo thêm tin `SYSTEM`.

### Câu 2: Trải nghiệm màn hình quản lý nhóm và luồng bàn giao khi `ADMIN` duy nhất rời nhóm?

| Option | Description | Selected |
|---|---|---|
| Màn GroupInfoFragment riêng + dialog chọn người kế nhiệm khi ADMIN duy nhất rời nhóm | Đầy đủ thao tác sửa tên, muted, thành viên, cấp ADMIN, rời nhóm | ✓ |
| BottomSheet/Dialog gọn trong ChatRoomFragment | ADMIN duy nhất tự cấp quyền trước rồi mới bấm rời lại | |

**User's choice:** Chọn phương án 1 + chốt đủ 3 nhánh rời nhóm & `muted`.
**Notes:** Dùng `GroupInfoFragment` riêng, server kiểm quyền mọi thao tác quản trị. Khi rời nhóm: `ADMIN` duy nhất còn thành viên khác → chọn người kế nhiệm, bàn giao và rời nhóm trong cùng transaction; đã có `ADMIN` khác → cho rời trực tiếp; là thành viên cuối cùng → cho rời trực tiếp không bắt chọn kế nhiệm. `muted` là thiết lập cá nhân, không sinh tin `SYSTEM`.

### Câu 3: Hiển thị biên nhận `DELIVERED` / `READ` trên bong bóng tin của mình (1-1 vs Nhóm) và thời điểm gọi `POST /read`?

| Option | Description | Selected |
|---|---|---|
| Nhất quán SENDING / SENT / DELIVERED / READ (hoặc FAILED) cho cả 1-1 và nhóm | Nhóm lên DELIVERED/READ khi ít nhất 1 thành viên khác đạt mốc (ADR-010); gọi POST /read khi phòng chat ở foreground RESUMED | ✓ |
| Nhóm chỉ hiện SENDING / SENT / FAILED | Tối giản giao diện bong bóng nhóm | |

**User's choice:** Hiển thị nhất quán cả 1-1 và nhóm; gọi `POST /read` khi phòng chat ở foreground (`RESUMED`) và có tin mới hơn `lastReadId`.

### Câu 4: Khi thành viên tự rời nhóm hoặc bị `ADMIN` xóa khỏi nhóm (`left_at != NULL`), hiển thị hội thoại đó trên app thế nào?

| Option | Description | Selected |
|---|---|---|
| Chuyển hội thoại sang chế độ chỉ đọc (ẩn khung nhập, giữ lịch sử trước khi rời) | Server chặn 403 NOT_A_MEMBER và ngừng đẩy sync_events mới của nhóm đó | ✓ |
| Xóa hẳn cuộc hội thoại khỏi danh sách chat | Xóa ngay khi nhận sự kiện rời nhóm | |

**User's choice:** Chọn phương án 1 + chốt sự kiện cuối và dừng retry.
**Notes:** Người bị xóa vẫn phải nhận được sự kiện cuối thông báo mất tư cách thành viên để app chuyển sang chỉ đọc; sau đó ngừng cấp sự kiện mới của nhóm. Các tin đang chờ gửi không được retry vô hạn khi gặp `NOT_A_MEMBER`.

---

## Mảng 4: Tìm kiếm FTS4 tiếng Việt & ranh giới với Phase 6

### Câu 1: Phạm vi tìm kiếm tin nhắn offline tiếng Việt không dấu (Room FTS4 `message_fts`)?

| Option | Description | Selected |
|---|---|---|
| Hỗ trợ cả trong ChatRoomFragment và toàn cục trên ChatListFragment | Dùng chung message_fts cho cả 2 phạm vi | ✓ |
| Chỉ tìm trong từng phòng chat (ChatRoomFragment) | ChatListFragment chỉ lọc theo tên hội thoại | |

**User's choice:** Hỗ trợ cả hai phạm vi qua `message_fts`.
**Notes:** Trong `ChatRoomFragment`: đếm kết quả, nhảy trước/sau. Trong `ChatListFragment`: tách rõ 2 nhóm kết quả (hội thoại vs tin nhắn), bấm mở đúng phòng và đưa tin khớp vào vùng hiển thị kể cả khi ngoài trang đang tải. Chuẩn hóa bỏ dấu + `đ/Đ -> d` + thường, an toàn ký tự `MATCH`, đầu vào rỗng không lỗi, chỉ tìm dữ liệu cache trong phạm vi lịch sử hợp lệ, ghi rõ “Tin nhắn trên thiết bị”, phân trang/giới hạn kết quả, cập nhật FTS đúng khi thêm/sửa/xóa cache.

### Câu 2: Cách xử lý giao diện cho các nút và loại tin thuộc Phase 6 (Media, Vị trí, Cuộc gọi) trong Phase 5?

| Option | Description | Selected |
|---|---|---|
| Ẩn sạch nút Media/Vị trí/Gọi thoại; Adapter render thẻ tóm tắt tối giản & giữ metadata | Không nút disabled, không câu "Chờ Phase 6" | ✓ |
| Hiện sẵn nút ở trạng thái disabled | Giữ khung bố cục cho Phase 6 | |

**User's choice:** Chọn ẩn sạch nút Phase 6 + bảo toàn metadata đầy đủ.
**Notes:** Chỉ gửi `TEXT` và nhận `SYSTEM` trong Phase 5. Adapter vẫn render an toàn `IMAGE/AUDIO/VIDEO/FILE/LOCATION/CALL` (icon + nhãn EN/VI, không gắn thao tác phát/mở/gọi) + fallback cho loại chưa biết; DTO/Repository/Room bảo toàn mọi metadata theo hợp đồng API; tách view type rõ để Phase 6 mở rộng mà không tạo framework thừa.

### Câu 3: Khi thành viên từng rời nhóm được thêm lại vào nhóm (có `visible_from_message_id` mới), xử lý cache cũ và kết quả FTS4 trên thiết bị thế nào?

| Option | Description | Selected |
|---|---|---|
| Siết chặt theo visible_from_message_id mới (> mốc mới) | Dọn tin cache & FTS ngoài phạm vi trong cùng transaction khi được thêm lại | ✓ |
| Giữ lại cache tin trước khi rời nhóm trên thiết bị cũ | Chỉ chặn tin trong khoảng thời gian không ở nhóm | |

**User's choice:** Chọn cô lập lịch sử theo lần tham gia hiện tại bằng `serverId > visible_from_message_id`.
**Notes:** Rời giữ lịch sử chỉ đọc; khi thêm lại chỉ hiển thị/tìm/tải tin có `serverId > visible_from_message_id` (không dùng `>=`). Truyền/lưu mốc trên Android (kèm Room version/migration phù hợp nếu đổi schema), dọn cache & chỉ mục FTS ngoài phạm vi trong cùng transaction khi nhận mốc mới, mọi đường nạp dữ liệu đều tuân thủ, phản hồi/sự kiện cũ không hạ mốc hoặc chèn lại tin cũ. Xử lý riêng tin `SENDING`/`FAILED` (`serverId = NULL`) không bị xóa nhầm và không tự gửi lại tin của lần tham gia cũ.

### Câu 4: Chuẩn kiểm thử tự động & điều kiện nghiệm thu bắt buộc cho Phase 5?

| Option | Description | Selected |
|---|---|---|
| Kiểm thử toàn diện bất biến dữ liệu, race condition & nghiệm thu 2 tài khoản thật ngay trong Phase 5 | EmbeddedPostgres (không Docker) + Android JVM/Room FTS + Lint + 2 tài khoản backend thật | ✓ |
| Chỉ viết happy-path cơ bản ở Phase 5 | Dồn race/edge-case và Lint sang Phase 7 | |

**User's choice:** Bắt buộc kiểm thử toàn diện ngay trong Phase 5.
**Notes:** Backend `mvn test` dùng JUnit/MockMvc + `EmbeddedPostgres` thật (không Docker) phủ đủ mọi bất biến & race condition (kể cả transaction commit khác thứ tự `sync_events.id`, `410 SYNC_CURSOR_EXPIRED`, bàn giao `ADMIN` + rời nhóm, WS Bearer handshake). Android có JVM test + instrumentation test Room/FTS thật + nghiệm thu 2 tài khoản trên backend thật; trước khi báo hoàn tất phải pass `mvn test`, Android unit test, `lintDebug` và instrumentation test liên quan.

---

## the agent's Discretion

- Bố cục chi tiết bong bóng tin nhắn, phân nhóm theo ngày, hiển thị chỉ báo đang nhập (`typing`) và chi tiết view types của `MessageAdapter` theo chuẩn Material 3.
- Cơ chế nội bộ trên server xử lý trường hợp transaction commit khác thứ tự `sync_events.id` trong `/api/sync`.

## Deferred Ideas

- Media upload/viewer (`POST/GET /api/media`, `FileProvider`), gửi vị trí, `NotificationChannel` đầy đủ + WorkManager periodic sync 15 phút, cuộc gọi thoại WebRTC và dịch ML Kit Translation → **Phase 6**.
- Gắn nút "Nhắn tin" vào `ContactsListFragment` khi Tv2 hoàn tất UI Phase 4.
- Kiểm thử hosted HTTPS/WSS, E2E toàn hệ thống và đóng gói hồ sơ nộp bài → **Phase 7**.
