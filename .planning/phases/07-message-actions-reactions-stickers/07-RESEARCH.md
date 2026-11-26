# Phase 7: Message Actions, Reactions & Stickers - Research

**Date:** 2026-11-27
**Author:** GSD Phase Researcher
**Phase:** 07 — Message Actions, Reactions & Stickers
**Requirement IDs:** `REQ-message-actions-recall-delete`, `REQ-message-reactions`, `REQ-message-stickers`
**Status:** COMPLETE

---

## User Constraints & Phase Boundary (from `07-CONTEXT.md`)

Phase 7 builds on the completed Phase 6 + 2FA + Friend-step codebase (`main` at `6eb9629`, Flyway `V1`–`V8`, Room `AppDatabase` `version = 3`) and adds four tightly scoped messaging interaction features:

1. **24-Hour Recall for Everyone (`D-01`, `D-02`, `D-04`, `D-05`):**
   - Only the original sender (`sender_id == actor`) can recall their own message (group admins cannot recall other members' messages).
   - Server enforces the **24-hour window** from `messages.created_at` (including in groups). Rejects `SYSTEM`, `CALL`, and already-recalled messages. Blocked when `left_at IS NOT NULL` (`403 NOT_A_MEMBER`) or blocked in 1-1 (`403 BLOCKED`).
   - Recalled messages stay in chronological position showing **“Tin nhắn đã được gỡ”** (`"Message recalled"` in EN).
   - Server sets `recalled_at = now()`, clears `text`, `attachment_id`, `latitude`, `longitude`, `sticker_id`, **revokes media re-download (`GET /api/media/{id}` rejects)**, clears reactions on that message, and emits `MESSAGE_RECALLED` to active members.
   - Android updates chat list preview to *"Tin nhắn đã được gỡ"*, removes original content from `message_fts`, and deletes cached translations in `message_translation`.
2. **Delete for Me (`D-03`, `D-04`, `D-05`):**
   - Stored in `public.message_deletions (user_id, message_id, deleted_at)` with RLS enabled; emits `MESSAGE_DELETED_FOR_ME` **only** to the acting user's devices.
   - Allowed even in read-only (left group) or blocked 1-1 conversations (`D-04`).
   - Excluded from `GET /conversations/{id}/messages`, `GET /media/{id}`, and `ConversationService` `lastMessage`/`unreadCount` for that user.
   - Android deletes the row from Room `message` (Room FTS4 trigger automatically removes it from search) and chat list preview automatically falls back to the latest remaining message (or empty if none left).
3. **6-Emoji Reactions (`D-06`, `D-07`, `D-04`):**
   - Exactly **6 fixed emojis**: `❤️`, `😂`, `👍`, `😮`, `😢`, `😡`.
   - At most **1 reaction per user per message** (`PRIMARY KEY (message_id, user_id)` in `public.message_reactions` with RLS): selecting a different emoji switches it; tapping the active emoji removes it. Forbidden on `SYSTEM` or recalled messages, and blocked in read-only/blocked conversations (`403 NOT_A_MEMBER` / `403 BLOCKED`).
   - Rendered as a chip cluster with counts directly below the bubble; tapping the cluster opens `ReactionDetailsBottomSheet` showing who reacted with which emoji. Synced realtime via `MESSAGE_REACTION_UPDATED` and stored locally in Room `message_reaction`.
4. **Sticker Packs & Offline Queue (`D-08`, `D-09`, `D-10`, `D-11`, `D-12`):**
   - Backend provides **2 sample sticker packs** (`sticker_packs`, `stickers`, `user_sticker_packs`, all with RLS) backed by static PNG/WebP assets packaged in backend resources (zero external URLs, 100% LAN/offline friendly): **1 default pack pre-installed for new accounts** and **1 store pack** users can Install (`Tải về`) / Uninstall (`Gỡ bộ`), synced per account.
   - Installed pack images are cached locally (`filesDir/stickers/<packCode>/`) for offline browsing and selection. **Previously sent stickers remain visible in chat history after uninstalling the pack** (`D-09`).
   - `messages.type` expanded with `'STICKER'` + `sticker_id`; rendered as a **borderless sticker bubble** (`TYPE_STICKER_OUT` / `TYPE_STICKER_IN`); **installed stickers can be queued offline via `SendMessageWorker`** (`clientId` idempotent replay, `D-10`).
5. **Unified Long-Press Menu, Offline Behavior & One-Way Contracts (`D-11`, `D-12`, `D-13`):**
   - Single unified BottomSheet (`MessageActionBottomSheet`) replaces Phase 6 `showTranslationMenu` in `ChatRoomFragment`: 6-emoji bar on top + conditional actions (Translate EN/VI, Recall, Delete for Me) below. Sticker button sits next to the text input in `ChatRoomFragment`.
   - **Offline (`D-12`):** Recall, Delete for Me, and Reactions require network confirmation (not queued offline); when disconnected, show a clear offline notice and keep the message unchanged. ML Kit translation remains 100% offline once downloaded; installed stickers enqueue in `SendMessageWorker`.
   - **One-way checkpoints (`D-13`):** Flyway `V9__message_actions_reactions_stickers.sql`, Room `AppDatabase` `v3 -> v4` (`MIGRATION_3_4` + `4.json`), and updating `docs/API_CONTRACT.md`, `docs/FLOWS.md`, `docs/ERD.md` before implementation.

---

## Project Constraints (from `GEMINI.md`, `AGENTS.md`, `DEPLOYMENT_SECURITY.md`)

1. **100% Java 17 & Android Views Stack:**
   - Android (`com.utechat`): Java 17, Views + XML, ViewBinding, MVVM + LiveData, Navigation Component, Room SQLite (`annotationProcessor`), WorkManager, Retrofit + OkHttp, Glide (`UteChatGlideModule`) [VERIFIED: `GEMINI.md#L17`, `UteChatGlideModule.java#L19-L31`].
   - **Forbidden:** Kotlin, Coroutines, Flow, Jetpack Compose, Hilt/Dagger, `ArchTaskExecutor.getInstance()` (always use `MainThreadExecutor.INSTANCE` or injected `Executor`) [VERIFIED: `GEMINI.md#L17`, `RealChatRepository.java#L48,L78`].
   - Backend (`com.utechat.backend`): Spring Boot 3, Java 17, Maven (`./mvnw.cmd`), `JdbcTemplate`, Flyway, PostgreSQL (`EmbeddedPostgres` for tests, zero Docker) [VERIFIED: `GEMINI.md#L18`].
2. **100% Educational Vietnamese In-Code Comments:** Every new or edited Java class/method/field, XML layout/strings, and SQL migration MUST have concise Vietnamese comments explaining what each block does and why [VERIFIED: `GEMINI.md#L3-L15`].
3. **Security Rules (`docs/DEPLOYMENT_SECURITY.md`):**
   - All new REST endpoints (`/recall`, `/reaction`, `/stickers/**`) require `Authorization: Bearer <token>` (`SecurityConfig.java#L31` already enforces `.anyRequest().authenticated()` for everything outside `/api/health/**` and `/api/auth/**`) [VERIFIED: `SecurityConfig.java#L28-L31`].
   - Sticker image URLs stay relative (`/api/stickers/assets/{packCode}/{fileName}`) resolved strictly against `BuildConfig.BACKEND_BASE_URL` via a dedicated regex guard (`ApiClient.resolveBackendStickerUrl`) matching `ApiClient.resolveBackendMediaUrl` [VERIFIED: `ApiClient.java#L16-L17,L76-L96`]. Never attach Bearer token to arbitrary external URLs.
   - All 5 new PostgreSQL tables MUST have `ENABLE ROW LEVEL SECURITY` with zero public policies (ADR-007) [VERIFIED: `V1__initial_schema.sql#L188-L200`, `V7__two_factor.sql#L16-L17`].
   - Zero new Gradle dependencies are needed for Phase 7! Glide (`5.0.10`), Material 3 (`BottomSheetDialog`), Room, WorkManager, Gson, and OkHttp are already wired.

---

## 1. PostgreSQL Schema & `V9__message_actions_reactions_stickers.sql` (`D-01`..`D-13`, One-Way)

### Inspection of Existing Schema (`V1`–`V8`)

- **Inline `CHECK` constraint names in `V1__initial_schema.sql`:**
  - `messages.type` (`V1__initial_schema.sql#L135`): inline `CHECK (type IN ('TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'FILE', 'LOCATION', 'SYSTEM', 'CALL'))` -> PostgreSQL default constraint name is **`messages_type_check`** [VERIFIED: `V1__initial_schema.sql#L135`, confirmed by identical pattern `auth_throttles_purpose_check` in `V7__two_factor.sql#L20-L22`].
  - `sync_events.event_type` (`V1__initial_schema.sql#L154-L157`): inline `CHECK` -> PostgreSQL default constraint name is **`sync_events_event_type_check`** [VERIFIED: `V1__initial_schema.sql#L154-L157`].
  - `sync_events.entity_type` (`V1__initial_schema.sql#L158-L160`): already contains `'MESSAGE'`, which covers `MESSAGE_RECALLED`, `MESSAGE_DELETED_FOR_ME`, and `MESSAGE_REACTION_UPDATED` (`entity_id = String.valueOf(messageId)`). No alteration needed on `entity_type`.
- **Critical Media Revocation Subtlety (`D-02` + `MediaService.java#L294-L319`):**
  - Currently, `MediaService.prepareDownload()` queries `SELECT m.conversation_id, m.id FROM public.messages m WHERE m.attachment_id = ?`. If `linkedMsgs` is empty, it falls through to the "unattached draft" branch and **allows `att.uploaderId().equals(actorId)` to download it** [VERIFIED: `MediaService.java#L314-L319`].
  - Therefore, simply setting `messages.attachment_id = NULL` on recall is **NOT sufficient** — the sender could still re-download their recalled attachment!
  - **Clean, leak-proof solution:** Add `revoked_at TIMESTAMPTZ` to `public.attachments`. During `recallMessage`:
    1. Read `oldAttachmentId` and `storage_path` before clearing `messages.attachment_id = NULL`.
    2. If `oldAttachmentId != null`: execute `UPDATE public.attachments SET revoked_at = now() WHERE id = ?` inside the same transaction AND register a `TransactionSynchronization.afterCommit()` callback to delete the physical file quietly from `MEDIA_STORAGE_DIR`.
    3. In `MediaService.prepareDownload()`: select `revoked_at` from `public.attachments`; if `revoked_at != null`, immediately throw `404 NOT_FOUND` (`"Attachment is no longer available"`).
    4. Also in `MediaService.prepareDownload()`, when `linkedMsgs` is non-empty, check both `m.recalled_at IS NULL` (defense-in-depth -> `404 NOT_FOUND`) AND `NOT EXISTS (SELECT 1 FROM public.message_deletions md WHERE md.user_id = ? AND md.message_id = ?)` (`D-03` -> `403 FORBIDDEN`).
- **Default Sticker Pack Semantics (`D-08`):**
  - Adding `installed BOOLEAN NOT NULL DEFAULT TRUE` on `public.user_sticker_packs` solves both requirements cleanly without coupling `AuthService` registration or breaking tests that seed users via SQL:
    - If no row exists in `user_sticker_packs` for `(user_id, pack_id)`, effective state is `COALESCE(usp.installed, sp.is_default)` -> **every new account automatically has the default pack installed**.
    - Installing upserts `installed = TRUE, installed_at = now()`.
    - Uninstalling upserts `installed = FALSE` -> even if the user uninstalls the default pack, it stays uninstalled across refreshes and devices!
- **Clock Injection for 24h Recall Boundary Testing:**
  - A Spring `Clock` bean is already registered and injected into `MediaService` [VERIFIED: `MediaService.java#L67,L78`]. Inject `Clock` into `MessageService` as well so unit/integration tests can test exact `23h59m59s` vs `24h00m01s` recall boundaries deterministically without `Thread.sleep`.

### Exact `V9__message_actions_reactions_stickers.sql` Migration

```sql
-- Phase 7: Gỡ tin nhắn 24 giờ, Xóa tin ở phía bạn, Cảm xúc 6 emoji và Kho nhãn dán (D-01..D-13).

-- 1) Đánh dấu thu hồi vĩnh viễn tệp media khi tin nhắn gốc bị gỡ (chặn tải lại kể cả người gửi - D-02).
ALTER TABLE public.attachments
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;

-- 2) Bảng kho bộ nhãn dán hệ thống (bật RLS, không tạo policy công khai theo ADR-007).
CREATE TABLE public.sticker_packs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    thumbnail_url VARCHAR(500) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3) Bảng danh sách từng nhãn dán thuộc bộ nhãn dán.
CREATE TABLE public.stickers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pack_id UUID NOT NULL REFERENCES public.sticker_packs(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    label VARCHAR(100) NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    mime VARCHAR(50) NOT NULL DEFAULT 'image/png',
    width INTEGER NOT NULL DEFAULT 256 CHECK (width > 0),
    height INTEGER NOT NULL DEFAULT 256 CHECK (height > 0),
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_stickers_pack_code UNIQUE (pack_id, code)
);
CREATE INDEX idx_stickers_pack_sort ON public.stickers(pack_id, sort_order ASC);

-- 4) Bảng đồng bộ trạng thái cài/gỡ bộ nhãn dán theo từng tài khoản (COALESCE(installed, is_default)).
CREATE TABLE public.user_sticker_packs (
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    pack_id UUID NOT NULL REFERENCES public.sticker_packs(id) ON DELETE CASCADE,
    installed BOOLEAN NOT NULL DEFAULT TRUE,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_sticker_packs PRIMARY KEY (user_id, pack_id)
);
CREATE INDEX idx_user_sticker_packs_user ON public.user_sticker_packs(user_id);

-- 5) Mở rộng bảng messages: thêm recalled_at, sticker_id và cho phép type = 'STICKER'.
ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS recalled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sticker_id UUID REFERENCES public.stickers(id) ON DELETE SET NULL;

ALTER TABLE public.messages DROP CONSTRAINT IF EXISTS messages_type_check;
ALTER TABLE public.messages ADD CONSTRAINT messages_type_check
    CHECK (type IN ('TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'FILE', 'LOCATION', 'SYSTEM', 'CALL', 'STICKER'));

-- 6) Bảng đánh dấu tin nhắn bị xóa ở phía người dùng (Delete for Me - D-03).
CREATE TABLE public.message_deletions (
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_deletions PRIMARY KEY (user_id, message_id)
);
CREATE INDEX idx_message_deletions_message ON public.message_deletions(message_id);

-- 7) Bảng cảm xúc tin nhắn: đúng 6 emoji cố định, tối đa 1 cảm xúc/người/tin (D-06).
CREATE TABLE public.message_reactions (
    message_id BIGINT NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    emoji VARCHAR(16) NOT NULL CHECK (emoji IN ('❤️', '😂', '👍', '😮', '😢', '😡')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_reactions PRIMARY KEY (message_id, user_id)
);
CREATE INDEX idx_message_reactions_msg_created ON public.message_reactions(message_id, created_at ASC);

-- 8) Mở rộng sync_events.event_type thêm 3 sự kiện đồng bộ Phase 7 (D-13).
ALTER TABLE public.sync_events DROP CONSTRAINT IF EXISTS sync_events_event_type_check;
ALTER TABLE public.sync_events ADD CONSTRAINT sync_events_event_type_check
    CHECK (event_type IN (
        'MESSAGE_CREATED', 'CONVERSATION_UPDATED', 'CONTACT_REQUEST_CREATED',
        'CONTACT_REQUEST_UPDATED', 'CONTACT_UPDATED', 'RECEIPT_UPDATED',
        'CALL_UPDATED', 'DEVICE_REVOKED',
        'MESSAGE_RECALLED', 'MESSAGE_DELETED_FOR_ME', 'MESSAGE_REACTION_UPDATED'
    ));

-- 9) Bật RLS mặc định từ chối mọi truy cập công khai cho toàn bộ 5 bảng mới (ADR-007).
ALTER TABLE public.sticker_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.stickers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_sticker_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_deletions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_reactions ENABLE ROW LEVEL SECURITY;

-- 10) Nạp sẵn 2 bộ nhãn dán mẫu (1 bộ mặc định ute_daily + 1 bộ cửa hàng ute_campus, mỗi bộ 6 nhãn dán - D-08).
INSERT INTO public.sticker_packs (id, code, name, description, thumbnail_url, is_default, sort_order)
VALUES
    ('11111111-1111-4111-8111-111111111101', 'ute_daily', 'UTE Thường Ngày', 'Nhãn dán giao tiếp hằng ngày cài sẵn cho tài khoản mới', '/api/stickers/assets/ute_daily/hi.png', TRUE, 1),
    ('11111111-1111-4111-8111-111111111102', 'ute_campus', 'UTE Học Đường', 'Bộ nhãn dán sinh viên UTE tại cửa hàng nhãn dán', '/api/stickers/assets/ute_campus/deadline.png', FALSE, 2)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.stickers (id, pack_id, code, label, image_url, mime, width, height, sort_order)
VALUES
    -- Bộ 1: ute_daily (mặc định)
    ('22222222-2222-4222-8222-222222220101', '11111111-1111-4111-8111-111111111101', 'hi', 'Xin chào', '/api/stickers/assets/ute_daily/hi.png', 'image/png', 256, 256, 1),
    ('22222222-2222-4222-8222-222222220102', '11111111-1111-4111-8111-111111111101', 'ok', 'OK luôn', '/api/stickers/assets/ute_daily/ok.png', 'image/png', 256, 256, 2),
    ('22222222-2222-4222-8222-222222220103', '11111111-1111-4111-8111-111111111101', 'thanks', 'Cảm ơn', '/api/stickers/assets/ute_daily/thanks.png', 'image/png', 256, 256, 3),
    ('22222222-2222-4222-8222-222222220104', '11111111-1111-4111-8111-111111111101', 'haha', 'Cười xỉu', '/api/stickers/assets/ute_daily/haha.png', 'image/png', 256, 256, 4),
    ('22222222-2222-4222-8222-222222220105', '11111111-1111-4111-8111-111111111101', 'love', 'Thả tim', '/api/stickers/assets/ute_daily/love.png', 'image/png', 256, 256, 5),
    ('22222222-2222-4222-8222-222222220106', '11111111-1111-4111-8111-111111111101', 'fighting', 'Cố lên', '/api/stickers/assets/ute_daily/fighting.png', 'image/png', 256, 256, 6),
    -- Bộ 2: ute_campus (cửa hàng)
    ('22222222-2222-4222-8222-222222220201', '11111111-1111-4111-8111-111111111102', 'deadline', 'Chạy deadline', '/api/stickers/assets/ute_campus/deadline.png', 'image/png', 256, 256, 1),
    ('22222222-2222-4222-8222-222222220202', '11111111-1111-4111-8111-111111111102', 'coffee', 'Cà phê thôi', '/api/stickers/assets/ute_campus/coffee.png', 'image/png', 256, 256, 2),
    ('22222222-2222-4222-8222-222222220203', '11111111-1111-4111-8111-111111111102', 'study', 'Học bài nào', '/api/stickers/assets/ute_campus/study.png', 'image/png', 256, 256, 3),
    ('22222222-2222-4222-8222-222222220204', '11111111-1111-4111-8111-111111111102', 'exam', 'Qua môn rồi', '/api/stickers/assets/ute_campus/exam.png', 'image/png', 256, 256, 4),
    ('22222222-2222-4222-8222-222222220205', '11111111-1111-4111-8111-111111111102', 'sleepy', 'Buồn ngủ quá', '/api/stickers/assets/ute_campus/sleepy.png', 'image/png', 256, 256, 5),
    ('22222222-2222-4222-8222-222222220206', '11111111-1111-4111-8111-111111111102', 'celebrate', 'Ăn mừng', '/api/stickers/assets/ute_campus/celebrate.png', 'image/png', 256, 256, 6)
ON CONFLICT (pack_id, code) DO NOTHING;
```

### Static Sticker Assets Packaging

- Package 12 lightweight transparent `256x256` PNG files in `backend/src/main/resources/stickers/ute_daily/{hi,ok,thanks,haha,love,fighting}.png` and `backend/src/main/resources/stickers/ute_campus/{deadline,coffee,study,exam,sleepy,celebrate}.png` (can be generated cleanly with a crisp flat vector-style Java `BufferedImage` + `Graphics2D` generator script during Wave 1 so every file is real, visually distinct, colorful, and ~4–8 KB).
- Serve via `StickerController`: `GET /api/stickers/assets/{packCode}/{fileName}`:
  - Protected by Spring Security Bearer JWT (`SecurityConfig` `.anyRequest().authenticated()`).
  - Strict regex validation: `packCode` matches `^[a-z0-9_-]{1,50}$`, `fileName` matches `^[a-z0-9_-]{1,50}\.(png|webp)$` (blocks `..`, `/`, `\`, null bytes).
  - Loads from `ClassPathResource("stickers/" + packCode + "/" + fileName)`; returns `404 NOT_FOUND` if missing; sets `Content-Type: image/png` (or `image/webp`), `Cache-Control: private, max-age=86400`, `X-Content-Type-Options: nosniff`.

---

## 2. REST & WebSocket Contract Extensions (`D-13`, One-Way)

Before implementation starts in Wave 1, synchronize `docs/API_CONTRACT.md`, `docs/FLOWS.md`, and `docs/ERD.md` with the exact contract below.

### 2.1 New & Extended REST Endpoints

| Method & Path | Request Body | Response | Status / Error Codes & Invariants |
|---|---|---|---|
| `POST /api/conversations/{id}/messages` *(extended)* | Add optional `stickerId` (UUID) for `type = "STICKER"` | `201 Created` / `200 OK` (replay) `MessageDto` | For `STICKER`: requires valid `stickerId` existing in `public.stickers` (`404 NOT_FOUND` if unknown); rejects non-null `text`, `mediaUrl`, `latitude`, `longitude` (`400 VALIDATION`). Enforces same group `expectedVisibleFromMessageId` (`409 MEMBERSHIP_CHANGED`), `403 NOT_A_MEMBER`, `403 BLOCKED`, `403 CONTACT_REQUIRED`, and 30/10s rate limit. Does **not** require the pack to remain installed at send time so offline-queued stickers succeed even if the pack was uninstalled before reconnect. |
| `POST /api/conversations/{id}/messages/{messageId}/recall` | *(empty)* | `200 OK` `MessageDto` (with `recalledAt`, cleared payload) | Lock `messages` row `FOR UPDATE`. Checks: (1) conversation & message exist in conversation (`404 NOT_FOUND`); (2) caller is active member (`left_at IS NULL`, else `403 NOT_A_MEMBER`), and in `DIRECT` chat not blocked either way (`403 BLOCKED`, `D-04`); (3) `message.sender_id == actorId` (else `403 FORBIDDEN`, `D-01`); (4) `message.type NOT IN ('SYSTEM', 'CALL')` (else `409 INVALID_STATE`); (5) if already recalled (`recalled_at IS NOT NULL`) -> return `200 OK` idempotent without duplicate `sync_events`; (6) `Duration.between(createdAt, clock.instant()) <= 24h` (strictly `> 24h` -> `409 RECALL_EXPIRED`). Sets `recalled_at = now()`, clears `text`, `attachment_id`, `latitude`, `longitude`, `sticker_id`, sets `attachments.revoked_at = now()` + schedules `afterCommit` file delete, deletes all rows in `message_reactions` for `messageId`, and writes `MESSAGE_RECALLED` to all active members (`left_at IS NULL`). |
| `DELETE /api/conversations/{id}/messages/{messageId}` | *(empty)* | `204 No Content` | Allowed in read-only (left group) and blocked 1-1 (`D-04`). Checks: (1) conversation exists (`404 NOT_FOUND`); (2) caller has a row in `conversation_members` (even if `left_at IS NOT NULL`, else `403 NOT_A_MEMBER`); (3) message exists in conversation and `message.id > member.visible_from_message_id` (else `404 NOT_FOUND`). Inserts `INSERT INTO public.message_deletions (user_id, message_id) VALUES (?, ?) ON CONFLICT DO NOTHING`. Only if a new row was inserted, writes `MESSAGE_DELETED_FOR_ME` **solely** for `actorId` (multi-device sync). Repeated calls return `204` idempotently. |
| `PUT /api/conversations/{id}/messages/{messageId}/reaction` | `{ "emoji": "❤️" }` or `{ "emoji": null }` | `200 OK` `ReactionUpdateResponse` | Checks: (1) active member (`left_at IS NULL`, else `403 NOT_A_MEMBER`); (2) in `DIRECT` chat not blocked either way (`403 BLOCKED`, `D-04`); (3) message exists in conversation, `message.id > visible_from_message_id`, and not deleted-for-me by caller (`404 NOT_FOUND`); (4) `message.type != 'SYSTEM'` and `message.recalled_at IS NULL` (else `409 INVALID_STATE`, `D-06`); (5) normalize `"\u2764"` -> `"\u2764\uFE0F"` (`"❤️"`); if non-null, must be in `{"❤️", "😂", "👍", "😮", "😢", "😡"}` (else `400 VALIDATION`). Lock message row or serialize per `(message_id, user_id)`: if `emoji == null` OR `emoji.equals(currentEmoji)` -> DELETE reaction (`action = "REMOVED"`, `emoji = null`); else UPSERT reaction (`action = "SET"`). Only when state actually changes, writes `MESSAGE_REACTION_UPDATED` to all active members (`left_at IS NULL`). |
| `GET /api/stickers/packs` | — | `200 OK` `List<StickerPackDto>` | Returns all sticker packs ordered by `sort_order ASC` with `installed = COALESCE(usp.installed, sp.is_default)` and nested `stickers[]` ordered by `sort_order ASC`. |
| `POST /api/stickers/packs/{packId}/install` | — | `204 No Content` | Validates `packId` exists (`404 NOT_FOUND`). Upserts `user_sticker_packs (user_id, pack_id, installed = TRUE, installed_at = now())`. Idempotent. |
| `DELETE /api/stickers/packs/{packId}/install` | — | `204 No Content` | Validates `packId` exists (`404 NOT_FOUND`). Upserts `user_sticker_packs (user_id, pack_id, installed = FALSE)`. Idempotent. Historical sticker messages remain untouched (`D-09`). |
| `GET /api/stickers/assets/{packCode}/{fileName}` | — | `200 OK` (`image/png` / `image/webp`) | Bearer-authenticated, strict path regex, `Cache-Control: private, max-age=86400`, `X-Content-Type-Options: nosniff`, `404 NOT_FOUND` if missing. |

### 2.2 DTO Extensions (`MessagingDtos` & `ChatDtos`)

- **`MessageDto`** gains 3 nullable fields (100% backward-compatible with `@JsonInclude(NON_NULL)`):
  - `String recalledAt` (ISO-8601 UTC, `null` if not recalled)
  - `StickerSummaryDto sticker` (`{ id, packId, code, label, imageUrl, width, height }`, present when `type = "STICKER"` and `recalledAt == null`)
  - `List<ReactionDto> reactions` (`[{ userId, emoji, createdAt }]`, populated in `GET /conversations/{id}/messages` when non-empty)
- **`ConversationService.buildConversationDto(viewerId, conversationId)` (`D-05`):**
  - Replace direct `h.lastMessageId` lookup with a per-viewer query selecting the latest message where `m.conversation_id = ? AND m.id > ? AND NOT EXISTS (SELECT 1 FROM public.message_deletions md WHERE md.user_id = ? AND md.message_id = m.id) ORDER BY m.id DESC LIMIT 1`.
  - Exclude `message_deletions` for `viewerId` in the `unreadCount` query as well.
  - Because recalled messages remain in `messages` (with `recalled_at != null`), a recalled latest message is returned as `lastMessage` -> clients render `"Tin nhắn đã được gỡ"`. When the latest message is deleted-for-me, the query naturally falls back to the previous remaining message (or `null` if none left).

### 2.3 Sync Events & WebSocket Frame Mappings

| `sync_events.event_type` | `entity_type` / `entity_id` | WS Frame `type` | Recipients | `payload_json` |
|---|---|---|---|---|
| `MESSAGE_RECALLED` | `MESSAGE` / `<messageId>` | `message.recalled` | All active members (`left_at IS NULL`) | `{ "messageId": 1042, "conversationId": "…", "recalledAt": "2026-11-27T10:00:00Z", "recalledBy": "…" }` |
| `MESSAGE_DELETED_FOR_ME` | `MESSAGE` / `<messageId>` | `message.deleted` | **Only `actorId`** | `{ "messageId": 1042, "conversationId": "…", "deletedAt": "2026-11-27T10:01:00Z" }` |
| `MESSAGE_REACTION_UPDATED` | `MESSAGE` / `<messageId>` | `message.reaction` | All active members (`left_at IS NULL`) | `{ "messageId": 1042, "conversationId": "…", "userId": "…", "emoji": "❤️" /* or null when removed */, "createdAt": "2026-11-27T10:02:00Z" }` |

- `SyncEventRealtimeDispatcher.mapEventTypeToWsType()` adds the 3 cases above [VERIFIED: `SyncEventRealtimeDispatcher.java#L106-L120`].
- `ChatWebSocketManager.SocketListener.onMessage()` adds `case "message.recalled": case "message.deleted": case "message.reaction": shouldRequestSync = true; break;` [VERIFIED: `ChatWebSocketManager.java#L442-L450`]. Consistent with Phase 5/6, WS frames NEVER mutate Room directly — they trigger single-flight `SyncEngine.requestSync()`.

---

## 3. Room Schema Upgrade: `AppDatabase` v3 -> v4 (`MIGRATION_3_4`, One-Way)

### Inspection of Current Room Schema (`AppDatabase` v3)

- `AppDatabase` is at `version = 3, exportSchema = true` with `MIGRATION_1_2` and `MIGRATION_2_3` [VERIFIED: `AppDatabase.java#L15-L48`].
- `MessageEntity` has `localId`, `serverId`, `clientId`, `conversationId`, `senderId`, `type`, `text`, `normalized_text`, `attachmentJson`, `createdAt`, `status`, `errorCode`, `queuedVisibleFromMessageId` [VERIFIED: `MessageEntity.java#L11-L59`].
- `MessageEntity.attachmentJson` already stores non-TEXT metadata JSON via `SyncEventApplier.serializeMessageMetadata()` [VERIFIED: `SyncEventApplier.java#L105-L121`]. Reusing `attachmentJson` for sticker metadata (`stickerId`, `packId`, `code`, `label`, `imageUrl`, `width`, `height`) avoids bloating `message` columns.
- `ConversationDao.observeConversationSummaries()` joins `message` with a correlated subquery selecting the newest visible row per conversation [VERIFIED: `ConversationDao.java#L22-L44`]. Adding `lm.recalledAt AS lastMessageRecalledAt` to this projection and `ConversationListItem` makes `D-05` work automatically for both recall and delete-for-me!

### Exact `MIGRATION_3_4` Diff

1. **Alter `message`:**
   - `ALTER TABLE message ADD COLUMN recalledAt INTEGER` (`@Nullable private Long recalledAt;` in `MessageEntity`, UTC epoch millis).
2. **Create `message_reaction` (`MessageReactionEntity`):**
   ```sql
   CREATE TABLE IF NOT EXISTS `message_reaction` (
       `messageServerId` INTEGER NOT NULL,
       `userId` TEXT NOT NULL,
       `emoji` TEXT NOT NULL,
       `createdAt` INTEGER NOT NULL,
       PRIMARY KEY(`messageServerId`, `userId`)
   );
   CREATE INDEX IF NOT EXISTS `index_message_reaction_messageServerId` ON `message_reaction` (`messageServerId`);
   ```
3. **Create `sticker_pack` (`StickerPackEntity`):**
   ```sql
   CREATE TABLE IF NOT EXISTS `sticker_pack` (
       `packId` TEXT NOT NULL,
       `code` TEXT NOT NULL,
       `name` TEXT NOT NULL,
       `description` TEXT,
       `thumbnailUrl` TEXT NOT NULL,
       `installed` INTEGER NOT NULL,
       `isDefault` INTEGER NOT NULL,
       `sortOrder` INTEGER NOT NULL,
       PRIMARY KEY(`packId`)
   );
   ```
4. **Create `sticker` (`StickerEntity`):**
   ```sql
   CREATE TABLE IF NOT EXISTS `sticker` (
       `stickerId` TEXT NOT NULL,
       `packId` TEXT NOT NULL,
       `code` TEXT NOT NULL,
       `label` TEXT NOT NULL,
       `imageUrl` TEXT NOT NULL,
       `localPath` TEXT,
       `width` INTEGER NOT NULL,
       `height` INTEGER NOT NULL,
       `sortOrder` INTEGER NOT NULL,
       PRIMARY KEY(`stickerId`)
   );
   CREATE INDEX IF NOT EXISTS `index_sticker_packId` ON `sticker` (`packId`);
   ```

### DAO & FTS4 Invariants

- **On `MESSAGE_RECALLED` in `SyncEventApplier` (inside Room transaction):**
  1. Look up `MessageEntity existing = messageDao.findByServerId(messageId)`.
  2. Call `messageDao.markRecalledByServerId(messageId, recalledAtMs)` -> `UPDATE message SET text = NULL, normalized_text = NULL, attachmentJson = NULL, recalledAt = :recalledAt WHERE serverId = :serverId`.
     - Room's `@Fts4(contentEntity = MessageEntity.class)` `BEFORE UPDATE` / `AFTER UPDATE` triggers automatically purge the old text from `message_fts`!
     - As defense-in-depth, also add `AND m.recalledAt IS NULL` to `MessageDao.searchInConversationVisibleInternal` and `searchGlobalVisibleInternal`.
  3. If `existing != null`, call `translationDao.deleteAllForMessage(existing.getClientId())` (`DELETE FROM message_translation WHERE messageKey = :key`) to clear cached translations (`D-02`).
  4. Call `reactionDao.deleteByMessageServerId(messageId)` so recalled messages show zero reactions.
- **On `MESSAGE_DELETED_FOR_ME` in `SyncEventApplier` (inside Room transaction):**
  1. Look up `MessageEntity existing = messageDao.findByServerId(messageId)`.
  2. Call `messageDao.deleteByServerId(messageId)` (Room FTS4 `BEFORE DELETE` trigger automatically removes it from `message_fts`, `D-03`).
  3. Call `reactionDao.deleteByMessageServerId(messageId)` and (if `existing != null`) `translationDao.deleteAllForMessage(existing.getClientId())`.
  4. Recompute `conversation.lastMessageId`, `lastMessageAt`, and `unreadCount` from remaining visible messages so `ChatListFragment` preview immediately falls back to the previous remaining message or empty (`D-05`).
- **On `MESSAGE_REACTION_UPDATED` in `SyncEventApplier` (inside Room transaction):**
  - If `emoji == null || emoji.isEmpty()`: `reactionDao.delete(messageId, userId)`.
  - Else: `reactionDao.upsert(new MessageReactionEntity(messageId, userId, emoji, createdAtMs))`.
- **Observing Reactions per Conversation (`ReactionDao`):**
  - `@Query("SELECT r.* FROM message_reaction r INNER JOIN message m ON m.serverId = r.messageServerId WHERE m.conversationId = :conversationId ORDER BY r.createdAt ASC, r.userId ASC") LiveData<List<MessageReactionEntity>> observeByConversation(String conversationId);`
  - Group in `ChatRoomViewModel` into `Map<Long, List<MessageReactionEntity>>` keyed by `messageServerId`, passed to `MessageAdapter.setReactionsMap(...)` with a dedicated `PAYLOAD_REACTION` partial bind (zero RecyclerView flicker!).

---

## 4. Android Architecture, Offline Queue & UI Integration (`D-06`..`D-12`)

### 4.1 `SendMessageWorker` Extension for `type = "STICKER"` (`D-10`)

- In `SendMessageWorker.executeSend()` [VERIFIED: `SendMessageWorker.java#L294-L315`], add `case "STICKER": return executeStickerSend(c);`:
  - Parse `stickerId` from `c.msg.getAttachmentJson()`. If missing/blank, call `failMessageOnly(c, "VALIDATION")` and return `Result.success()`.
  - Build `ChatDtos.SendMessageRequest` with `type = "STICKER"`, `stickerId = stickerId`, `text = null`, `mediaUrl = null`, `latitude = null`, `longitude = null`, `expectedVisibleFromMessageId = c.msg.getQueuedVisibleFromMessageId()`.
  - Delegate directly to existing `sendAndHandle(c, body, null)` — inherits idempotent `clientId` replay, exponential backoff, `429 Retry-After`, `401` session isolation, and `403`/`409 MEMBERSHIP_CHANGED` permanent failure handling automatically!
- In `RealChatRepository.enqueueStickerMessage(conversationId, StickerEntity sticker)`:
  - Writes a `MessageEntity` with `type = "STICKER"`, `status = "SENDING"`, `clientId = UUID`, `attachmentJson` containing `{ stickerId, packId, code, label, imageUrl, localPath, width, height }`, and `queuedVisibleFromMessageId` for `GROUP` chats, then calls `SendMessageWorker.enqueue(workManager, clientId, sessionKey)`.

### 4.2 `StickerRepository` & `StickerCacheManager` (`D-08`, `D-09`)

- Dedicated `StickerApi` interface (`api/stickers/packs`, `POST/DELETE api/stickers/packs/{packId}/install`) + `StickerRepository` (with `RealStickerRepository` and `FakeStickerRepository` for `useFakeData=true` compatibility).
- **Caching & Offline Browsing (`D-08`, `D-09`):**
  - Store downloaded sticker PNGs in `new File(appContext.getFilesDir(), "stickers/" + packCode + "/" + stickerCode + ".png")`.
  - When syncing `/api/stickers/packs` or installing a pack, download all stickers of installed packs on `MEDIA_IO` using `ApiClient.getMediaOkHttpClient()` (Bearer header attached automatically by `AuthInterceptor` for `BACKEND_HOST`) and update `sticker.localPath` in Room `StickerDao`.
  - **Historical Sticker Preservation (`D-09`):** When the user uninstalls a pack, mark `sticker_pack.installed = 0` in Room (hiding it from the sticker picker tray), while historical `MessageEntity` rows still carry `imageUrl` (and optional cached file). `MessageAdapter` loads a sticker by checking `localPath` (if file exists on disk) -> else `Glide` loading `ApiClient.resolveBackendStickerUrl(BuildConfig.BACKEND_BASE_URL, imageUrl)` with Bearer auth & Glide disk cache! Thus historical stickers render seamlessly even after uninstalling the pack.
  - **Session Isolation (`Injection.cleanupDepartingSession`):** Add `deleteDirectoryContents(new File(appContext.getFilesDir(), "stickers"))` on `DISK` alongside `pending_media`, `media_cache`, `camera` [VERIFIED: `Injection.java#L386-L394`] so switching accounts leaves zero stale files.

### 4.3 UI Components (`ChatRoomFragment`, `MessageAdapter`, `ChatListFragment`)

1. **Unified Long-Press BottomSheet (`MessageActionBottomSheet`, `D-11`):**
   - Replaces `ChatRoomFragment.showTranslationMenu(message)` [VERIFIED: `ChatRoomFragment.java#L249-L251,L764-L791`].
   - Triggered on long-press of **any** non-`SYSTEM` sent bubble (`TEXT`, `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION`, `CALL`, `STICKER`, and recalled messages so users can still "Delete for Me").
   - Layout:
     - **Top Emoji Bar** (`❤️`, `😂`, `👍`, `😮`, `😢`, `😡`): shown only when `message.getServerId() != null && message.getRecalledAt() == null && !"SYSTEM".equals(type) && !readOnly && !isBlocked`. Highlights the user's current reaction emoji. Tapping calls `vm.toggleReaction(message, emoji)` and dismisses.
     - **Action Rows:**
       - `Dịch sang Tiếng Việt` / `Dịch sang Tiếng Anh` / `Ẩn bản dịch`: shown only when `"TEXT".equals(type) && recalledAt == null && text != null && !text.isBlank()` (works 100% offline).
       - `Gỡ tin nhắn` (Recall for Everyone): shown only when `meId.equals(senderId) && serverId != null && recalledAt == null && !"SYSTEM".equals(type) && !"CALL".equals(type) && !readOnly && !isBlocked && (System.currentTimeMillis() - createdAt) < 86_400_000L`. Opens confirmation dialog before calling `vm.recallMessage(message)`.
       - `Xóa ở phía bạn` (Delete for Me): shown whenever `serverId != null` (including recalled messages, `CALL`, and read-only/blocked rooms per `D-04`). Opens confirmation dialog before calling `vm.deleteMessageForMe(message)`.
2. **Reaction Chip Cluster & `ReactionDetailsBottomSheet` (`D-07`):**
   - Add a `LinearLayout` / `ChipGroup` (`@+id/reactions_container`) directly below `bubble` in `item_message_out.xml`, `item_message_in.xml`, `item_message_summary_out.xml`, `item_message_summary_in.xml`, and the new `item_message_sticker_out.xml`, `item_message_sticker_in.xml`.
   - Group reactions by emoji preserving order of `FIXED_EMOJIS`, render compact rounded pills (`❤️ 2`, `😂 1`), highlighting the pill containing `meId`.
   - Tapping the reaction cluster opens `ReactionDetailsBottomSheet` listing tabs/rows of `(avatar/initials, displayName from users map, emoji)`.
3. **Sticker Bubble & Sticker Tray / Store (`D-08`..`D-11`):**
   - `MessageAdapter`: add `TYPE_STICKER_OUT = 6`, `TYPE_STICKER_IN = 7` (preserving `0..5` constants so existing Phase 5/6 adapter unit tests never break!).
   - Recalled messages (`message.getRecalledAt() != null`): render in `TYPE_TEXT_OUT` / `TYPE_TEXT_IN` (or in-place in their bubble with muted italic text `R.string.chat_message_recalled` = `"Tin nhắn đã được gỡ"` / `"Message recalled"`, hiding media, translation box, and reaction chips, and disabling media click listeners). Routing recalled messages through `TYPE_TEXT_OUT` / `TYPE_TEXT_IN` is cleanest because all media/sticker/location fields are already `null` on recall!
   - `fragment_chat_room.xml`: add `ImageButton @+id/sticker` immediately adjacent to `EditText @+id/input` (between `attach` and `input`, or between `input` and `mic`) + collapsible `sticker_tray` panel below `composer` (hidden when keyboard opens or when `readOnly` is true) with pack tab bar, 4-column `RecyclerView` grid of installed stickers, and a `+` Store button opening `StickerStoreBottomSheet` (lists all packs with preview thumbnails and `Tải về` / `Gỡ bộ` button).
4. **Offline UX (`D-12`):**
   - In `RealChatRepository`: `recallMessage`, `deleteMessageForMe`, and `toggleReaction` perform direct REST calls on `CHAT_NETWORK` (no WorkManager queue). On `IOException` / network unavailability, return `ChatResult.error("NETWORK")` -> `ChatRoomFragment` displays Snackbar `R.string.chat_action_requires_network` (*"Cần kết nối mạng để thực hiện thao tác này"*) and keeps the current Room message untouched.
   - `SystemMessageFormatter.preview()` & `NotificationHelper`: add `"STICKER"` -> `R.string.chat_type_sticker` (`"[Nhãn dán]"` / `"[Sticker]"`) and check `recalledAt != null` -> `R.string.chat_message_recalled` (`"Tin nhắn đã được gỡ"` / `"Message recalled"`).

---

## 5. Common Pitfalls & Mitigations

| # | Pitfall | Impact | Mitigation |
|---|---|---|---|
| 1 | **Recalled media still downloadable by uploader (`MediaService.java#L314-L319`)** | Violates `D-02` security invariant | Add `attachments.revoked_at`, set it on recall + delete disk file `afterCommit`, reject `revoked_at != null` with `404` in `MediaService.prepareDownload()`. |
| 2 | **Unicode variation selector mismatch on `❤️`** | Android keyboards/JSON may send `U+2764` without `U+FE0F`, failing PostgreSQL `CHECK` | Normalize `"\u2764"` -> `"\u2764\uFE0F"` on backend before validation and DB write; use constant `"\u2764\uFE0F"` everywhere on Android. |
| 3 | **FTS4 still matching recalled text** | Recalled secret text leaks in offline search | `MessageDao.markRecalledByServerId` clears `text = NULL, normalized_text = NULL` (firing Room FTS4 update trigger) + add `AND m.recalledAt IS NULL` in FTS4 queries. |
| 4 | **Delete-for-me in left group blocked by `left_at IS NULL` check** | Violates `D-04` | `DELETE /conversations/{id}/messages/{messageId}` checks `EXISTS(conversation_members WHERE conversation_id = ? AND user_id = ?)` **without** requiring `left_at IS NULL` (while still enforcing `m.id > visible_from_message_id`). |
| 5 | **Breaking Phase 5/6 `MessageAdapter` or `ChatRepository` tests** | Regression in `ChatViewModelTest` / `FakeRepositoriesTest` | Preserve `MessageAdapter` viewType constants `0..5` (append `6`, `7`), provide default/no-op implementations in `FakeChatRepository`, and keep `ChatRoomViewModel` 3-arg constructor intact. |
| 6 | **Sticker URL bypassing `AuthInterceptor` host guard** | Security violation (`DEPLOYMENT_SECURITY.md`) | Enforce strict `ApiClient.resolveBackendStickerUrl()` regex `^/api/stickers/assets/[a-z0-9_-]{1,50}/[a-z0-9_-]{1,50}\.(png|webp)$` and load via `UteChatGlideModule` / `ApiClient.getMediaOkHttpClient()`. |
| 7 | **Uninstalling a sticker pack breaking historical chat bubbles** | Violates `D-09` | Keep `imageUrl` in `MessageEntity.attachmentJson`; on uninstall, only flip `sticker_pack.installed = 0` so picker hides the pack while `MessageAdapter` still renders historical stickers from cache or authenticated Glide URL. |

---

## 6. Recommended Execution Wave Breakdown (for Planner)

1. **Wave 1 (One-Way Contracts & Migrations):**
   - Sync `docs/API_CONTRACT.md`, `docs/FLOWS.md`, `docs/ERD.md`.
   - Create `V9__message_actions_reactions_stickers.sql` + 12 static PNG sticker assets in `backend/src/main/resources/stickers/`.
   - Bump Room `AppDatabase` `v3 -> v4` (`MIGRATION_3_4`, `4.json`, `MessageReactionEntity`, `StickerPackEntity`, `StickerEntity`, `ReactionDao`, `StickerDao`, updated `MessageDao`/`ConversationDao`/`TranslationDao`).
2. **Wave 2 (Backend Services, Controllers & Realtime):**
   - Extend `MessageService` (recall 24h with `Clock`, delete-for-me, reactions toggle/6-emoji allowlist, `STICKER` send, `getMessages` excluding deletions + attaching reactions).
   - Update `MediaService` (`revoked_at` + `message_deletions` check), `ConversationService` (`lastMessage` & `unreadCount` excluding `message_deletions`), `SyncEventRealtimeDispatcher` (`message.recalled`, `message.deleted`, `message.reaction`).
   - Add `StickerService` & `StickerController` (`/api/stickers/packs`, `/install`, `/assets/{packCode}/{fileName}`).
   - Full backend integration test suite on `EmbeddedPostgres`.
3. **Wave 3 (Android Data Layer — Sync, Worker, Repositories, Sticker Cache):**
   - Extend `ChatDtos`, `ChatApi`, `StickerApi`, `ApiClient.resolveBackendStickerUrl()`.
   - Extend `ChatWebSocketManager` & `SyncEventApplier` (`MESSAGE_RECALLED`, `MESSAGE_DELETED_FOR_ME`, `MESSAGE_REACTION_UPDATED`, `STICKER` metadata).
   - Extend `SendMessageWorker` for `type = "STICKER"` offline queue.
   - Add `StickerCacheManager`, `StickerRepository` (`RealStickerRepository` + `FakeStickerRepository`), extend `ChatRepository` / `RealChatRepository` / `FakeChatRepository` / `Injection` (including `stickers/` cleanup on logout).
4. **Wave 4 (Android UI — Unified BottomSheet, Reactions, Sticker Tray/Store & Previews):**
   - Update `strings_chat.xml` (EN & VI), `SystemMessageFormatter`, `NotificationHelper`, `ConversationListAdapter`.
   - Extend `MessageAdapter` (`TYPE_STICKER_OUT`/`IN`, recalled bubble rendering, reaction chip cluster with `PAYLOAD_REACTION`, unified long-press listener).
   - Implement `MessageActionBottomSheet`, `ReactionDetailsBottomSheet`, `StickerTray` & `StickerStoreBottomSheet`, wire into `ChatRoomFragment` & `ChatRoomViewModel`.
   - Run full backend + Android JVM + lint + Room migration tests + 2-device E2E verification.
