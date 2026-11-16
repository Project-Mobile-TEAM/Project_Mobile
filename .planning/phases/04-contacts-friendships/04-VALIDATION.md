---
phase: "4"
slug: "contacts-friendships"
status: draft
nyquist_compliant: true
wave_0_complete: false
created: "2026-11-17"
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring MockMvc + EmbeddedPostgres (Backend) / JUnit 4 + Mockito + InstantTaskExecutorRule (Android JVM) |
| **Config file** | `backend/pom.xml`, `app/build.gradle.kts` |
| **Quick run command** | `cd backend; mvn test -Dtest=LookupRateLimiterTest,ContactsApiTest` |
| **Full suite command** | `cd backend; mvn test; cd ..; .\gradlew testDebugUnitTest --no-daemon` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run unit/integration test for the touched module (`mvn test -Dtest=...` or `.\gradlew testDebugUnitTest --tests ...`)
- **After every plan wave:** Run full module suite (`cd backend; mvn test` or `.\gradlew testDebugUnitTest --no-daemon`)
- **Before `/gsd-verify-work`:** Full Backend + Android JVM suites must pass 100%, plus real Android ↔ Backend ↔ PostgreSQL integration checklist
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | REQ-contacts-management | T-04-01, T-04-04 | `V4__contacts_friend_flag.sql`, sliding-window rate limiter (`Clock` injectable), pair lock `pg_advisory_xact_lock`, cập nhật `API_CONTRACT.md`/`ERD.md`/`FLOWS.md` | unit | `powershell -Command "cd backend; mvn test -Dtest=LookupRateLimiterTest"` | ❌ W0 | ⬜ pending |
| 04-01-02 | 01 | 1 | REQ-contacts-management | T-04-02..05 | Authz, đồng thời `A<->B`, `send<->block`, `accept<->block` không deadlock, idempotent accept (cả sau unfriend), hai bên chặn nhau rồi bỏ chặn vẫn giữ bạn (`is_friend`), sync_events | integration (`EmbeddedPostgres`) | `powershell -Command "cd backend; mvn test -Dtest=ContactsApiTest"` | ❌ W0 | ⬜ pending |
| 04-02-01 | 02 | 2 | REQ-contacts-management | T-04-06, T-04-07 | Room DAO `blockUserAtomically`, `upsertPreservingPrivacy`, `SessionManager.getUserId()`, `ContactDao` reconciliation | unit | `powershell -Command ".\gradlew testDebugUnitTest --tests 'com.utechat.ui.contacts.ContactsValidatorTest' --no-daemon"` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | REQ-contacts-management | T-04-07 | Epoch chống refresh trễ/ngược thứ tự, accept cũ không tạo bạn ảo, chặn người lạ lưu profile offline, giữ cache khi `IOException` | unit | `powershell -Command ".\gradlew testDebugUnitTest --tests 'com.utechat.data.repository.FakeRepositoriesTest' --tests 'com.utechat.data.repository.RealContactsRepositoryTest' --no-daemon"` | ✅/❌ W0 | ⬜ pending |
| 04-03-01 | 03 | 3 | REQ-contacts-management | T-04-08 | `ContactsViewModel` Incoming PENDING badge count, Received/Sent split, 5-state UI actions, `SavedStateHandle` persistence | unit | `powershell -Command ".\gradlew testDebugUnitTest --tests 'com.utechat.ui.contacts.ContactsViewModelTest' --no-daemon"` | ❌ W0 | ⬜ pending |
| 04-03-02 | 03 | 3 | REQ-contacts-management | T-04-09 | `nav_contacts.xml` 4 screens, hide "Message" in Phase 4, i18n EN/VI, full Android JVM test suite & Lint | unit/build | `powershell -Command ".\gradlew testDebugUnitTest lintDebug --no-daemon"` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/com/utechat/backend/contacts/LookupRateLimiterTest.java` — sliding-window lookup rate limit tests
- [ ] `backend/src/test/java/com/utechat/backend/contacts/ContactsApiTest.java` — EmbeddedPostgres end-to-end tests for concurrency, authz, block semantics, sync_events
- [ ] `app/src/test/java/com/utechat/ui/contacts/ContactsValidatorTest.java` — E.164 normalization & error code mapping
- [ ] `app/src/test/java/com/utechat/data/repository/RealContactsRepositoryTest.java` — REST/Room reconciliation, offline cache retention, cross-account callback guard
- [ ] `app/src/test/java/com/utechat/ui/contacts/ContactsViewModelTest.java` — badge count, Incoming/Outgoing split, 5 UI states, orientation retention

*Existing infrastructure (`EmbeddedPostgres`, JUnit 5, MockMvc, JUnit 4, Mockito, InstantTaskExecutorRule) covers all phase dependencies.*

---

## Integration & Real-Flow Verification Checklist

Để chứng minh luồng thật Android ↔ Spring Boot Backend ↔ PostgreSQL (chạy với `-PuseFakeData=false` trên nhánh thực thi tách trực tiếp từ `main` đã tích hợp Phase 3):

| Kịch bản kiểm chứng | Thao tác | Kết quả bắt buộc |
|---|---|---|
| **1. Quyền thao tác lời mời (`D-05`)** | A gửi lời mời cho B; A tự gọi accept (`403/404`), C gọi accept/cancel (`403/404`) | Server từ chối mọi thao tác sai vai trò; chỉ B mới `accept`/`reject`, chỉ A mới `cancel`. |
| **2. Rate limit tìm SĐT (`D-04`)** | A gọi `GET /api/users/lookup?phone=...` 11 lần trong 1 phút | Lần 1–10 bình thường; lần 11 trả `429 RATE_LIMITED` kèm `retryAfterSeconds`. |
| **3. Gửi ngược chiều, Send/Accept ↔ Block & Accept lặp (`D-06`, `D-07`)** | B gửi A; A gửi B -> nhận `409 REQUEST_ALREADY_PENDING` & UI hiện nút Chấp nhận. Bắn đồng thời `send`/`accept` và `block`. Accept 2 lần liên tiếp (kể cả trên lời mời cũ sau khi đã unfriend) -> `200`, không tạo trùng `contacts`/`sync_events` và client không tạo bạn bè ảo. | Khóa cặp `pg_advisory_xact_lock` ngăn deadlock & `PENDING` lẫn với block; Room không tạo bạn ảo từ lịch sử `ACCEPTED`. |
| **4. Chặn 1 chiều DB / Hai bên chặn nhau / Unblock sau Unfriend (`D-01`)** | A chặn bạn B; sau đó B cũng chặn A -> A bỏ chặn B -> B bỏ chặn A (vẫn là bạn bè). Trường hợp khác: A chặn B rồi B unfriend A -> A bỏ chặn B (hoặc A bỏ chặn người lạ B rồi mất mạng ngay). | Cả hai bạn chặn nhau rồi bỏ chặn vẫn giữ quan hệ bạn bè (`is_friend`); bỏ chặn sau unfriend về `STRANGER`; người lạ vừa chặn có đủ tên hiển thị khi mất mạng. |
| **5. Xem offline & Thử lại khi ghi (`D-09`)** | Tắt Wifi/4G trên Emulator, mở tab Danh bạ & bấm Kết bạn/Chặn | Vẫn xem được danh sách bạn bè/lời mời đã cache; thao tác ghi báo lỗi mạng và giữ nguyên nút thử lại, không văng đăng xuất. |
| **6. Cô lập Room khi đổi tài khoản (`D-10`)** | Tài khoản A mở danh bạ → Đăng xuất → Đăng nhập tài khoản B → Tắt mạng mở tab Danh bạ | Không còn danh bạ hay lời mời nào của tài khoản A hiển thị ở tài khoản B (`clearAllTables()` sạch hoàn toàn). |
| **7. Ranh giới `sync_events` (`D-08`)** | Thực hiện gửi, accept, reject, cancel, block, unblock, unfriend | Bảng `sync_events` có đủ các event `CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED` trong cùng transaction, sẵn sàng cho Phase 5. |

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|---|---|---|---|
| 5-State UI Transitions & Badge Sync | REQ-contacts-management | Visual UI verification on Emulator (`SearchUserFragment`, `FriendRequestsFragment`, `ContactsListFragment`) | Chạy app ở `-PuseFakeData=false`, thử đủ 5 trạng thái (`STRANGER`, `OUTGOING_PENDING`, `INCOMING_PENDING`, `FRIEND`, `BLOCKED`), xoay ngang màn hình khi tìm SĐT, kiểm badge chỉ đếm lời mời nhận đến `PENDING` và nút "Nhắn tin" đang ẩn. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify criteria
- [x] Wave 0 covers all new test files
- [x] Real-flow permission, concurrency, offline, and cross-account isolation scenarios included
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending 2026-11-17
