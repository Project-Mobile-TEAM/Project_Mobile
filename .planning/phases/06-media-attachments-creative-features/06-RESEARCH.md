# Phase 6: Media Attachments & Creative Features - Research

**Date:** 2026-11-21
**Author:** GSD Phase Researcher
**Phase:** 06 — Media Attachments & Creative Features
**Requirement IDs:** `REQ-messaging-media-attachments`, `REQ-notifications-sync`, `REQ-voice-call-webrtc`, `REQ-message-translation`
**Status:** COMPLETE

---

## User Constraints & Phase Boundary (from `06-CONTEXT.md`)

Phase 6 builds on the Phase 5 foundation located on branch `feature/phase-5-core` (`AppDatabase` v2, `SendMessageWorker`, `SyncEngine`, `SyncEventApplier`, `ChatWebSocketManager`, `ChatRoomFragment` non-TEXT summary placeholders) and covers four tightly scoped pillars:

1. **Multimedia Attachments, Lightweight Location, `FileProvider` & Offline Upload Queue (`D-01`..`D-04`):**
   - `D-01`: Local disk storage on backend via `MEDIA_STORAGE_DIR`; `POST /api/media` & `GET /api/media/{id}` supporting HTTP `Range` (`206 Partial Content`), protected strictly by `Authorization: Bearer <token>` and conversation membership check (`403`), with zero query tickets or JWTs in URLs.
   - `D-02`: 4 core media types (compressed Image long edge <= 1600px / <= 10MB; Voice `.m4a` AAC <= 5 min / <= 10MB; Video <= 50MB; Document File <= 25MB with MIME allowlist) **plus** lightweight Location (`LOCATION` single-shot via `FusedLocationProviderClient`, coordinate card + external `geo:` Intent — **no** embedded `SupportMapFragment` or Google Maps SDK API key).
   - `D-03`: Fullscreen zoom/pinch image viewer inside app; inline Voice `.m4a` bubble player (play/pause + seekbar); in-app Video playback via `Media3 ExoPlayer` + *"Mở bằng app ngoài"* via `FileProvider` (`FLAG_GRANT_READ_URI_PERMISSION`); download-with-Bearer & open Document Files via `FileProvider`.
   - `D-04` *(costly)*: Extend Phase 5 per-session `SendMessageWorker` (`send-messages:<sessionKey>`): copy/compress media to persistent app-private storage, track state/progress in Room `attachment_transfer`, upload first (`POST /api/media`), **persist `mediaUrl` immediately on upload success** so retries never re-upload duplicates, mark `413 FILE_TOO_LARGE` / `415 UNSUPPORTED_TYPE` as `FAILED` immediately without blocking subsequent queued messages, and delete temporary copies after server ack or user deletion of failed message. Mandatory verification of two demo scenarios: (1) picking media while offline; (2) network drop after upload completes but before `POST /messages` succeeds.
2. **Notifications & 15-Minute Periodic Background Sync (`D-05`..`D-08`):**
   - `D-05`: Suppress notifications for self-sent messages, `muted = true` conversations, and the currently open foreground `conversationId`; group per conversation with `NotificationCompat.MessagingStyle`; tap opens `ChatRoomFragment` directly for that conversation.
   - `D-06`: Exactly **2 `NotificationChannel`s** in V1: `messages` and `calls` (high priority incoming calls & missed calls). Keep friend requests in Contacts UI (no friend-request channel). On logout / `401` / `4401` / account switch: cancel all notifications from the old session and ignore stale delayed results.
   - `D-07`: Request `POST_NOTIFICATIONS` on Android 13+ (API 33+) **once** after successful login into `MainActivity`; never re-prompt annoyingly if denied.
   - `D-08`: `PeriodicWorkRequest` every 15 minutes per session (`ExistingPeriodicWorkPolicy.KEEP`, `NetworkType.CONNECTED`, canceled immediately on logout/`401`/`4401`) invoking Phase 5 single-flight `SyncEngine` so it never races WebSocket/foreground cursors; post notifications only after committing to Room (best-effort, no FCM).
3. **1-1 Voice Calling with WebRTC & Coturn (`D-09`..`D-12`):**
   - `D-09`: Backend reads `COTURN_URLS`, `COTURN_SHARED_SECRET`, `COTURN_TTL_SECONDS` to issue short-lived coturn REST HMAC-SHA1 `iceServers[]` in `POST /api/calls` and `POST /api/calls/{id}/accept` (supporting TCP/TLS 443 and UDP 3478/443); fallback to public STUN in local dev; provide EC2 `turnserver.conf` template & runbook.
   - `D-10` *(one-way)*: Strict call invariants per F-21/F-22: `ACCEPTED` non-blocked friends only (`403 BLOCKED` before `403 CONTACT_REQUIRED`, group -> `400 CALL_UNSUPPORTED`); `409 CALL_BUSY` without ringing when either party is busy; 45s auto-timeout `RINGING -> MISSED`; atomic `accept` / `reject` / `timeout` state transitions; `call.handled_elsewhere` to callee's other devices on accept/reject; **2-device signaling lock** for `call.offer`/`call.answer`/`call.ice` after accept; **exactly one idempotent `CALL` message** inserted per saved call on terminal state (`ENDED`/`MISSED`/`REJECTED`/`BUSY`/`FAILED`). Sync `docs/API_CONTRACT.md` and `docs/FLOWS.md` before implementation.
   - `D-11`: Dedicated call screen (`CallFragment` within `MainActivity`) paired with `Foreground CallService` keeping `PeerConnection` alive across rotation, 3 core controls (Mute mic, Speaker/Earpiece toggle, End call), `mm:ss` timer, ringtone/vibration on `call.incoming`, and `RECORD_AUDIO` permission check/request before calling or accepting.
   - `D-12` *(costly)*: Use `io.getstream:stream-webrtc-android` wrapped behind `WebRtcCallEngine` seam so JVM unit tests verify the full state machine and 2 real devices verify LAN/coturn audio calls.
4. **On-Device ML Kit Translation (`D-13`..`D-15`):**
   - `D-13`: **No Language Identification library**; long-press context menu on `TEXT` messages offers *"Dịch sang Tiếng Việt"* and *"Dịch sang Tiếng Anh"* directly.
   - `D-14`: Render translation inside the same bubble below a thin divider with small label *"Đã dịch"* and button *"Ẩn bản dịch"*; `TEXT` only (never `SYSTEM`, `CALL`, or textless media); persist cache in Room `message_translation` by `(message, targetLang)`.
   - `D-15`: On-demand model download (~30MB): auto-download on Wi-Fi, **prompt user confirmation on cellular**, show *"Đang tải gói ngôn ngữ…"* with **indeterminate progress (no fake %)**; on offline/failure keep original message and allow retry; once downloaded, translate 100% offline and cache in Room.

---

## Project Constraints (from `GEMINI.md`, `AGENTS.md`, `DEPLOYMENT_SECURITY.md`)

1. **100% Java 17 & Android Views Stack:**
   - Android (`com.utechat`): `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`, AGP `9.5.1`, Java 17 (`JavaVersion.VERSION_17`), Views + XML, ViewBinding, MVVM + LiveData, Navigation Component (`MainActivity`), Room SQLite (`annotationProcessor`), WorkManager, Retrofit + OkHttp [VERIFIED: `feature/phase-5-core:app/build.gradle.kts#L57-L134`, `feature/phase-5-core:gradle/libs.versions.toml#L1-L54`].
   - **Forbidden:** Kotlin source files, KSP, Kapt, Coroutines, Flow, Jetpack Compose, Hilt/Dagger [VERIFIED: `GEMINI.md#L17`, `AGENTS.md#L17`].
   - **Banned Internal API:** Never use `ArchTaskExecutor.getInstance()` in `app/src/main/**` (triggers `RestrictedApi` error in `lintDebug`). Always use `MainThreadExecutor.INSTANCE` (`Handler(Looper.getMainLooper())`) or injected `Executor` [VERIFIED: `05-RESEARCH.md#L112`, `feature/phase-5-core:app/src/main/java/com/utechat/data/repository/real/RealChatRepository.java#L74`].
   - Backend (`com.utechat.backend`): Spring Boot `3.3.5`, Java 17, Spring Web, Spring Security (stateless JWT), Spring WebSocket pure JSON (`/ws`, no STOMP), Spring JDBC (`JdbcTemplate`), Flyway, PostgreSQL (`EmbeddedPostgres` `2.0.7` for tests, no Docker) [VERIFIED: `feature/phase-5-core:backend/pom.xml#L1-L110`].
