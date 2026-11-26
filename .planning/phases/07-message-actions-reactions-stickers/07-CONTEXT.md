# Phase 7: Message Actions, Reactions & Stickers - Context

**Gathered:** 2026-11-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 7 xây dựng 4 tính năng tương tác tin nhắn nâng cao cho UTEChat trên nền tảng Phase 6 + 2FA (`main` tại `6eb9629`, Flyway `V1`–`V8`, Room `AppDatabase` `version = 3`):
1. **Gỡ tin nhắn trong vòng 24 giờ (Recall for Everyone):** Chỉ người gửi được gỡ tin của chính mình trong 24 giờ (tính trên server, áp dụng cả trong nhóm, chặn khi đã rời nhóm hoặc bị chặn); tin giữ nguyên vị trí với dòng *"Tin nhắn đã được gỡ"*, cập nhật preview, xóa khỏi FTS4/bản dịch và vô hiệu hóa tải lại media.
2. **Xóa tin nhắn ở phía bạn (Delete for Me):** Chỉ ẩn tin trên các thiết bị của tài khoản đã xóa (lưu bảng `message_deletions` có RLS), cho phép cả trong chế độ chỉ đọc/bị chặn, tự lùi preview danh sách chat về tin gần nhất còn lại.
3. **Bày tỏ cảm xúc tin nhắn (Emoji Reactions):** 6 emoji cố định (`❤️`, `😂`, `👍`, `😮`, `😢`, `😡`), mỗi người tối đa 1 cảm xúc/tin (đổi/bỏ linh hoạt), chạm vào cụm cảm xúc xem danh sách người đã thả, đồng bộ realtime giữa các thiết bị.
4. **Kho nhãn dán & Gửi Sticker (Sticker Packs & Offline Queue):** Backend cung cấp 2 bộ nhãn dán mẫu (1 bộ cài sẵn mặc định cho tài khoản mới + 1 bộ trong cửa hàng để bấm Tải/Gỡ), đồng bộ danh sách bộ đã thêm theo tài khoản, lưu ảnh cache xem offline, giữ nguyên hiển thị sticker cũ trong lịch sử sau khi gỡ bộ, hiển thị bong bóng sticker riêng và gửi sticker đã tải qua hàng đợi `SendMessageWorker` khi mất mạng.

</domain>

<decisions>
## Implementation Decisions

### Gỡ tin nhắn 24 giờ & Xóa ở phía bạn
- **D-01:** Gỡ tin nhắn cho mọi người (Recall): **chỉ người gửi** (`sender_id == actor`) mới được gỡ tin nhắn của chính mình (không cho Admin nhóm gỡ tin của người khác). Backend tính hạn **24 giờ** kể từ thời điểm gửi (`created_at`) và kiểm quyền trên server (kể cả trong nhóm). Không áp dụng cho tin `SYSTEM`, `CALL`, hoặc tin đã gỡ. Tin đã gỡ giữ nguyên vị trí trong hội thoại với dòng **“Tin nhắn đã được gỡ”** (`"Message recalled"`).
- **D-02:** Hậu quả đồng bộ khi tin bị gỡ (`MESSAGE_RECALLED`): Backend đặt `recalled_at = now()`, xóa sạch nội dung gốc (`text`, `attachment_id`, `latitude`, `longitude`, `sticker_id`) và **vô hiệu hóa tải lại tệp media** của tin đã gỡ (`GET /api/media/{id}` từ chối tải lại). Khi đồng bộ trên Android, cập nhật preview hội thoại sang *"Tin nhắn đã được gỡ"*, loại nội dung khỏi chỉ mục `message_fts`, và xóa bản dịch cache trong `message_translation`.
- **D-03:** Xóa tin nhắn ở phía bạn (Delete for Me): lưu bảng `public.message_deletions (user_id, message_id, deleted_at)` trên PostgreSQL (bật RLS) và phát sự kiện `MESSAGE_DELETED_FOR_ME` chỉ cho các thiết bị của tài khoản đã xóa. Các truy vấn lịch sử `GET /conversations/{id}/messages` và `GET /media/{id}` loại trừ tin đã xóa phía người gọi; trên Android xóa dòng tương ứng khỏi bảng `message` cục bộ (trigger `message_fts` tự xóa khỏi tìm kiếm).
- **D-04:** Quyền trong chế độ chỉ đọc / bị chặn: khi thành viên đã rời nhóm (`left_at != NULL`) hoặc bị chặn ở chat 1-1, **chỉ cho phép "Xóa ở phía bạn"**; chặn "Gỡ tin 24h" và "Thả cảm xúc" bằng `403 NOT_A_MEMBER` / `403 BLOCKED` (nhất quán chế độ chỉ đọc `D-12` Phase 5).
- **D-05:** Preview Danh sách chat (`ChatListFragment`): khi tin nhắn mới nhất của hội thoại bị gỡ → preview cập nhật thành *"Tin nhắn đã được gỡ"*; khi tin mới nhất bị xóa ở phía bạn → preview tự lùi về tin nhắn gần nhất còn lại chưa xóa (hoặc rỗng nếu không còn tin nào).

