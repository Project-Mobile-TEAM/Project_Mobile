# Phase 2: Foundation & Anti-Conflict Architecture - Context

**Gathered:** 2026-11-11
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 2 delivers the **Walking Skeleton** and **Anti-Conflict Architecture** for UTEChat:
1. **Backend Foundation:** A lightweight Spring Boot 3 backend application inside `backend/` built with Maven (`pom.xml`), connected to Supabase PostgreSQL (via Transaction Pooler port 6543 with HikariCP, `prepareThreshold=0`), exposing `GET /api/health` with a real database ping.
2. **Android Foundation:** Pure Single Activity (`MainActivity`) with ViewBinding, MVVM (LiveData), Navigation Component with 3-tab BottomNavigationView, and OkHttp/Retrofit networking configured for emulator and LAN testing.
3. **Anti-Conflict Locks (6 Hotspots):**
   - Hotspot 1: Dependencies locked in `app/build.gradle.kts`.
   - Hotspot 2: Manifest & network security locked in `AndroidManifest.xml` and `res/xml/network_security_config.xml`.
   - Hotspot 3: Navigation locked via `<include>` nested graphs (`nav_auth.xml`, `nav_chat.xml`, `nav_contacts.xml`, `nav_profile.xml`).
   - Hotspot 4: String resources partitioned (`strings_common.xml`, `strings_auth.xml`, `strings_chat.xml`, `strings_contacts.xml`, `strings_profile.xml`).
   - Hotspot 5: Room database locked with all 13 Entities matching `docs/ERD.md` and `AppDatabase` v1.
   - Hotspot 6: Seam Interfaces & Fake Repositories (`AuthRepository`, `ContactsRepository`, `ChatRepository`) switchable via `USE_FAKE_DATA`.
4. **End-to-End Verification:** `SplashFragment` probes `GET /api/health` through `HealthViewModel` -> `HealthRepository` -> Retrofit -> Spring Boot -> Supabase DB, displaying live connectivity badges.

</domain>

<decisions>
## Implementation Decisions

### Backend Architecture & Build Tool
- **D-01:** Spring Boot 3 backend inside `backend/` uses Apache Maven (`pom.xml`) with a dedicated `mvnw` wrapper, completely independent from the root Android Gradle build. — **Reversibility:** costly — build scripts and CI/CD pipelines depend on Maven.
- **D-02:** Backend targets Java 17 LTS, ensuring 100% parity with Android app `JavaVersion.VERSION_17` compileOptions and team local dev environments.
- **D-03:** Backend loads Supabase database credentials from root `.env` (or environment variables) via `dotenv-java` and configures HikariCP in `application-dev.yml` targeting the Supabase Transaction Pooler (`aws-1-ap-southeast-1.pooler.supabase.com:6543`, `sslmode=require`, `prepareThreshold=0`). — **Reversibility:** costly — affects connection pool behavior with PgBouncer.
- **D-04:** Backend runs on port 8080 with dev CORS allowing all origins (`*`) so Android Emulator (`10.0.2.2`), LAN devices, and local diagnostic tools connect seamlessly.

### Navigation & Bottom Navigation Layout
- **D-05:** Main screen BottomNavigationView features 3 primary tabs: Tin nhắn (Chats), Danh bạ (Contacts), and Cá nhân (Profile). Calls/WebRTC (Phase 6) will be initiated directly from conversation or contact screens.
- **D-06:** Root `nav_graph.xml` includes 4 modular child navigation graphs: `nav_auth.xml` (Phase 3), `nav_chat.xml` (Phase 5), `nav_contacts.xml` (Phase 4), and `nav_profile.xml` (Phase 3). Teammates only modify their respective child graph, eliminating XML merge conflicts. — **Reversibility:** costly — refactoring navigation IDs impacts all Fragment destinations.
- **D-07:** Resource strings are partitioned into `strings_common.xml`, `strings_auth.xml`, `strings_chat.xml`, `strings_contacts.xml`, and `strings_profile.xml`.
- **D-08:** Pure Single Activity architecture with `MainActivity` hosting `NavHostFragment`. BottomNavigationView is dynamically visible only on top-level tabs and automatically hidden on Auth and detail screens via `NavController.OnDestinationChangedListener`.

### Seam & Fake Repositories
- **D-09:** Dependency Injection utilizes pure Java Manual DI / Service Locator (`Injection.java` / `AppContainer.java`), directly following the University course slides without Kapt/KSP or Dagger/Hilt annotation processing complexity.
- **D-10:** Seam switching between Fake and Real Repositories is controlled by `BuildConfig.USE_FAKE_DATA` / config toggle in `Injection.java`, allowing teammates to develop and test offline instantly.
- **D-11:** Pre-build 3 Seam Interfaces and Fake Repositories: `AuthRepository` (`FakeAuthRepository`), `ContactsRepository` (`FakeContactsRepository`), and `ChatRepository` (`FakeChatRepository`), seeded with mock data representing the 5 demo accounts from Phase 1.
- **D-12:** Define all 13 Room Entities matching `docs/ERD.md` in Phase 2 alongside `AppDatabase` v1. This freezes the SQLite schema contract and prevents table name or migration conflicts in subsequent phases. — **Reversibility:** costly — altering Room entity definitions requires schema migrations.