2. **100% Educational Vietnamese In-Code Comments:** Every new or modified Java class/method/field, XML layout/nav/strings, SQL migration, and ProGuard rule MUST include concise Vietnamese comments explaining what each block does and why [VERIFIED: `GEMINI.md#L3-L15`, `AGENTS.md#L3-L15`].
3. **Security Rules (`docs/DEPLOYMENT_SECURITY.md`):**
   - Release uses HTTPS/WSS only (`cleartextTrafficPermitted="false"` already active in `network_security_config.xml#L3-L6`); Debug allows local HTTP/WS.
   - `mediaUrl` stays relative (`/api/media/{attachmentId}`) resolved against `BuildConfig.BACKEND_BASE_URL`; **never** append JWT or query tickets to URLs; **never** attach `Authorization` header to non-backend hosts (`AuthInterceptor.BACKEND_HOST` guard already enforces this) [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L10,L25,L29`, `feature/phase-5-core:app/src/main/java/com/utechat/data/remote/api/AuthInterceptor.java#L14-L25`].
   - `COTURN_SHARED_SECRET` never enters APK/logs; Release keeps `HttpLoggingInterceptor.Level.NONE` [VERIFIED: `docs/DEPLOYMENT_SECURITY.md#L27-L28`, `feature/phase-5-core:app/src/main/java/com/utechat/data/remote/api/ApiClient.java#L17`].
   - Every new/altered PostgreSQL table MUST preserve `ENABLE ROW LEVEL SECURITY` with zero public policies [VERIFIED: `docs/ARCHITECTURE_DECISIONS.md#L76`, `feature/phase-5-core:backend/src/main/resources/db/migration/V5__sync_and_messaging.sql#L52`].
4. **Anti-Conflict Resource/Module Boundaries (`ADR-025`, `06-CONTEXT.md`):**
   - Use separate EN/VI strings for media and calls. Keep the media destinations in `nav_chat.xml` and the shared call destination and global action in `nav_graph.xml`.
   - Keep `ChatRepository` backward-compatible with `FakeChatRepository` and `FakeRepositoriesTest` (`getConversations`, `getMessages`, `sendMessage`) [VERIFIED: `feature/phase-5-core:app/src/main/java/com/utechat/data/repository/ChatRepository.java#L21-L24`].

---

## 1. Exact Dependency Coordinates & `compileSdk = 36` / Java 17 / 16KB Compatibility

All dependencies below are verified against `gradle/libs.versions.toml` (`compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`, `JavaVersion.VERSION_17`, AGP `9.5.1`, pure `annotationProcessor`) [VERIFIED: `feature/phase-5-core:app/build.gradle.kts#L65-L109`]:

| Component | Maven Coordinates (`libs.versions.toml`) | Verification & Compatibility Notes |
|---|---|---|
| **Media3 ExoPlayer** (`D-03`) | `androidx.media3:media3-exoplayer:1.11.1`<br>`androidx.media3:media3-ui:1.11.1`<br>`androidx.media3:media3-datasource-okhttp:1.11.1` | Stable release `1.11.1` (Sep 10, 2026) [CITED: https://developer.android.com/jetpack/androidx/releases/media3]. Built with `compileSdk = 35`, Java 17 compatible. **Crucial:** `media3-datasource-okhttp` provides `OkHttpDataSource.Factory(okHttpClient)` so video streaming against `GET /api/media/{id}` automatically passes `Authorization: Bearer` via `AuthInterceptor` and supports HTTP `Range` seeking without query tickets! |
| **Fused Location** (`D-02`) | `com.google.android.gms:play-services-location:21.4.0` | Latest stable release (Jun 25, 2026) [CITED: https://mvnrepository.com/artifact/com.google.android.gms/play-services-location]. Java 17 `Task<Location>` API (`LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(...)`). **Do NOT add `play-services-maps`** (`D-02`). |
| **WebRTC Android** (`D-12`) | `io.getstream:stream-webrtc-android:1.3.10` | Latest stable release on Maven Central (Sep 11, 2025) [CITED: https://mvnrepository.com/artifact/io.getstream/stream-webrtc-android]. Pure Java `org.webrtc.*` APIs (do NOT add `-ktx` or `-compose`). **16 KB page-size alignment:** `1.3.10` contains 16 KB ELF-aligned `.so` binaries (`libjingle_peerconnection_so.so`) [CITED: https://github.com/GetStream/webrtc-android]. Consumer ProGuard rules are bundled in the AAR; keep fallback `-keep class org.webrtc.** { *; }` in `rules.keep`. |
| **ML Kit Translation** (`D-13`..`D-15`) | `com.google.mlkit:translate:17.0.3` | Latest release on Google Maven (`dl.google.com`) [CITED: https://developers.google.com/ml-kit/release-notes]. **Strictly exclude `com.google.mlkit:language-id`** (`D-13`). **16 KB Alignment Pitfall:** `17.0.3` packages `libtranslate_jni.so` which is documented to carry 4 KB ELF/RELRO alignment warnings on Android 15/16 16 KB page-size emulators (`PageSizeMismatchDialog`); builds and runs cleanly on standard 4 KB devices/emulators (`compileSdk = 36`). Verify APK with `llvm-readelf` during Phase 6 and keep `-keep class com.google.mlkit.** { *; }` in `rules.keep`. |
| **EXIF & Image Loading** (`D-02`, `D-03`) | `androidx.exifinterface:exifinterface:1.4.2`<br>`com.github.bumptech.glide:glide:5.0.10`<br>`com.github.bumptech.glide:okhttp3-integration:5.0.10`<br>`annotationProcessor("com.github.bumptech.glide:compiler:5.0.10")` | `exifinterface:1.4.2` [CITED: https://developer.android.com/jetpack/androidx/releases/exifinterface] handles JPEG/WebP orientation before downscaling (`<= 1600px`). Glide `5.0.10` (Maven Central, Jul 2026) supports Java `annotationProcessor` (`compiler:5.0.10`, no KSP/Kapt) [CITED: https://github.com/bumptech/glide/releases]. `okhttp3-integration` wires `OkHttpUrlLoader.Factory` via `@GlideModule` to authenticated `OkHttpClient` so `/api/media/{id}` thumbnails load with Bearer tokens. For pinch-to-zoom (`D-03`), write a self-contained pure-Java `ZoomableImageView` (~120 lines using `ScaleGestureDetector` + `GestureDetector` + `Matrix`) instead of adding unmaintained JitPack `PhotoView`. |
| **Core & Activity Compat** (`D-02`, `D-03`, `D-05`, `D-07`) | `androidx.core:core:1.17.0`<br>`androidx.activity:activity:1.10.1` | **Important `compileSdk = 36` Constraint:** Do **NOT** bump `androidx.core:core` to `1.18.0` or `1.19.1` because those require `compileSdk = 36.1` (`minCompileSdk` AAR check failure) and bump `lifecycle-runtime` to `2.10.0` [CITED: https://developer.android.com/jetpack/androidx/releases/core]. Pinning `core:1.17.0` and `activity:1.10.1` (which requires `compileSdk >= 35`) aligns cleanly with `compileSdk = 36` and `lifecycle = "2.8.7"`, providing `FileProvider`, `NotificationCompat.MessagingStyle`, `ActivityResultContracts.PickVisualMedia` (remember `.INSTANCE` syntax in Java!), and `OpenDocument`. |

### Manifest, `FileProvider` & Android 13–16 Permission Matrix

Add to `app/src/main/AndroidManifest.xml` [VERIFIED baseline: `feature/phase-5-core:app/src/main/AndroidManifest.xml#L1-L26`]:
- **Permissions:**
  - Existing: `INTERNET`, `ACCESS_NETWORK_STATE`.
  - Add:
    - `android.permission.POST_NOTIFICATIONS` (runtime requested **once** after login on API 33+, `D-07`).
    - `android.permission.RECORD_AUDIO` (runtime requested before voice recording or WebRTC call start/accept, `D-02`, `D-11`).
    - `android.permission.FOREGROUND_SERVICE` + `android.permission.FOREGROUND_SERVICE_MICROPHONE` (mandatory on API 34+ / `targetSdk = 36` for `CallService` with `android:foregroundServiceType="microphone"`).
    - `android.permission.MODIFY_AUDIO_SETTINGS` (audio routing).
    - `android.permission.BLUETOOTH_CONNECT` (API 31+ runtime-checked optionally if routing to Bluetooth SCO/headset; graceful fallback without crash if denied).
    - `android.permission.ACCESS_COARSE_LOCATION` + `android.permission.ACCESS_FINE_LOCATION` (runtime requested only when user chooses "Location", `D-02`).
    - `android.permission.CAMERA` with `<uses-feature android:name="android.hardware.camera" android:required="false" />` (runtime requested only if user chooses "Take photo"; NOT needed for `PickVisualMedia`).
    - **Omit** `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` — Android Photo Picker (`PickVisualMedia`) and SAF (`OpenDocument`) grant scoped read URI access without broad storage permissions!
- **`<queries>` block** (mandatory on `targetSdk = 36` for `PackageManager` / chooser availability checks):
  - `<intent><action android:name="android.intent.action.VIEW" /><data android:scheme="geo" /></intent>`
  - `<intent><action android:name="android.intent.action.VIEW" /><data android:mimeType="*/*" /></intent>`
- **`FileProvider` + `res/xml/file_paths.xml`:**
  ```xml
  <provider
      android:name="androidx.core.content.FileProvider"
      android:authorities="${applicationId}.fileprovider"
      android:exported="false"
      android:grantUriPermissions="true">
      <meta-data
          android:name="android.support.FILE_PROVIDER_PATHS"
          android:resource="@xml/file_paths" />
  </provider>
  ```
  `res/xml/file_paths.xml`:
  - `<files-path name="pending_media" path="pending_media/" />` (offline queued copies & voice notes)
  - `<cache-path name="media_cache" path="media_cache/" />` (authenticated downloads opened via `Intent.ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`)
  - `<cache-path name="camera" path="camera/" />` (temporary camera capture destination before EXIF compression)
- **`MainActivity`, `CallFragment`, and `CallService` declarations:**
  - `MainActivity`: set `android:launchMode="singleTop"` so tapping a `MessagingStyle` notification routes through `onNewIntent()` cleanly without recreating the activity stack.
  - `CallFragment` is declared in `nav_graph.xml` within `MainActivity`.
  - `CallService`: `android:exported="false"`, `android:foregroundServiceType="microphone"`.

---

## 2. Room Schema Verification (`AppDatabase` v2 vs v3)

### Inspection of Phase 5 Schema (`AppDatabase` v2 & `2.json`)
- `AppDatabase` currently sits at `version = 2, exportSchema = true` with `MIGRATION_1_2` [VERIFIED: `feature/phase-5-core:app/src/main/java/com/utechat/data/local/AppDatabase.java#L15-L50`].
- **`attachment_transfer` (`AttachmentTransferEntity.java`, `2.json#L381-L430`)** currently has only **5 columns**:
  - `clientId` (`TEXT NOT NULL PK`)
  - `localUri` (`TEXT NOT NULL`)
  - `state` (`TEXT NOT NULL`)
  - `progress` (`INTEGER NOT NULL`)
  - `mediaUrl` (`TEXT`, nullable)
- **`TransferDao.java`** currently has only `@Upsert insert(...)` and `@Query("SELECT * FROM attachment_transfer WHERE clientId = :id") LiveData<AttachmentTransferEntity> getByClientId(String id)` — **missing** synchronous lookup for `SendMessageWorker`, progress/state update queries, conversation-level observation, and delete cleanup [VERIFIED: `feature/phase-5-core:app/src/main/java/com/utechat/data/local/dao/TransferDao.java#L10-L15`].
- **`message_translation` (`MessageTranslationEntity.java`, `2.json#L466-L498`)** has composite PK `("messageKey", "targetLang")` + `translatedText` (`TEXT NOT NULL`).
  - `MessageEntity.clientId` is `TEXT NOT NULL` with a `UNIQUE` index `index_message_clientId` (`2.json#L350-L364`), and `SyncEventApplier.toMessageEntity` assigns `"srv-" + dto.id` when `dto.clientId` is absent [VERIFIED: `SyncEventApplier.java#L918`]. Thus `messageKey = MessageEntity.clientId` is guaranteed stable and unique across `SENDING -> SENT -> sync`!
  - `TranslationDao.java` currently has only `insert(...)` and `get(key, language)` [VERIFIED: `TranslationDao.java#L10-L16`].

### Decision: Bump `AppDatabase` `version = 2 -> 3` (`MIGRATION_2_3`, **One-Way Checkpoint**)

**Why Room v3 IS required for `D-04` and `D-03`:**
1. External Photo Picker / SAF `content://` URIs lose read permission upon process restart. `D-04` requires copying media into private storage (`filesDir/pending_media/`) and persisting upload state, error codes, and file metadata across restarts. Keeping only `localUri` blurs the original picked URI vs the private copied file path, and lacks typed `kind`, `mime`, `sizeBytes`, `fileName`, `durationMs`, `width`, `height`, `direction` (`UPLOAD`/`DOWNLOAD`), `errorCode`, and `updatedAt`.
2. Tracking download state/local cached path (`cacheDir/media_cache/`) in `attachment_transfer` (`direction = "DOWNLOAD"`, keyed by `"dl:" + attachmentId` or `clientId`) avoids re-downloading documents/videos/audio on replay or reopening (`D-03`).
3. **Whereas `message_translation` does NOT need column changes** — `(messageKey, targetLang, translatedText)` keyed on `MessageEntity.clientId` satisfies `D-14` completely! Expanding `TranslationDao` with `findSync(key, lang)`, `observeByConversation(conversationId)` (joining `message` on `m.clientId = mt.messageKey`), and `deleteOrphaned()` requires zero schema change on `message_translation`.

#### Exact Room `MIGRATION_2_3` Column Diff (`attachment_transfer`)

| New Column | Java Type / Nullability | SQLite `ALTER TABLE` in `MIGRATION_2_3` | Purpose (`D-03`, `D-04`) |
|---|---|---|---|
| `direction` | `@NonNull String` (`"UPLOAD"` / `"DOWNLOAD"`) | `ALTER TABLE attachment_transfer ADD COLUMN direction TEXT NOT NULL DEFAULT 'UPLOAD'` | Distinguishes queued uploads from downloaded attachment caches |
| `localPath` | `@Nullable String` | `ALTER TABLE attachment_transfer ADD COLUMN localPath TEXT` | Persistent absolute path in `filesDir/pending_media/` or `cacheDir/media_cache/` |
| `kind` | `@Nullable String` | `ALTER TABLE attachment_transfer ADD COLUMN kind TEXT` | `IMAGE`, `AUDIO`, `VIDEO`, `FILE` (sent as `kind` part in `POST /api/media`) |
| `mime` | `@Nullable String` | `ALTER TABLE attachment_transfer ADD COLUMN mime TEXT` | Normalized MIME (`image/jpeg`, `audio/mp4`, `video/mp4`, `application/pdf`, etc.) |
| `sizeBytes` | `@Nullable Long` | `ALTER TABLE attachment_transfer ADD COLUMN sizeBytes INTEGER` | Exact byte count of prepared file |
| `fileName` | `@Nullable String` | `ALTER TABLE attachment_transfer ADD COLUMN fileName TEXT` | Original/display file name (for `FILE` cards & `FileProvider` cache name) |
| `durationMs` | `@Nullable Integer` | `ALTER TABLE attachment_transfer ADD COLUMN durationMs INTEGER` | Voice/video duration in milliseconds |
| `width` | `@Nullable Integer` | `ALTER TABLE attachment_transfer ADD COLUMN width INTEGER` | Image/video width in pixels |
| `height` | `@Nullable Integer` | `ALTER TABLE attachment_transfer ADD COLUMN height INTEGER` | Image/video height in pixels |
| `errorCode` | `@Nullable String` | `ALTER TABLE attachment_transfer ADD COLUMN errorCode TEXT` | Transfer error (`FILE_TOO_LARGE`, `UNSUPPORTED_TYPE`, `NETWORK`, `FILE_MISSING`) |
| `updatedAt` | `long` | `ALTER TABLE attachment_transfer ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0` | Timestamp for cache eviction & stale transfer cleanup |

- **Schema & Test Gate:** Export `app/schemas/com.utechat.data.local.AppDatabase/3.json`, attach `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` in `AppDatabase.getInstance(...)` (no `fallbackToDestructiveMigration()`), and extend `AppDatabaseMigrationTest` to verify `1 -> 2 -> 3` and `2 -> 3` preserving existing messages, FTS4, and v2 rows.
- **Reversibility:** **One-way** once `3.json` and `MIGRATION_2_3` are committed in Wave 1.

---

## 3. PostgreSQL Schema Verification (`V1__initial_schema.sql` vs `V6__media_and_calls.sql`)

### Inspection of `V1__initial_schema.sql`

1. **`attachments` (`V1#L104-L116`, RLS enabled at `V1#L197`):**
   - Already has `id`, `uploader_id`, `kind IN ('IMAGE', 'AUDIO', 'VIDEO', 'FILE')`, `mime`, `size_bytes >= 0`, `storage_path`, `original_name`, `duration_ms >= 0`, `width > 0`, `height > 0`, `created_at` [VERIFIED: `V1__initial_schema.sql#L104-L116`].
   - `messages.attachment_id` already has `CONSTRAINT uq_messages_attachment UNIQUE (attachment_id)` (`V1#L145`) [VERIFIED].
   - **Performance gap:** `messages(attachment_id)` only creates an index via `uq_messages_attachment`, which suffices for `WHERE m.attachment_id = ?`, and adding an explicit partial index or verifying `uq_messages_attachment` serves the lookup directly is clean.
2. **`calls` (`V1#L117-L129`, RLS enabled at `V1#L198`):**
   - Currently has `id`, `conversation_id`, `caller_id`, `callee_id`, `media = 'VOICE'`, `state IN ('RINGING', 'ACTIVE', 'ENDED', 'MISSED', 'REJECTED', 'BUSY', 'FAILED')`, `end_reason IN ('HANGUP', 'TIMEOUT', 'REJECT', 'BUSY', 'OFFLINE', 'ERROR')`, `started_at`, `answered_at`, `ended_at` [VERIFIED: `V1__initial_schema.sql#L117-L129`].
   - **Critical Gaps vs `D-10`:**
     1. **Missing device hash columns (`D-10` 2-device signaling lock):** No `caller_device_id_hash` or `callee_device_id_hash` columns exist to lock WebRTC signaling (`call.offer`, `call.answer`, `call.ice`) to the exact two participating devices!
     2. **Missing atomic busy uniqueness (`D-10` `CALL_BUSY`):** No constraint stops concurrent `POST /calls` threads from creating two simultaneous `RINGING`/`ACTIVE` calls for the same caller or callee.
     3. **Missing call timeout metadata (`D-10` 45s timeout):** No `expires_at` / `updated_at` columns or partial index on `(state, expires_at) WHERE state = 'RINGING'` for fast atomic timeout sweeping (`RINGING -> MISSED`).
3. **`messages` (`V1#L130-L146`):**
   - **Critical Gap vs `D-10`:** Has `CONSTRAINT uq_messages_attachment UNIQUE (attachment_id)`, **but NO unique constraint on `call_id`**! Without `CREATE UNIQUE INDEX uq_messages_call_id ON public.messages(call_id) WHERE call_id IS NOT NULL`, concurrent `end` / `reject` / `timeout` transitions could insert duplicate `CALL` messages!

### Exact `V6__media_and_calls.sql` Migration (**One-Way Checkpoint**)

```sql
-- Phase 6: Khóa thiết bị signaling WebRTC, kiểm soát cuộc gọi đồng thời (CALL_BUSY), timeout 45s và bất biến 1 tin CALL duy nhất.

-- 1) Bổ sung hash thiết bị gọi/nhận (khóa signaling 2 thiết bị theo D-10) và mốc hết hạn 45s / cập nhật cuối.
ALTER TABLE public.calls
    ADD COLUMN IF NOT EXISTS caller_device_id_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS callee_device_id_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '45 seconds'),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS caller_alive_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS callee_alive_until TIMESTAMPTZ;

-- Đồng bộ expires_at cho dữ liệu seed/cũ dựa theo started_at.
UPDATE public.calls
SET expires_at = started_at + interval '45 seconds';

-- Cho tác vụ quét kết thúc các cuộc gọi ACTIVE cũ chưa có thiết bị đã khóa.
UPDATE public.calls
SET caller_alive_until = now(), callee_alive_until = now()
WHERE state = 'ACTIVE' AND (caller_device_id_hash IS NULL OR callee_device_id_hash IS NULL);

-- Giữ tệp cũ và bảo đảm mỗi lần gửi lại cùng clientId dùng một bản ghi tệp.
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS upload_client_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uq_attachments_upload_client
    ON public.attachments (uploader_id, upload_client_id)
    WHERE upload_client_id IS NOT NULL;

-- 2) Chỉ mục duy nhất bán phần: mỗi người dùng tối đa 1 cuộc gọi RINGING/ACTIVE ở vai trò caller hoặc callee (lớp bảo vệ DB cho 409 CALL_BUSY).
CREATE UNIQUE INDEX IF NOT EXISTS uq_calls_active_caller
    ON public.calls (caller_id)
    WHERE state IN ('RINGING', 'ACTIVE');

CREATE UNIQUE INDEX IF NOT EXISTS uq_calls_active_callee
    ON public.calls (callee_id)
    WHERE state IN ('RINGING', 'ACTIVE');

-- Tăng tốc tác vụ quét cuộc gọi RINGING quá hạn 45 giây -> MISSED.
CREATE INDEX IF NOT EXISTS idx_calls_ringing_expires
    ON public.calls (expires_at)
    WHERE state = 'RINGING';

-- 3) Bắt buộc tối đa 1 tin nhắn CALL cho mỗi bản ghi calls (chống trùng khi end/reject gửi lặp - D-10).
CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_call_id
    ON public.messages (call_id)
    WHERE call_id IS NOT NULL;
```

- **Important Concurrency Note for `POST /calls` (`CALL_BUSY`):** Partial unique indexes `(caller_id)` and `(callee_id)` protect same-role races, while cross-role races (e.g., User B is `ACTIVE` as a caller in Call 1, and User C calls User B) are serialized in Java inside `@Transactional CallService.startCall(...)` by locking both user rows in deterministic UUID order (`SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR UPDATE`) **after** `ContactPolicyService.lockPair(callerId, calleeId)` (actually lock sorted user IDs + `lockPair` in consistent order — note `lockPair` uses advisory lock which cannot deadlock with row locks if `lockPair` is always acquired first or user rows are locked in UUID order) and checking `EXISTS(SELECT 1 FROM public.calls WHERE state IN ('RINGING', 'ACTIVE') AND (caller_id IN (?, ?) OR callee_id IN (?, ?)))`!
- **RLS Check:** All modified tables (`calls`, `messages`, `attachments`) already have `ENABLE ROW LEVEL SECURITY` in `V1__initial_schema.sql#L197-L199` [VERIFIED]. No new public table is created in `V6`.

---

## 4. Docs Contract Gaps (`D-10` One-Way Checkpoint)

Before backend/Android implementation starts in Phase 6, `docs/API_CONTRACT.md` and `docs/FLOWS.md` MUST be synchronized (`D-10`, **one-way**):

### Required Updates to `docs/API_CONTRACT.md`
1. **Section 2.4 (`POST /conversations/{id}/messages`):**
   - Expand Phase 5 `TEXT`-only rule to V1 full client types: `TEXT`, `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION` (keep rejecting `SYSTEM` and `CALL` from client with `400 VALIDATION`).
   - Document `attachmentId` extraction from `mediaUrl` (`/api/media/{uuid}`), strict ownership check (`attachments.uploader_id == actorId`), single-use binding (`uq_messages_attachment` — returning `409 CONFLICT` / `400 VALIDATION` if already attached to a different `clientId`), and `kind == type` validation (`400 VALIDATION`).
   - Require the message `clientId` on multipart upload. Replay returns the saved attachment and concurrent upload attempts with the same key converge on a single file.
   - Document `LOCATION` validation (`latitude` in `[-90, 90]`, `longitude` in `[-180, 180]`, finite, non-null; `mediaUrl == null`).
   - Clarify `expectedVisibleFromMessageId` applies to media & location messages in `GROUP` chats (`409 MEMBERSHIP_CHANGED`).
2. **Section 2.5 (`POST /media`, `GET /media/{id}`):**
   - Document exact file limits (`IMAGE <= 10 MB`, `AUDIO <= 10 MB / <= 300,000 ms`, `VIDEO <= 50 MB`, `FILE <= 25 MB`), MIME allowlist + server magic-byte verification (`413 FILE_TOO_LARGE`, `415 UNSUPPORTED_TYPE`), and `20 uploads/min` rate limit (`429 RATE_LIMITED`).
   - Document `GET /media/{id}`: `Authorization: Bearer <token>` header only, membership check (`403 FORBIDDEN` if attached to a message whose conversation actor is not an active member or `message.id <= visible_from_message_id`, or if unattached and actor != `uploader_id`), HTTP `Range` (`206 Partial Content`, `Accept-Ranges: bytes`, `Content-Range`, `416` on invalid range), and `X-Content-Type-Options: nosniff`.
   - Note client retry invariant (`D-04`): reuse saved `mediaUrl` on retry instead of re-uploading.
3. **Section 2.6 (`POST /calls`, `/accept`, `/reject`, `/end`):**
   - Align `POST /calls` return: returns `201` `{ callId, state: "RINGING", expiresAt, iceServers[] }` when valid; returns `400 CALL_UNSUPPORTED` (group chat), `403 BLOCKED` (priority over `403 CONTACT_REQUIRED`), `403 CONTACT_REQUIRED` (not `ACCEPTED` friends), `429 RATE_LIMITED` (5 calls/min), or **`409 CALL_BUSY`** (without creating a call or ringing!). Remove misleading `MISSED`/`BUSY` from `POST /calls` success return column (`API_CONTRACT.md#L180`).
   - Document `POST /calls/{id}/accept`: atomic `RINGING -> ACTIVE` (caller cannot accept own call -> `403 FORBIDDEN`; expired/non-ringing -> `409 INVALID_STATE`; same callee device replay -> `200` idempotent), locks `callee_device_id_hash`, returns `{ callId, state: "ACTIVE", iceServers[] }`, triggers `call.accepted` (to caller) and `call.handled_elsewhere` (to callee's other devices).
   - Use the server deadline for ringing actions. Each locked device sends `call.keepalive` every 15s while ICE is connected. Either device missing its 60s deadline causes one `FAILED/OFFLINE` terminal event and one `CALL` message.
   - Document `POST /calls/{id}/reject`: atomic `RINGING -> REJECTED` (only callee; replay when already `REJECTED` -> `204` idempotent; if `ACTIVE`/`ENDED`/`MISSED` -> `409 INVALID_STATE`), emits `call.ended` + `call.handled_elsewhere`, inserts **1 idempotent `CALL` message**.
   - Document `POST /calls/{id}/end`: allowed for caller or callee (`403 FORBIDDEN` otherwise); atomic `RINGING/ACTIVE -> ENDED (HANGUP)`; replay on any terminal state (`ENDED`/`MISSED`/`REJECTED`/`BUSY`/`FAILED`) returns `204` idempotently without duplicate `CALL` messages.
   - Document 45-second timeout: `RINGING -> MISSED (TIMEOUT)`, emits `call.ended` (`MISSED`) to both users, inserts **1 idempotent `CALL` message**.
4. **Section 3 (WebSocket `/ws`):**
   - Specify `CALL_UPDATED` mapping to WS `type` (`call.incoming`, `call.accepted`, `call.ended`, `call.handled_elsewhere`) carrying `syncEventId` and payload `{ callId, conversationId, state, endReason, caller?, media?, handledByDeviceIdHash? }` (preserving `{ callId, state, endReason }` fields expected by Phase 5 `SyncEventApplier.applyCallUpdated` [VERIFIED: `SyncEventApplier.java#L1260-L1285`]).
   - Specify `call.offer` (`{ callId, sdp }`), `call.answer` (`{ callId, sdp }`), `call.ice` (`{ callId, candidate, sdpMid, sdpMLineIndex }`) **2-device signaling lock** (`D-10`): server requires `call.state == 'ACTIVE'`, verifies sender `(userId, deviceIdHash)` matches `caller_device_id_hash` or `callee_device_id_hash`, and forwards ONLY to the single opposite `(peerUserId, peerDeviceIdHash)`.

### Required Updates to `docs/FLOWS.md`
1. **F-18 (`docs/FLOWS.md#L321`):** Replace *"hiển thị bản đồ `SupportMapFragment`"* with lightweight coordinate card + external Google Maps `geo:` Intent (`D-02`, `06-CONTEXT.md#L108`), and document `mediaUrl` persistence + `413`/`415` queue handling (`D-04`).
2. **F-20 (`docs/FLOWS.md#L334-L358`):** Clarify direct EN↔VI menu (no Language ID, `D-13`), Wi-Fi auto vs cellular confirmation dialog (`D-15`), indeterminate progress `"Đang tải gói ngôn ngữ…"` without percentage, and *"Đã dịch"* / *"Ẩn bản dịch"* UI (`D-14`).
3. **F-21 & F-22 (`docs/FLOWS.md#L363-L405`):** Update sequence diagram & rules with `409 CALL_BUSY`, atomic accept/reject/45s timeout, `call.handled_elsewhere`, 2-device signaling lock after accept, and single idempotent `CALL` message per saved call (`D-10`).

---

## 5. Backend Disk Storage, HTTP `Range` Streaming & Coturn Architecture

### 5.1 Local Media Storage (`MediaController` / `MediaService`, `D-01`)
- **Configuration (`application.yml` + `.env.example`):**
  ```yaml
  spring:
    servlet:
      multipart:
        max-file-size: 55MB
        max-request-size: 60MB
  app:
    media:
      storage-dir: ${MEDIA_STORAGE_DIR:./data/media}
      max-image-bytes: 10485760
      max-audio-bytes: 10485760
      max-video-bytes: 52428800
      max-file-bytes: 26214400
  ```
  **Critical Spring Boot Pitfall:** Spring Boot defaults `spring.servlet.multipart.max-file-size` to **1 MB** and `max-request-size` to **10 MB**! Raising them to `55MB`/`60MB` ensures requests reach `MediaService` for per-`kind` exact limit checks (`413 FILE_TOO_LARGE`), AND adding `@ExceptionHandler(MaxUploadSizeExceededException.class)` in `GlobalExceptionHandler` guarantees uploads > 60 MB still return `{ "error": { "code": "FILE_TOO_LARGE", ... } }` with HTTP `413` instead of generic `500`!
- **Upload Endpoint (`POST /api/media`):**
  - Rate limit: `MediaUploadRateLimiter` (`20 uploads / 60s / userId`, sliding window matching `MessageRateLimiter` [VERIFIED: `MessageRateLimiter.java#L20-L61`], returning `429 RATE_LIMITED` + `Retry-After`).
  - Validate `kind` ∈ `{"IMAGE", "AUDIO", "VIDEO", "FILE"}` (`400 VALIDATION`).
  - Stream to temp file while counting exact bytes against kind max (`10MB` / `10MB` / `50MB` / `25MB`); abort early on overflow (`413 FILE_TOO_LARGE`).
  - **Magic-Byte MIME Detection (never trust client `Content-Type`):**
    - `IMAGE`: JPEG (`FF D8 FF`), PNG (`89 50 4E 47 0D 0A 1A 0A`), WebP (`RIFF....WEBP`) -> parse `width`/`height` via `ImageIO` for JPEG/PNG (or header parser for WebP). Reject SVG/HTML/executables (`415 UNSUPPORTED_TYPE`).
    - `AUDIO`: M4A/MP4 audio (`ftyp` box with `M4A `, `mp42`, `isom`) or ADTS AAC (`FF F1`/`FF F9`) -> `audio/mp4` / `audio/aac`. Validate optional `durationMs` hint (`0 <= durationMs <= 300_000`) or parse ISOBMFF `mvhd` duration box.
    - `VIDEO`: MP4/MOV (`ftyp` box) or WebM (`1A 45 DF A3`) -> `video/mp4` / `video/quicktime` / `video/webm`.
    - `FILE`: PDF (`%PDF-`), ZIP (`50 4B 03 04`, checking OOXML `.docx`/`.xlsx`/`.pptx` vs generic zip, rejecting `.apk` if `AndroidManifest.xml` inside), plain text UTF-8 (no NUL bytes, valid UTF-8 decoder). Reject `.exe`/`MZ`, ELF, shell scripts (`415 UNSUPPORTED_TYPE`).
  - Store atomically at `<MEDIA_STORAGE_DIR>/<yyyy>/<MM>/<attachmentUuid><safeExt>`, assert `target.normalize().startsWith(rootDir.normalize())`, insert `attachments` row, return `201 Created`:
    `{ mediaUrl: "/api/media/" + id, mime, sizeBytes, width, height, durationMs, fileName }`.
- **Download / HTTP `Range` Endpoint (`GET /api/media/{id}`):**
  - Parse `UUID attachmentId` (`404 NOT_FOUND` on malformed UUID).
  - **Strict RBAC Membership Query:**
    - Check `SELECT m.conversation_id, m.id AS message_id FROM public.messages m WHERE m.attachment_id = ?`.
    - If linked to a message: require `EXISTS(SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = ? AND cm.user_id = ? AND cm.left_at IS NULL AND ? > cm.visible_from_message_id)` -> if false, throw `403 FORBIDDEN` (prevents unauthorized users, removed members, AND rejoined group members from reading pre-join attachments!).
    - If unlinked (newly uploaded, not yet sent as message): allow ONLY `attachment.uploaderId.equals(actor.userId())` -> else `403 FORBIDDEN`.
  - Verify file exists on disk (`404 NOT_FOUND` if missing).
  - Parse `Range` header via `HttpRange.parseRanges(headers)`:
    - Reject multiple ranges or invalid bounds (`start >= totalLength` or `start > end`) with `416 Range Not Satisfiable` and `Content-Range: bytes */<totalLength>`.
    - Valid single range -> `206 Partial Content`, `Accept-Ranges: bytes`, `Content-Range: bytes <start>-<end>/<totalLength>`, `Content-Length: <rangeLen>`, `Content-Type: <mime>`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="<safeName>"` (`attachment` for `FILE`), streamed via `StreamingResponseBody` using `FileChannel.open(path, READ)` bounded to `[start, end]`.
    - No `Range` header -> `200 OK`, `Accept-Ranges: bytes`, `Content-Length: <totalLength>`, full stream.

### 5.2 Coturn REST HMAC-SHA1 Credentials & EC2 Config (`D-09`)
- **Backend Configuration (`application.yml` & `.env.example`):**
  ```yaml
  app:
    coturn:
      urls: ${COTURN_URLS:}
      shared-secret: ${COTURN_SHARED_SECRET:}
      ttl-seconds: ${COTURN_TTL_SECONDS:3600}
      fallback-stun-urls: ${COTURN_FALLBACK_STUN_URLS:stun:stun.l.google.com:19302}
  ```
- **Credential Generation (`TurnCredentialsService`):**
  - If `COTURN_URLS` and `COTURN_SHARED_SECRET` are non-blank:
    - `long expiry = clock.instant().getEpochSecond() + ttlSeconds;`
    - `String username = expiry + ":" + userId;`
    - `Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA1"));`
    - `String credential = Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(UTF_8)));`
    - Split comma-separated `COTURN_URLS`: emit STUN URLs without credentials and TURN/TURNS URLs with `{ urls, username, credential }`.
  - If `COTURN_URLS` or `COTURN_SHARED_SECRET` is blank (local dev/LAN): emit fallback STUN entry (`fallback-stun-urls`, default `stun:stun.l.google.com:19302`) without credentials (`D-09`).
- **EC2 `turnserver.conf` Template (`backend/deploy/coturn/turnserver.conf` + README):**
  - Runs on AWS EC2 secondary private/Elastic IP so Nginx stays on primary IP:443 (`DEPLOYMENT_SECURITY.md#L20`, `ADR-013`):
    - `listening-port=3478`, `tls-listening-port=443`
    - `listening-ip=<SECONDARY_PRIVATE_IP>`, `external-ip=<SECONDARY_PUBLIC_IP>/<SECONDARY_PRIVATE_IP>`
    - `use-auth-secret`, `static-auth-secret=<COTURN_SHARED_SECRET>`, `realm=<turn.utechat.example>`
    - `cert=.../fullchain.pem`, `pkey=.../privkey.pem`, `no-tlsv1`, `no-tlsv1_1`
    - `fingerprint`, `no-cli`, `no-loopback-peers`, `no-multicast-peers`
    - SSRF guard: `denied-peer-ip=10.0.0.0-10.255.255.255`, `denied-peer-ip=172.16.0.0-172.31.255.255`, `denied-peer-ip=192.168.0.0-192.168.255.255`, `denied-peer-ip=169.254.0.0-169.254.255.255`, `denied-peer-ip=127.0.0.0-127.255.255.255`.

---

## 6. Android Architecture Details: Media Queue, Notifications, WebRTC & ML Kit

### 6.1 Offline Media Pipeline (`SendMessageWorker` Extension, `D-04`)
1. **Media Preparation (`MediaPreparationHelper` on `CHAT_NETWORK` / background executor):**
   - **Image (`IMAGE`):** Read EXIF orientation via `ExifInterface`, decode with `inSampleSize`, rotate/scale so `max(width, height) <= 1600`, re-encode JPEG (`quality = 85`, stepping down to `70` if > 10MB) into `filesDir/pending_media/<clientId>.jpg`. Re-encoding strips GPS/camera EXIF metadata automatically!
   - **Voice (`AUDIO`):** `VoiceRecorderHelper` records `.m4a` (`MediaRecorder.OutputFormat.MPEG_4`, `AudioEncoder.AAC`, 64kbps, 44.1kHz, max duration 300,000ms, max file size 10MB) directly into `filesDir/pending_media/<clientId>.m4a` while checking `RECORD_AUDIO` permission.
   - **Video (`VIDEO`) & File (`FILE`):** Copy from `PickVisualMedia`/`OpenDocument` URI to `filesDir/pending_media/<clientId>.<ext>` while counting exact bytes against `50MB` / `25MB` limits before writing Room rows.
   - **Location (`LOCATION`):** Single-shot `FusedLocationProviderClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken)`. Build `MessageEntity` (`type = "LOCATION"`, `attachmentJson = {"latitude":..., "longitude":...}`, `status = "SENDING"`) and enqueue into `SendMessageWorker`.
2. **Atomic Room Insert (`RealChatRepository`):**
   - In one `db.runInTransaction(...)`: insert `MessageEntity` (`status = "SENDING"`, `queuedVisibleFromMessageId` for `GROUP` exactly like Phase 5 [VERIFIED: `RealChatRepository.java#L485-L515`]) **AND** insert `AttachmentTransferEntity` (`direction = "UPLOAD"`, `localPath`, `state = "QUEUED"`, `progress = 0`, `mediaUrl = null`).
   - Enqueue `SendMessageWorker.enqueue(workManager, clientId, sessionKey)` (`send-messages:<sessionKey>`, `APPEND_OR_REPLACE`, `NetworkType.CONNECTED`).
3. **Two-Phase Worker Execution (`SendMessageWorker.executeSend`):**
   - Keep `SendMessageWorker` backward-compatible with Phase 5 `TEXT` messages and existing `SendMessageWorkerTest`!
   - For `IMAGE`/`AUDIO`/`VIDEO`/`FILE`:
     - Load `AttachmentTransferEntity transfer = db.transferDao().findByClientIdSync(clientId)`.
     - **If `transfer.getMediaUrl() == null`:**
       - Check `localPath` exists on disk; if missing -> mark `MessageEntity` + `AttachmentTransferEntity` `FAILED` (`FILE_MISSING`) and return `Result.success()`.
       - Upload via `MediaApi.uploadMedia("Bearer " + token, clientIdPart, kindPart, countingFilePart, durationPart).execute()` with throttled `TransferDao.updateProgress(clientId, pct)`.
       - On `200`/`201`: **immediately persist `mediaUrl`** + server metadata (`mime`, `sizeBytes`, `width`, `height`, `durationMs`) to `AttachmentTransferEntity` (`state = "UPLOADED", progress = 100`) and `MessageEntity.attachmentJson` under `session.runIfCurrent(...)`! Thus if connectivity drops before `POST /messages`, the retry skips `POST /media` completely (`D-04`)!
       - On `413 FILE_TOO_LARGE` / `415 UNSUPPORTED_TYPE` / `400`/`403`/`404`: mark both `MessageEntity` and `AttachmentTransferEntity` `FAILED` with parsed `errorCode` (`FILE_TOO_LARGE`, `UNSUPPORTED_TYPE`, etc.), delete pending file on `413`/`415`, and **return `Result.success()`** so later queued messages continue (`D-04`).
       - On `401`: cancel `queueName(enqueuedSessionKey)`, `clearIfCurrent(token, version)`, `SessionEvents.notifyExpired(...)`, return `Result.failure()`.
       - On `IOException`/`408`/`5xx`/`429`: respect `MAX_AUTO_ATTEMPTS = 3` and `Retry-After` exactly like Phase 5 [VERIFIED: `SendMessageWorker.java#L310-L353`].
     - **Once `mediaUrl != null`:** Call `ChatApi.sendMessage("Bearer " + token, convId, new SendMessageRequest(clientId, msg.getType(), msg.getText(), mediaUrl, null, null, msg.getQueuedVisibleFromMessageId())).execute()`.
     - On `200`/`201`: mark `MessageEntity` `SENT`, mark `AttachmentTransferEntity` `COMPLETED`, and **delete `localPath` temp file** (`D-04`).
   - **Cleanup on Failure Delete & Logout:**
     - `deleteFailedMessage(clientId)`: deletes `filesDir/pending_media/` file + `transferDao.deleteByClientId(clientId)` + `messageDao.deleteFailedLocal(clientId)`.
     - `Injection.cleanupDepartingSession(...)`: cancels `SendMessageWorker.queueName(departingSessionKey)`, cancels `SyncWorker.queueName(departingSessionKey)`, disconnects WS, calls `CallSessionCoordinator.endOrResetForSessionChange()`, calls `NotificationHelper.cancelAll(appContext)`, and wipes `filesDir/pending_media/`, `cacheDir/media_cache/`, and `cacheDir/camera/` on `DISK` executor!
4. **In-App Media Viewing & Playback (`D-03`):**
   - **Image:** `ImagePreviewDialogFragment` / `ImageViewerFragment` inside `nav_chat.xml` using `Glide` (`OkHttpUrlLoader` with Bearer token for remote `/api/media/{id}`, or `localPath` when `SENDING`) inside `ZoomableImageView`.
   - **Voice `.m4a`:** Single shared `VoiceBubblePlayer` (`MediaPlayer` or lightweight `ExoPlayer` instance managed by `ChatRoomFragment` / lifecycle, releasing on `onStop`) playing from `localPath` (if `SENDING`/cached) or authenticated `media_cache` download (`MediaDownloadManager` with Bearer token) — never multiple audio bubbles playing simultaneously!
   - **Video:** `VideoPlayerFragment` / `VideoPlayerDialog` using `Media3 ExoPlayer` + `PlayerView` + `OkHttpDataSource.Factory(ApiClient.getMediaOkHttpClient())` supporting HTTP `Range` streaming directly with Bearer token, plus toolbar/overlay button *"Mở bằng app ngoài"* that ensures file is downloaded to `cacheDir/media_cache/` and opens `FileProvider.getUriForFile(..., authority, file)` with `Intent.ACTION_VIEW` + `Intent.FLAG_GRANT_READ_URI_PERMISSION`.
   - **Document File:** Tapping downloads via `MediaDownloadManager` (using `ApiClient.getMediaOkHttpClient()`, tracking progress in `attachment_transfer` `direction = "DOWNLOAD"`) into `cacheDir/media_cache/<attachmentId>_<safeName>` and opens via `FileProvider` + `FLAG_GRANT_READ_URI_PERMISSION`.

### 6.2 Notifications & 15-Minute `SyncWorker` (`D-05`..`D-08`)
- **2 Channels (`NotificationHelper`):**
  1. `messages` (`IMPORTANCE_HIGH`, label EN/VI, sound & vibration).
  2. `calls` (`IMPORTANCE_HIGH`, ringtone/call alert & missed call notifications).
- **Single-Flight `SyncWorker` (`D-08`):**
  - Enqueued on authenticated `MainActivity` entry: `PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)` with `NetworkType.CONNECTED`, unique work name `"periodic-sync:" + sessionKey`, `ExistingPeriodicWorkPolicy.KEEP`, tag `"session:" + sessionKey`.
  - `SyncWorker.doWork()` verifies `enqueuedSessionKey.equals(session.sessionKey())`, then calls `syncEngine.syncBlocking(enqueuedSessionKey)` (shares `SyncEngine`'s `AtomicBoolean running` single-flight mutex!).
- **Notification Filtering (`D-05`, `D-06`):**
  - **Bootstrap suppression:** Initial `bootstrap` snapshot NEVER fires message notifications (`SyncEngine.runBootstrap` does not trigger notifications; only incremental `applyBatch` events from `/sync` do!).
  - **Per-message filter:** Notify ONLY if:
    1. Event is `MESSAGE_CREATED` (new message not previously in Room, `serverId > lastReadId`),
    2. `!myUserId.equals(dto.senderId)` (not self-sent),
    3. `!conversation.getMuted()`,
    4. `!conversation.getId().equals(activeRoomTracker.getActiveConversationId())` (where `ActiveRoomTracker` is set in `ChatRoomFragment.onResume()` and cleared in `onPause()`),
    5. `sessionKey.equals(session.sessionKey())` still holds!
  - **Missed Call notification (`CALL_UPDATED`):** When `/sync` receives a terminal `MISSED` call for which `myUserId` was callee and no active `CallFragment` handled it, post to channel `calls`.
  - **Grouping & Navigation (`PendingIntent.FLAG_IMMUTABLE`):** Build `NotificationCompat.MessagingStyle(mePerson)` using sender `Person` + recent unread lines for `conversationId`, notification ID `conversationId.hashCode()`, summary group `"utechat_messages"`, tapping opens `MainActivity` (`extra_open_conversation_id = conversationId`) -> `ChatNavigation.openConversation(...)`.
- **`POST_NOTIFICATIONS` Prompt (`D-07`):** Check `SharedPreferences` flag `notif_prompted_once` in `MainActivity` when entering top-level tabs (`chatListFragment`/`contactsListFragment`/`profileFragment`) on API 33+; if `!prompted && checkSelfPermission != GRANTED`, launch `ActivityResultContracts.RequestPermission` once and set `notif_prompted_once = true`.

### 6.3 WebRTC `WebRtcCallEngine`, `CallService` & `CallSessionCoordinator` (`D-09`..`D-12`)
- **Interface Seam (`WebRtcCallEngine`):** Allows `CallSessionCoordinator` (pure Java state machine) to be 100% JVM-tested with Mockito/fake engine for:
  - Outgoing `IDLE -> OUTGOING_RINGING -> ACTIVE -> ENDED`, `409 CALL_BUSY`, `403 BLOCKED`/`CONTACT_REQUIRED`, `400 CALL_UNSUPPORTED`, `45s` timeout `MISSED`.
  - Incoming `IDLE -> INCOMING_RINGING -> ACTIVE -> ENDED`, `call.handled_elsewhere` stopping ringtone & closing screen, reject `REJECTED`.
  - 2-device signaling guard: ignoring `call.offer`/`call.answer`/`call.ice` when `callId` mismatches or session changes.
- **`StreamWebRtcCallEngine` (`io.getstream:stream-webrtc-android:1.3.10`) Pitfalls:**
  1. **Early ICE Candidate Buffering:** Remote ICE candidates can arrive before `setRemoteDescription` finishes; buffer `List<IceCandidate> pendingRemoteIce` and drain immediately inside `SdpObserver.onSetSuccess()` for `setRemoteDescription`.
  2. **Audio Routing on API 26–36:** Use `AudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION)` + `requestAudioFocus`; on API 31+ use `AudioManager.setCommunicationDevice(AudioDeviceInfo)` (`TYPE_BUILTIN_SPEAKER` vs `TYPE_BUILTIN_EARPIECE`/`TYPE_BLUETOOTH_SCO`) instead of deprecated `setSpeakerphoneOn()`, falling back to `setSpeakerphoneOn()` on API 26–30! Restore previous mode/clear communication device on `close()`.
  3. **Android 14+ microphone service:** For an incoming call, show the notification or the resumed `MainActivity` call screen. Start the microphone foreground service only when the visible call screen handles a tap on Call or Accept and confirms `RECORD_AUDIO`.
- **UI & Orientation Survival (`D-11`):**
  - `CallFragment` hosts `CallViewModel` (`SavedStateHandle`) + observes `CallSessionCoordinator.getUiState()` (singleton/service-bound live state) so rotating the phone never drops `PeerConnection` or resets the `mm:ss` timer (timer computed from `answeredAtElapsedRealtime`).
  - Controls: Mute/Unmute (`>= 48dp`), Speaker/Earpiece (`>= 48dp`), End Call (`>= 48dp`), plus Accept & Reject (`>= 48dp`) when ringing, Material 3 DayNight colors, EN/VI strings, and optional `ACTION_DIAL` fallback button if WebRTC/backend call fails and peer phone exists (`ADR-013`).

### 6.4 ML Kit Translation Manager (`D-13`..`D-15`)
- **Components:** `MessageTranslator` (interface) -> `RealMlKitTranslator` + `FakeMessageTranslator`; `MessageTranslationManager` (coordinates Room `TranslationDao`, `ConnectivityManager`, cellular prompt signal, indeterminate downloading state, and retry).
- **Flow (`D-13`..`D-15`):**
  1. Long-press `TEXT` bubble in `MessageAdapter` -> popup/bottom sheet with *"Dịch sang Tiếng Việt"* (`en -> vi`) & *"Dịch sang Tiếng Anh"* (`vi -> en`). (Also keep `"Ẩn bản dịch"` when translation is currently displayed!).
  2. Check Room `TranslationDao.findSync(msg.getClientId(), targetLang)`:
     - Hit -> immediately show in `MessageAdapter` (`Map<String, ActiveTranslation>` passed from `ChatRoomViewModel`, preserved in `SavedStateHandle` across rotation!).
     - Miss -> check `isModelDownloaded(source)` & `isModelDownloaded(target)`:
       - Both downloaded -> translate offline, `TranslationDao.insert(...)`, display!
       - Needs download (~30MB):
         - No network (`!hasValidatedInternet`) -> show error snackbar with Retry, keep original bubble untouched (`D-15`).
         - Metered / Cellular (`!NET_CAPABILITY_NOT_METERED`) -> ask confirmation via `MaterialAlertDialogBuilder` (*"Tải gói ngôn ngữ (~30MB) qua dữ liệu di động?"*). Cancel keeps original untouched; Confirm proceeds with `DownloadConditions` (cellular allowed).
         - Unmetered / Wi-Fi -> start download immediately.
         - While downloading: show indeterminate `LinearProgressIndicator` / `CircularProgressIndicator` inside bubble or top status bar with exact text `"Đang tải gói ngôn ngữ…"` (EN: `"Downloading language pack…"`), **no percentage estimate (`D-15`)**!
         - Download fails -> hide progress, keep original message, show Retry action.
         - Download succeeds -> translate text, save to `message_translation`, display below a `1dp` divider with small label *"Đã dịch"* and *"Ẩn bản dịch"* button (`D-14`).

---

## 7. Security Domain & Threat Mitigations

| Risk / Attack Surface | Mandatory Control in Phase 6 | Verification Source |
|---|---|---|
| **IDOR / Path Traversal / Pre-join Attachment Leak on `/api/media`** | Validate `attachmentId` UUID; storage path strictly `<MEDIA_STORAGE_DIR>/yyyy/MM/<uuid>.ext` (ignore client filename in disk path); `GET /api/media/{id}` enforces active membership (`left_at IS NULL`) **AND `message.id > visible_from_message_id`** (`403` otherwise); `POST /messages` locks `attachments` row `FOR UPDATE`, asserts `uploader_id == actorId`, `kind == type`, and single-use `uq_messages_attachment`. | [VERIFIED: `DEPLOYMENT_SECURITY.md#L29`], [VERIFIED: `V1__initial_schema.sql#L145`], [VERIFIED: `MessageService.java#L67-L86`] |
| **Executable / XSS / Bomb Upload on `POST /media`** | Enforce per-kind byte limits while streaming (`10MB`/`10MB`/`50MB`/`25MB`); verify magic bytes (reject SVG/HTML/APK/EXE/ELF); return `X-Content-Type-Options: nosniff`; rate limit `20 uploads/min/userId`. | [VERIFIED: `API_CONTRACT.md#L171,L251`], [VERIFIED: `ADR-011`] |
| **Token Leak via Media Player, Glide, or External Apps** | `Media3` uses `OkHttpDataSource` + `Glide` uses `OkHttpUrlLoader` backed by `ApiClient` (`AuthInterceptor` attaches Bearer ONLY to `BACKEND_HOST`); external opening downloads to `cacheDir/media_cache/` with Bearer and shares `content://` via `FileProvider` (`FLAG_GRANT_READ_URI_PERMISSION`), never URL tickets. | [VERIFIED: `AuthInterceptor.java#L14-L25`], [VERIFIED: `DEPLOYMENT_SECURITY.md#L25,L29`] |
| **TURN Secret Leak & EC2 Metadata SSRF** | `COTURN_SHARED_SECRET` lives only in backend env vars; client receives only HMAC-SHA1 `(expiry:userId, credential)`; `turnserver.conf` blocks loopback/private/link-local `169.254.0.0/16` peers. | [VERIFIED: `DEPLOYMENT_SECURITY.md#L20,L28`], [VERIFIED: `D-09`] |
| **Call Hijacking / Signaling Spoof / Duplicate Billing/Messages** | `calls` enforces `ACCEPTED` friends & `!blocked` (`403 BLOCKED` > `403 CONTACT_REQUIRED`), atomic state transitions `FOR UPDATE`, `409 CALL_BUSY`, 2-device signaling lock (`caller_device_id_hash` / `callee_device_id_hash`), and partial unique index `uq_messages_call_id` guaranteeing 1 `CALL` message. | [VERIFIED: `D-10`], [VERIFIED: `V1__initial_schema.sql#L117-L145`] |
| **Cross-Account Data/Media/Notification Leak on Android** | Logout / `401` / `4401` / account switch cancels `send-messages:<sessionKey>` & `periodic-sync:<sessionKey>`, calls `NotificationManagerCompat.cancelAll()`, ends any active WebRTC call, clears Room tables, and deletes `pending_media/`, `media_cache/`, and `camera/` directories. | [VERIFIED: `Injection.java#L106-L129`], [VERIFIED: `D-06`], [VERIFIED: `D-04`] |

---

## 8. Key Risks, Pitfalls & Open Checks for the Planner

1. **Git Branch Integration Prerequisite:** Phase 5 code lives on `feature/phase-5-core` while `.planning` is on `planning`. Before Phase 6 coding begins, `feature/phase-5-core` must be merged/rebased cleanly into the Phase 6 branch so `AppDatabase` v2, `SendMessageWorker`, `SyncEngine`, `ChatWebSocketManager`, and `MessageService` are present.
2. **`AppDatabase` v3 & `V6__media_and_calls.sql` (`one-way`) + `docs/API_CONTRACT.md` & `docs/FLOWS.md` (`D-10` `one-way`):** Plan Wave 1 MUST lock docs contracts, `V6__media_and_calls.sql`, and Room `AppDatabase` v3 (`MIGRATION_2_3`, `3.json`) before parallel media/notification/call/translation implementation waves.
3. **`compileSdk = 36` + `androidx.core:core:1.17.0`:** Do NOT upgrade `androidx.core` to `1.18.0+` or `1.19.1` (they require `compileSdk = 36.1`). Keep `core:1.17.0` and `activity:1.10.1`.
4. **ML Kit `17.0.3` 16 KB ELF Warning:** Check `libtranslate_jni.so` alignment with `llvm-readelf` during Wave 1/4 verification and ensure `rules.keep` protects both `org.webrtc.**` and `com.google.mlkit.**` for R8 Release builds.
5. **Android 14+ Foreground Service Microphone Timing:** Only start `CallService` (`foregroundServiceType="microphone"`) AFTER `RECORD_AUDIO` permission is granted (never passively on background `call.incoming` before user taps Accept or grants permission).
6. **Spring Boot Multipart Limit:** Raise `spring.servlet.multipart.max-file-size` (`55MB`) / `max-request-size` (`60MB`) AND map `MaxUploadSizeExceededException` to `413 FILE_TOO_LARGE` in `GlobalExceptionHandler`.
7. **Windows Build/Test Environment:** Remember `05-RESEARCH.md#L452` note on Windows ACLs in `app/build`; all backend tests run with `EmbeddedPostgres` (no Docker), and Android unit tests keep `net.bytebuddy.experimental=true`.

---

*Research completed: 2026-11-21*