### Bày tỏ cảm xúc tin nhắn (Emoji Reactions)
- **D-06:** Dùng đúng **6 emoji cố định** (`❤️`, `😂`, `👍`, `😮`, `😢`, `😡`). Mỗi người có **tối đa 1 cảm xúc trên mỗi tin nhắn** (`PRIMARY KEY (message_id, user_id)` trên `public.message_reactions` có RLS): chọn emoji khác để đổi, chạm lại emoji đang dùng để bỏ. Không cho thả cảm xúc trên tin `SYSTEM` hoặc tin đã gỡ (`recalled_at IS NOT NULL`).
- **D-07:** Hiển thị & đồng bộ cảm xúc: hiển thị cụm biểu cảm kèm số lượng ngay dưới bong bóng tin nhắn; **chạm vào cụm cảm xúc** mở danh sách ai đã thả emoji nào. Đồng bộ realtime giữa các thiết bị qua `sync_events` (`MESSAGE_REACTION_UPDATED`) và lưu cục bộ trong bảng Room `message_reaction`.

### Tải bộ nhãn dán & Gửi Sticker
- **D-08:** Backend cung cấp **2 bộ nhãn dán mẫu** (`sticker_packs` + `stickers`, phục vụ ảnh từ backend) và bảng `user_sticker_packs` (tất cả bật RLS). **Tài khoản mới có sẵn 1 bộ mặc định** để gửi được ngay; **bộ thứ 2 nằm trong Cửa hàng nhãn dán** để người dùng bấm **Tải về** hoặc **Gỡ bộ**, đồng bộ danh sách bộ đã thêm theo tài khoản.
- **D-09:** Cache offline & lịch sử sticker: khi tải bộ nhãn dán, tải ảnh về cache cục bộ của app để xem và chọn offline. **Sticker đã gửi vẫn hiển thị bình thường trong lịch sử trò chuyện sau khi người dùng gỡ bộ nhãn dán đó.**
- **D-10:** Bong bóng sticker & hàng đợi gửi offline: mở rộng `messages.type` thêm `'STICKER'` (kèm `sticker_id`), hiển thị bằng **bong bóng sticker riêng** (không viền bong bóng chữ) và **cho phép gửi sticker đã tải qua hàng đợi `SendMessageWorker` khi mất mạng** (tự gửi bù idempotent với `clientId` khi có mạng lại). — **Reversibility:** costly — mở rộng `SendMessageWorker`, `MessageAdapter` view types và `ChatRepository`.