### Walking Skeleton Health Probe
- **D-13:** Backend exposes `GET /api/health` returning `{ "status": "UP", "database": "CONNECTED", "timestamp": ... }` after executing a real `SELECT 1` ping query against Supabase PostgreSQL via JDBC.
- **D-14:** Android `SplashFragment` invokes `HealthViewModel` -> `HealthRepository` -> Retrofit `GET /api/health`, displaying connection status badges for Backend and Database with a "Thử lại" (Retry) action on network failure, then automatically transitions to Auth.
- **D-15:** Android `buildConfigField` sets `BACKEND_BASE_URL` to `http://10.0.2.2:8080/`, accompanied by `res/xml/network_security_config.xml` permitting cleartext HTTP traffic for localhost, `10.0.2.2`, and `192.168.0.0/16`.
- **D-16:** OkHttpClient is configured with `HttpLoggingInterceptor` at `Level.BODY` for dev debugging, with a 15-second connect/read timeout accommodating Supabase pooler TLS handshakes.

### Educational & Code Commenting Standard
- **D-17:** Tất cả mã nguồn (Android Java, XML layout/nav/strings, Backend Spring Boot, SQL, scripts) BẮT BUỘC phải có comment Tiếng Việt ngắn gọn, súc tích, giải thích rõ ràng mục đích và ý nghĩa logic của từng hàm, khối xử lý và các dòng code quan trọng. Tuyệt đối không viết code chay không chú thích. Mục đích trọng tâm: giúp sinh viên hiểu sâu kiến trúc, dễ dàng học và giải trình mạch lạc, cũng như sửa code nhanh chóng và chính xác khi giảng viên đặt câu hỏi vấn đáp trực tiếp lúc chấm đồ án. — **Reversibility:** reversible — formatting & documentation requirement across all phases.

### the agent's Discretion
- Exact layout styling and color palette for `SplashFragment` and stub fragments (following Material 3 guidelines).
- Spring Boot package layout inside `com.utechat.backend` (`config`, `controller`, `dto`, `health`).
- Entity converter helper types (e.g. `UUIDConverter`, `DateConverter`, `ListStringConverter` for Room).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing:**

### Architecture & Standards
- `docs/ARCHITECTURE_DECISIONS.md` — ADR-001 (Java/XML MVVM), ADR-002 (Spring Boot Backend), ADR-003 (Anti-Conflict Architecture & Seams), ADR-007 (Security & Secrets).
- `docs/COURSE_ALIGNMENT.md` — Alignment with course syllabus: MVVM, LiveData, Room, Retrofit, WorkManager, Service Locator.
- `docs/API_CONTRACT.md` — Core REST endpoints and standard JSON error envelopes.
- `docs/ERD.md` — Full schema specifications for the 13 core entities to be mapped into Room.
- `docs/SUPABASE_SETUP.md` — Database credentials, connection strings, and pooler ports (6543 / 5432).

### Existing Project Assets
- `app/build.gradle.kts` — Existing dependencies and compilation flags.
- `app/src/main/AndroidManifest.xml` — Android manifest baseline.
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — Supabase SQL schema defining columns, types, and constraints.
- `.env` / `.env.example` — Environment configuration for Supabase pooler connection.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `app/build.gradle.kts` already has Navigation, Room, Retrofit, OkHttp, and LiveData dependencies declared.
- `backend/scripts/test-db-connection.ps1` provides proven connection verification logic for Supabase Transaction Pooler (port 6543).

### Established Patterns
- Single Activity pattern (`MainActivity`).
- Environment variables sourced from `.env` in root.
- All code strictly in package `com.utechat` (Android) and `com.utechat.backend` (Backend).

### Integration Points
- Android Retrofit `ApiService` communicates with Spring Boot `HealthController` via `GET /api/health`.
- Spring Boot `DataSource` connects to Supabase PostgreSQL at `aws-1-ap-southeast-1.pooler.supabase.com:6543`.

</code_context>

<specifics>
## Specific Ideas

- The Splash screen serves as both a brand entrance ("UTEChat") and an automated verification gate, confirming the Walking Skeleton is alive and connected before handing off to the Auth flow.
- Having all 13 Room entities defined upfront ensures that when Team Member 2 (Auth), Member 3 (Contacts), Member 4 (Messaging), and Member 5 (Media/WebRTC) begin their phases, their local Room SQLite databases are already synchronized and won't suffer from missing foreign key references or conflicting table migrations.

</specifics>

<deferred>
## Deferred Ideas

- None — discussion stayed strictly within Phase 2 scope.

</deferred>

---

*Phase: 2-Foundation & Anti-Conflict Architecture*
*Context gathered: 2026-11-11*

## Quyết định cập nhật khi execute — 2026-11-11

Người dùng đã chọn rõ: **Theo 10 bảng Room trong ERD**, thay D-12 và phần 13 entity trong Plan 02-02/RESEARCH/PATTERNS. Không sao chép password_hash, OTP, auth_sessions hay auth_throttles vào Android. Cache/hàng đợi gồm user_cache, contact_request, contact, conversation, member, message, attachment_transfer, message_fts, message_translation, sync_state.

Điều chỉnh tính đúng đắn/bảo mật khi triển khai: health là liveness, health/ready ping CSDL theo API_CONTRACT; không trả exception message. HTTP cleartext và Fake chỉ Debug; HTTP log BASIC thay BODY để tránh lộ dữ liệu xác thực. Không tạo lớp thật báo thành công giả khi module nghiệp vụ chưa triển khai.