### Menu nhấn giữ hợp nhất, Hành vi Offline & Hợp đồng/Migration
- **D-11:** Hợp nhất thao tác trên tin nhắn bằng **một Bottom Sheet chung** khi nhấn giữ tin nhắn (thay thế `showTranslationMenu` của Phase 6): dải 6 emoji ở trên cùng, bên dưới hiển thị các nút **Dịch (Anh ↔ Việt)**, **Gỡ tin nhắn**, **Xóa ở phía bạn** tùy theo loại tin nhắn và quyền hợp lệ. Đặt **nút mở khay Sticker ngay cạnh ô nhập tin nhắn** trong `ChatRoomFragment`.
- **D-12:** Hành vi khi mất mạng: **Gỡ tin nhắn, Xóa phía bạn và Đổi/Bỏ cảm xúc cần kết nối mạng để server xác nhận** (không xếp hàng đợi offline); khi mất mạng, báo rõ trạng thái và giữ nguyên tin đang hiển thị. **Dịch tin nhắn tiếp tục hoạt động 100% offline** nếu đã tải gói ngôn ngữ; **Gửi sticker đã tải được xếp hàng đợi offline** theo `D-10`.
- **D-13:** Hợp đồng & Schema Migration: tạo migration PostgreSQL mới **`V9__message_actions_reactions_stickers.sql`** (mở rộng `messages` thêm `recalled_at`, `sticker_id`, `CHECK type` thêm `'STICKER'`; mở rộng `sync_events.event_type` thêm `'MESSAGE_RECALLED'`, `'MESSAGE_DELETED_FOR_ME'`, `'MESSAGE_REACTION_UPDATED'`; tạo 5 bảng mới có RLS: `message_deletions`, `message_reactions`, `sticker_packs`, `stickers`, `user_sticker_packs`), nâng Room `AppDatabase` `v3 → v4` (`MIGRATION_3_4` + `4.json`), và đồng bộ `docs/API_CONTRACT.md`, `docs/FLOWS.md`, `docs/ERD.md` trước khi triển khai. — **Reversibility:** one-way — thay đổi hợp đồng REST/WS, migration PostgreSQL `V9` và migration Room `v3 → v4`.

### the agent's Discretion
- Chi tiết thiết kế trực quan 2 bộ ảnh sticker mẫu (`.webp`/`.png` gọn nhẹ phục vụ từ tài nguyên backend), bố cục chi tiết khay sticker / cửa hàng bộ nhãn dán và kiểu dáng reaction chip + BottomSheet chi tiết người thả.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing:**

### Architecture, Contracts & Security Specs
- `docs/API_CONTRACT.md` — Hợp đồng REST/WebSocket hiện hành (cần bổ sung endpoint recall, delete-for-me, reactions, sticker packs/stickers, `type=STICKER`, và 3 event `MESSAGE_RECALLED`, `MESSAGE_DELETED_FOR_ME`, `MESSAGE_REACTION_UPDATED`).
- `docs/FLOWS.md` — Luồng đồng bộ `/ws` → `/api/sync`, `SendMessageWorker`, tìm kiếm FTS4 và dịch ML Kit.
- `docs/ERD.md` — Sơ đồ bảng PostgreSQL (`messages`, `attachments`, `sync_events`) và Room entities (`message`, `message_fts`, `message_translation`).
- `docs/DEPLOYMENT_SECURITY.md` — Bearer header trên mọi endpoint REST/media/sticker, RLS default-deny trên mọi bảng mới, kiểm quyền server-side.
- `GEMINI.md` & `AGENTS.md` — 100% Java 17 (cấm Kotlin), Views+XML, ViewBinding, MVVM+LiveData, Room, WorkManager, 100% comment Tiếng Việt sư phạm.

### Database & Existing Codebase Assets
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — `CHECK` constraints của `messages.type` (#L135) và `sync_events.event_type` (#L154-L157).
- `backend/src/main/resources/db/migration/V5__sync_and_messaging.sql` & `V6__media_and_calls.sql` — Mẫu migration và trigger `barrier_xid`.
- `backend/src/main/java/com/utechat/backend/service/MessageService.java`, `MediaService.java`, `SyncService.java`, `SyncEventWriter.java`, `ContactPolicyService.java`.
- `backend/src/main/java/com/utechat/backend/websocket/SyncEventRealtimeDispatcher.java`.
- `app/src/main/java/com/utechat/data/local/AppDatabase.java`, `MessageEntity.java`, `MessageDao.java`, `ConversationDao.java`, `TranslationDao.java`.
- `app/src/main/java/com/utechat/data/sync/SyncEventApplier.java`, `SyncEngine.java`, `ChatWebSocketManager.java`.
- `app/src/main/java/com/utechat/data/worker/SendMessageWorker.java`, `RealChatRepository.java`, `FakeChatRepository.java`, `Injection.java`.
- `app/src/main/java/com/utechat/ui/chat/ChatRoomFragment.java`, `ChatRoomViewModel.java`, `MessageAdapter.java`, `ChatListFragment.java`.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MessageEntity.attachmentJson` có thể tái sử dụng để lưu metadata sticker (`stickerId`, `packId`, `imageUrl`, `width`, `height`) mà không làm phình cột trên bảng `message`.
- `SendMessageWorker` (`send-messages:<sessionKey>`) đã hỗ trợ gửi tin `TEXT`/`LOCATION`/media idempotent theo `clientId` — mở rộng sạch cho `type = "STICKER"` offline (`D-10`).
- `Glide` (`UteChatGlideModule` + `ApiClient.getMediaOkHttpClient()`) đã cấu hình sẵn Bearer header an toàn cho tải ảnh từ backend.
- `ChatRoomFragment` hiện đã có hook nhấn giữ `showTranslationMenu(message)` ở dòng 250/764 — điểm thay thế trực tiếp bằng BottomSheet hợp nhất (`D-11`).
- `ConversationDao.observeConversationSummaries()` tính preview tin cuối từ bảng `message` — thuận tiện cho `D-05` khi tin bị gỡ hoặc bị xóa khỏi `message`.

### Established Patterns
- Mọi sự kiện thay đổi dữ liệu bền vững đi qua `sync_events` (`SyncEventWriter` → `/ws` thông báo → `SyncEngine` kéo `/api/sync` single-flight → `SyncEventApplier` ghi Room trong transaction).
- Cấm `ArchTaskExecutor` (`MainThreadExecutor.INSTANCE`), backend test chạy bằng `EmbeddedPostgres` không Docker, 100% Java 17 + comment Tiếng Việt.

### Integration Points
- `V9__message_actions_reactions_stickers.sql` nối tiếp `V8__friend_step.sql`; `AppDatabase` nâng từ `v3` lên `v4` (`MIGRATION_3_4`, xuất `4.json`).
- `ChatRoomFragment` bổ sung nút mở khay Sticker cạnh ô nhập, BottomSheet nhấn giữ hợp nhất và hộp thoại danh sách người thả cảm xúc.

</code_context>

<specifics>
## Specific Ideas

- Chuỗi hiển thị tin bị gỡ tiếng Việt cố định: **“Tin nhắn đã được gỡ”** (`"Message recalled"` ở EN), giữ đúng vị trí dòng thời gian trong hội thoại.
- 6 emoji cố định: `❤️`, `😂`, `👍`, `😮`, `😢`, `😡`; tối đa 1 cảm xúc/người/tin; chạm lại để bỏ, chọn cái khác để đổi; chạm cụm biểu cảm mở danh sách chi tiết ai đã thả.
- 2 bộ nhãn dán mẫu từ backend: tài khoản mới cài sẵn 1 bộ mặc định, bộ thứ 2 nằm ở Cửa hàng để bấm Tải/Gỡ (dễ demo cả 2 luồng); sticker đã gửi trong lịch sử vẫn hiển thị sau khi gỡ bộ.
- Gỡ/xóa/thả cảm xúc yêu cầu có mạng (mất mạng báo rõ trạng thái, giữ nguyên tin); dịch ML Kit vẫn chạy offline nếu đã có model; gửi sticker đã tải xếp hàng đợi offline `SendMessageWorker`.

</specifics>

<deferred>
## Deferred Ideas

- Kiểm thử tổng thể kim tự tháp, tối ưu R8 và đóng gói hồ sơ nộp bài → chuyển sang **Phase 8: Testing, Quality & Release Packaging**.
- Cho phép 1 người thả nhiều emoji khác nhau trên cùng 1 tin nhắn, bộ emoji tự do (emoji picker toàn bảng), hoặc cho người dùng tự upload tạo bộ sticker cá nhân → thuộc **V2**.
- Admin nhóm gỡ tin nhắn của thành viên khác → ngoài phạm vi Phase 7 (chỉ người gửi tự gỡ trong 24h).

</deferred>

---

*Phase: 07-message-actions-reactions-stickers*
*Context gathered: 2026-11-27*
