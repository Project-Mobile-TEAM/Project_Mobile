# Phase 4: Contacts & Friendships - Pattern Mapping

**Phase:** 04 — Contacts & Friendships (`REQ-contacts-management`)  
**Date:** 2026-11-17  
**Author:** GSD Pattern Mapper  
**Status:** COMPLETE  

> **Nền mã nguồn (`main` đã tích hợp Phase 3):** Hạ tầng xác thực Phase 3 (`AuthenticatedActor`, `GlobalExceptionHandler`, `SecurityConfig`, `SessionManager` với `getMeId()`, `AuthInterceptor`, `UserDao.findById()`, `Injection.DISK`, `clearAllTables()`, `EmbeddedPostgres`, `V3__account_deletion_grace.sql`) đã được merge vào `main` (`aaf3844`). Khi thực thi Phase 4, tách nhánh trực tiếp từ `main` để kế thừa toàn bộ các thành phần dưới đây.

---

## 1. Overview & Component Inventory

Bảng tổng hợp đầy đủ các tệp cần tạo mới (`Created`) hoặc chỉnh sửa (`Modified`) cho Phase 04 từ `04-CONTEXT.md` (`D-01`–`D-11`) và `04-RESEARCH.md`, phân loại theo vai trò, luồng dữ liệu, tệp mẫu đối chiếu (`Analog`) và quy chuẩn áp dụng.

### 1.1 Backend Spring Boot 3 Inventory (`backend/`)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (Analog) | Quy chuẩn áp dụng |
|---|---|---|---|---|
| `backend/.../config/TimeConfig.java` | Created | Spring `@Configuration` cấp `@Bean Clock` UTC | `config/CorsConfig.java` | Tách `Clock.systemUTC()` thành bean để `LookupRateLimiter` test xác định không cần `Thread.sleep` |
| `backend/.../exception/GlobalExceptionHandler.java` | Modified | Bắt `MissingServletRequestParameterException` → HTTP `400` | `feature/phase-3-auth:.../GlobalExceptionHandler.java` | Bổ sung handler thiếu query param để `GET /api/users/lookup` thiếu `?phone=` không rơi vào `500 INTERNAL` |
| `backend/.../dto/ContactDtos.java` | Created | Immutable Java `record` DTOs cho Lookup, Requests, Contacts | `feature/phase-3-auth:.../dto/AuthDtos.java` | Java `record` `camelCase`, `@JsonInclude(NON_NULL)`, `@JsonProperty("isContact")` tránh lệch tên Jackson `is*` |
| `backend/.../service/LookupRateLimiter.java` | Created | Sliding Window Log `10 req / 60s / userId` (`429 RATE_LIMITED`) | *No direct analog (greenfield in-memory)*; tham chiếu `ApiException` từ `ThrottleService.java` | `ConcurrentHashMap<UUID, Deque<Instant>>`, khóa từng `Deque`, dọn bucket rỗng, ném `429` kèm `retryAfterSeconds` |
| `backend/.../service/ContactPolicyService.java` | Created | Ranh giới chính sách dùng chung Phase 4/5/6 (`isBlockedEitherWay`, `areAcceptedFriends`) | *No analog (greenfield)*; SQL pattern theo `AccountService.java` | `@Service` + `JdbcTemplate`, luôn kiểm tra block hai chiều ở server (`D-01`) |
| `backend/.../service/SyncEventWriter.java` | Created | Helper ghi `sync_events` (`?::jsonb`) trong cùng transaction (`D-08`) | `AccountService.java#L92-L93` (inline `INSERT INTO sync_events`) | `@Component`, `JdbcTemplate` + Jackson `ObjectMapper`, ràng buộc theo `CHECK` của `V1__initial_schema.sql` |
| `backend/.../service/ContactLookupService.java` | Created | Nghiệp vụ `GET /api/users/lookup?phone=` (`D-04`) | `feature/phase-3-auth:.../AccountService.java`, `AuthService.java#L202-L207` | Validate E.164 -> rate limit -> kiểm `SELF` trước `discoverable`/`status` -> ẩn `BLOCKED` thành `404` -> suy diễn 5 trạng thái |
| `backend/.../service/ContactRequestService.java` | Created | Vòng đời lời mời: gửi, liệt kê, chấp nhận, từ chối, hủy (`D-05`, `D-06`, `D-07`) | `AccountService.java` (`@Transactional`), `AuthService.java` | `TransactionTemplate` bắt `DuplicateKeyException` bên ngoài transaction; `FOR UPDATE`; upsert `contacts` theo thứ tự UUID tăng dần; idempotency |
| `backend/.../service/ContactsService.java` | Created | Danh sách bạn bè/chặn, unfriend 2 chiều, block 1 chiều dữ liệu, unblock (`D-01`) | `AccountService.java` | `@Transactional`, `ON CONFLICT DO UPDATE`, `DELETE ... AND blocked = FALSE`, giữ lịch sử bạn bè đúng quy tắc `D-01` |
| `backend/.../controller/UserLookupController.java` | Created | REST Controller `GET /api/users/lookup` | `feature/phase-3-auth:.../AccountController.java` | `@RestController`, `@AuthenticationPrincipal AuthenticatedActor actor`, `@RequestParam(required = false)` |
| `backend/.../controller/ContactRequestController.java` | Created | REST Controller `/api/contact-requests/**` | `feature/phase-3-auth:.../AccountController.java` | Parse UUID path param (invalid -> `404`), trả `201`/`200`/`204`, lấy danh tính từ `AuthenticatedActor` |
| `backend/.../controller/ContactController.java` | Created | REST Controller `/api/contacts/**` | `feature/phase-3-auth:.../AccountController.java` | Parse UUID `userId` path param, trả `200`/`204`, không nhận `ownerId` từ client |
| `backend/src/test/.../contacts/LookupRateLimiterTest.java` | Created | Unit test JUnit 5 thuần với `Clock` giả lập | `backend/src/test/.../health/HealthControllerTest.java` | Kiểm ngưỡng 10 req/phút, `retryAfterSeconds`, dịch đồng hồ `+61s`, cô lập giữa các `userId` |
| `backend/src/test/.../contacts/ContactsApiTest.java` | Created | Integration test `@SpringBootTest` + `MockMvc` + `EmbeddedPostgres` | `feature/phase-3-auth:backend/src/test/.../auth/AuthApiTest.java` | Khởi tạo `V1__initial_schema.sql`, test đồng thời `ExecutorService + CountDownLatch`, quyền hạn, idempotency, `sync_events` |

---

### 1.2 Android Client Inventory (`gradle/` & `app/`)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (Analog) | Quy chuẩn áp dụng |
|---|---|---|---|---|
| `gradle/libs.versions.toml` | Modified | Version Catalog bổ sung `swiperefreshlayout = "1.1.0"` | `gradle/libs.versions.toml` | Quản lý phụ thuộc tập trung theo `ADR-025` |
| `app/build.gradle.kts` | Modified | Khai báo `implementation(libs.swiperefreshlayout)` | `app/build.gradle.kts` | Dùng alias `libs.swiperefreshlayout`, giữ Java 17 & `viewBinding = true` |
| `app/.../data/session/SessionManager.java` | Modified | Đọc `sub` (`userId`) từ JWT payload khi cold-start offline (`D-10`) | `feature/phase-3-auth:.../SessionManager.java` | Thêm `getUserId()` + `extractUserIdFromJwt(String)` dùng `java.util.Base64.getUrlDecoder()` + `Gson`, trả `null` an toàn khi lỗi |
| `app/.../data/local/dao/ContactRequestWithUser.java` | Created | POJO kết hợp `@Embedded ContactRequestEntity` + tên/avatar 2 phía từ `user_cache` | *No analog (greenfield Room POJO)* | Public fields cho Room mapping từ câu `LEFT JOIN user_cache` |
| `app/.../data/local/dao/UserDao.java` | Modified | Bảo toàn `allowMessagesFromNonContacts` & `phone` khi upsert cache | `data/local/dao/UserDao.java`, `MessageDao.java` | Giữ nguyên các method đã có gồm `findById(String)` (Phase 3); thêm `@Transaction default upsertPreservingPrivacy(...)` |
| `app/.../data/local/dao/ContactDao.java` | Modified | Query reactive + reconciliation Room trong 1 transaction (`AppDatabase` vẫn `version = 1`) | `data/local/dao/ContactDao.java`, `MessageDao.java`, `SyncStateDao.java` | Giữ nguyên 5 method cũ; thêm `getPendingRequestsWithUsers`, `getIncomingPendingCount(userId)`, `replaceFriends`, `replaceBlocked`, `replacePendingRequests` |
| `app/.../data/remote/model/ContactDtos.java` | Created | DTO Gson `camelCase` cho request/response + `ErrorBody` đọc `details` | `feature/phase-3-auth:.../data/remote/model/AuthDtos.java` | Class thuần Java, public fields cho Gson, tách khỏi Room `@Entity` |
| `app/.../data/remote/api/ContactApi.java` | Modified | Khai báo 11 Retrofit endpoints `/api/users/lookup`, `/api/contact-requests/**`, `/api/contacts/**` | `feature/phase-3-auth:.../data/remote/api/AuthApi.java` | Đường dẫn tương đối `"api/..."` (không có `/` đầu) |
| `app/.../data/repository/RelationshipState.java` | Created | Enum 5 trạng thái UI: `STRANGER`, `OUTGOING_PENDING`, `INCOMING_PENDING`, `FRIEND`, `BLOCKED` | `ui/splash/HealthViewModel.java` (`ProbeStatus`) | Khóa chặt ma trận 5 trạng thái (`D-02`) |
| `app/.../data/repository/ContactsResult.java` | Created | Wrapper kết quả kèm `retryAfterSeconds`, `existingRequestId`, `existingDirection` | `feature/phase-3-auth:.../data/repository/AuthResult.java` | `ok(T)`, `error(String)`, `error(String, Long, String, String)`, `isSuccess()` |
| `app/.../data/repository/UserLookupResult.java` | Created | Model kết quả tìm kiếm SĐT truyền lên UI (`RelationshipState` + `pendingRequestId`) | `feature/phase-3-auth:.../data/repository/UserProfile.java` | Class bất biến `public final` fields |
| `app/.../data/repository/CurrentUserIdProvider.java` | Created | SAM interface cung cấp `currentUserId` không phụ thuộc `AndroidKeyStore` | `Runnable clearCache` trong `RealAuthRepository.java` | Tách rời `SessionManager` để JVM unit test inject lambda trực tiếp |
| `app/.../data/repository/ContactsRepository.java` | Modified | Seam interface danh bạ | `data/repository/ContactsRepository.java` | Giữ nguyên 100% 3 chữ ký Phase 2; bổ sung đầy đủ phương thức Phase 4 |
| `app/.../data/repository/fake/FakeContactsRepository.java` | Modified | Mock repository in-memory đồng bộ `V2__seed_demo_data.sql` | `data/repository/fake/FakeContactsRepository.java`, `FakeAuthRepository.java` | Giữ nguyên fixture gốc cho `FakeRepositoriesTest`, hỗ trợ phát `LiveData` mới khi thao tác |
| `app/.../data/repository/real/RealContactsRepository.java` | Modified | Production repository: REST + Room reconciliation + guard chống race logout (`D-10`) | `feature/phase-3-auth:.../RealAuthRepository.java`, `RealContactsRepository.java` | Dùng chung `DISK` executor với `clearAllTables()`; kiểm `ownerAtStart.equals(currentUserIdProvider.getCurrentUserId())` trước khi ghi Room |
| `app/.../di/Injection.java` | Modified | Service Locator nối `RealContactsRepository` với `DISK` & `CurrentUserIdProvider` | `feature/phase-3-auth:.../di/Injection.java` | Truyền `db.contactDao()`, `db.userDao()`, `ContactApi`, `DISK`, và provider ưu tiên `AuthRepository.getCurrentUser()` fallback `SessionManager.getUserId()` |
| `app/.../ui/contacts/ContactsValidator.java` | Created | Chuẩn hóa SĐT VN & quốc tế E.164 + tái sử dụng format/initials | `feature/phase-3-auth:.../ui/auth/AuthValidator.java` | Hỗ trợ `0901111111` -> `+84901111111` và quốc tế `^\+[1-9][0-9]{7,14}$`; ủy quyền `AuthValidator.displayPhone` / `initials` |
| `app/.../ui/contacts/ContactsErrors.java` | Created | Ánh xạ mã lỗi API thành `@StringRes` i18n | `feature/phase-3-auth:.../ui/auth/AuthErrors.java` | Hỗ trợ `SELF`, `NOT_FOUND`, `BLOCKED`, `REQUEST_ALREADY_PENDING`, `ALREADY_FRIENDS`, `INVALID_STATE`, `RATE_LIMITED`, `PHONE_INVALID`, `NETWORK` |
| `app/.../ui/contacts/ContactsViewModel.java` | Created | MVVM State Holder dùng `CreationExtras` + `SavedStateHandle` | `feature/phase-3-auth:.../ui/auth/AuthViewModel.java`, `ProfileViewModel.java` | Lọc nội bộ, chia tab Incoming/Outgoing, đếm badge `incomingPendingCount`, giữ `lookupResult` qua xoay màn hình, `Event<T>` cho thông báo một lần |
| `app/.../ui/contacts/FriendsAdapter.java` | Created | `ListAdapter<UserEntity, Holder>` + `DiffUtil` cho danh sách bạn bè | `feature/phase-3-auth:.../ui/profile/DeviceAdapter.java` | Không dùng `notifyDataSetChanged()`; **ẩn nút Nhắn tin (`D-02`)**; chỉ mở thao tác `Hủy kết bạn` / `Chặn` |
| `app/.../ui/contacts/ContactRequestsAdapter.java` | Created | `ListAdapter<ContactRequestWithUser, Holder>` + `DiffUtil` (Incoming / Outgoing) | `feature/phase-3-auth:.../ui/profile/DeviceAdapter.java` | Incoming: `Chấp nhận`, `Từ chối`, `Chặn`; Outgoing: `Hủy lời mời` |
| `app/.../ui/contacts/BlockedUsersAdapter.java` | Created | `ListAdapter<UserEntity, Holder>` + `DiffUtil` cho danh sách chặn | `feature/phase-3-auth:.../ui/profile/DeviceAdapter.java` | Chỉ hiển thị nút `Bỏ chặn` |
| `app/.../ui/contacts/ContactsListFragment.java` | Modified | Màn chính tab Danh bạ: badge, 2 lối tắt, tìm nhanh, `SwipeRefreshLayout` | `ui/contacts/ContactsListFragment.java`, `ProfileFragment.java`, `DevicesFragment.java` | Toolbar `@string/title_contacts` + menu Tìm kiếm; ẩn nút Nhắn tin; giữ cache khi mất mạng + Snackbar thử lại (`D-09`) |
| `app/.../ui/contacts/FriendRequestsFragment.java` | Created | Màn con Lời mời kết bạn (Back, `TabLayout` Đã nhận/Đã gửi, `SwipeRefreshLayout`) | `feature/phase-3-auth:.../ui/profile/DevicesFragment.java` | Lưu tab đang chọn vào `SavedStateHandle`, empty state từng tab |
| `app/.../ui/contacts/SearchUserFragment.java` | Created | Màn con Tìm SĐT E.164 & thẻ kết quả 5 trạng thái (`D-02`, `D-04`) | `feature/phase-3-auth:.../RegisterFragment.java`, `DevicesFragment.java` | `MaterialToolbar` Back, nhập SĐT, hiển thị đúng 5 trạng thái, xử lý `409 REQUEST_ALREADY_PENDING` & `429 RATE_LIMITED` |
| `app/.../ui/contacts/BlockedUsersFragment.java` | Created | Màn con Danh sách chặn (`SwipeRefreshLayout`, `Bỏ chặn`) | `feature/phase-3-auth:.../ui/profile/DevicesFragment.java` | `MaterialToolbar` Back, `MaterialAlertDialogBuilder` xác nhận bỏ chặn, empty state |
| `app/src/main/res/navigation/nav_contacts.xml` | Modified | Graph con 4 màn hình Danh bạ (`D-03`) | `feature/phase-3-auth:.../res/navigation/nav_profile.xml` | `startDestination="@id/contactsListFragment"`, 3 destinations con + `<action>` |
| `app/src/main/res/menu/menu_contacts.xml` | Created | Icon Tìm kiếm trên Toolbar `ContactsListFragment` | `feature/phase-3-auth:.../res/menu/menu_save.xml` | `@drawable/ic_search` tint `@color/ute_on_bar`, `app:showAsAction="always"` |
| `app/src/main/res/drawable/bg_badge_count.xml` | Created | Nền tròn/viên thuốc đỏ cho badge số lời mời | `feature/phase-3-auth:.../res/drawable/bg_avatar.xml` | `<shape android:shape="rectangle">` + `corners 10dp` + `solid @color/ute_error` |
| `app/src/main/res/layout/fragment_contacts_list.xml` | Modified | Bố cục màn chính Danh bạ Material 3 DayNight | `fragment_devices.xml`, `fragment_profile.xml` | Toolbar xanh `ute_bar`, 2 hàng lối tắt (`@color/ute_card`), ô lọc, `SwipeRefreshLayout` + `RecyclerView` |
| `app/src/main/res/layout/fragment_friend_requests.xml` | Created | Bố cục Lời mời (`MaterialToolbar`, `TabLayout`, `SwipeRefreshLayout`, `RecyclerView`) | `fragment_devices.xml` | `TabLayout` 2 tab (`Đã nhận`, `Đã gửi`), `empty_state` |
| `app/src/main/res/layout/fragment_search_user.xml` | Created | Bố cục Tìm SĐT & thẻ kết quả 5 trạng thái | `fragment_register.xml`, `fragment_devices.xml` | `TextInputLayout` (`Widget.UTEChat.InputLayout`), thẻ `MaterialCardView` (`Widget.UTEChat.Card`) cho kết quả |
| `app/src/main/res/layout/fragment_blocked_users.xml` | Created | Bố cục Danh sách chặn | `fragment_devices.xml` | `MaterialToolbar`, `SwipeRefreshLayout`, `RecyclerView`, `empty_state` |
| `app/src/main/res/layout/item_contact_friend.xml` | Created | Item bạn bè (avatar chữ cái đầu, tên, SĐT, nút/menu Hủy kết bạn & Chặn) | `item_device.xml` | `minHeight="56dp"`, `bg_avatar`, **không có nút Nhắn tin** |
| `app/src/main/res/layout/item_contact_request.xml` | Created | Item lời mời (avatar, tên, nút Chấp nhận / Từ chối / Chặn hoặc Hủy) | `item_device.xml` | Chuyển cụm nút theo chiều Incoming/Outgoing |
| `app/src/main/res/layout/item_blocked_user.xml` | Created | Item người bị chặn (avatar, tên, nút Bỏ chặn) | `item_device.xml` | `Widget.Material3.Button.TonalButton` |
| `app/src/main/res/values/strings_contacts.xml` & `values-vi/strings_contacts.xml` | Modified | Toàn bộ chuỗi song ngữ EN/VI cho phân hệ Danh bạ | `values/strings_profile.xml`, `values-vi/strings_profile.xml` | Chỉ sửa `strings_contacts.xml` (`ADR-025`) |
| `app/src/test/.../data/repository/FakeRepositoriesTest.java` | Modified (Verified) | Giữ nguyên test Phase 2 + tùy chọn bổ sung test Fake Phase 4 | `FakeRepositoriesTest.java` | Bắt buộc giữ nguyên `contactsRespectDirectionAndPrivacyFixture` pass 100% |
| `app/src/test/.../ui/contacts/ContactsValidatorTest.java` | Created | JVM unit test chuẩn hóa E.164 & ánh xạ mã lỗi | `feature/phase-3-auth:.../AuthValidatorTest.java` | JUnit 4 thuần |
| `app/src/test/.../data/repository/RealContactsRepositoryTest.java` | Created | JVM unit test parse DTO, Room reconciliation, offline cache, account switch guard | `ui/splash/HealthViewModelTest.java` | JUnit 4 + Mockito + `InstantTaskExecutorRule` + `Runnable::run` |
| `app/src/test/.../ui/contacts/ContactsViewModelTest.java` | Created | JVM unit test badge count, lọc bạn, tab Đã nhận/Đã gửi, 5 trạng thái Lookup | `ui/splash/HealthViewModelTest.java` | JUnit 4 + Mockito + `InstantTaskExecutorRule` + `SavedStateHandle` |

---

## 2. Backend Spring Boot 3 Patterns (`com.utechat.backend`)

### 2.1 REST Controller Pattern
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:backend/src/main/java/com/utechat/backend/controller/AccountController.java`, `backend/.../controller/HealthController.java`.
- **Đặc trưng kế thừa:**
  1. `@RestController` + `@RequestMapping("/api...")` + Constructor Injection.
  2. Luôn nhận `@AuthenticationPrincipal AuthenticatedActor actor` từ `JwtAuthenticationFilter` (`SecurityConfig` đã khóa `.anyRequest().authenticated()`); **tuyệt đối không** nhận `userId` của người gọi từ query hay JSON body (`docs/DEPLOYMENT_SECURITY.md#3`).
  3. Chuyển tham số UUID trên `@PathVariable String ...` qua helper `parseUuid(String raw)` trả `404 NOT_FOUND` nếu sai định dạng UUID (đúng chuẩn `AccountController.revokeDevice`).
  4. Dùng `@RequestParam(value = "phone", required = false)` trên `UserLookupController` để tránh Spring ném `MissingServletRequestParameterException` rơi vào `500 INTERNAL`, đồng thời bổ sung handler `MissingServletRequestParameterException` trong `GlobalExceptionHandler`.

```java
// Mẫu đối chiếu từ AccountController.java -> áp dụng cho ContactRequestController.java:
package com.utechat.backend.controller;

import com.utechat.backend.dto.ContactDtos.*;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.ContactRequestService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Endpoint quản lý lời mời kết bạn (API_CONTRACT 2.3). Danh tính luôn lấy từ JWT. */
@RestController
@RequestMapping("/api/contact-requests")
public class ContactRequestController {
    private final ContactRequestService service;

    public ContactRequestController(ContactRequestService service) {
        this.service = service;
    }

    /** Gửi lời mời kết bạn tới addresseeId; trả 201 khi tạo mới hoặc 409 nếu đã có PENDING. */
    @PostMapping
    public ResponseEntity<ContactRequestDto> send(@AuthenticationPrincipal AuthenticatedActor actor,
            @RequestBody(required = false) SendContactRequestBody body) {
        // Kiểm tra body không rỗng và parse UUID trước khi gọi transaction
        ContactRequestDto created = service.sendRequest(actor.userId(), body == null ? null : body.addresseeId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Lấy danh sách lời mời theo status (mặc định PENDING) và type (incoming/outgoing/null). */
    @GetMapping
    public List<ContactRequestDto> list(@AuthenticationPrincipal AuthenticatedActor actor,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String type) {
        return service.listRequests(actor.userId(), status, type);
    }

    /** Chỉ addressee mới được chấp nhận lời mời (D-05). */
    @PostMapping("/{id}/accept")
    public ContactRequestDto accept(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id) {
        return service.acceptRequest(actor.userId(), parseUuid(id));
    }

    /** Chỉ addressee mới được từ chối lời mời (D-05). */
    @PostMapping("/{id}/reject")
    public ContactRequestDto reject(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id) {
        return service.rejectRequest(actor.userId(), parseUuid(id));
    }

    /** Chỉ requester mới được hủy lời mời đã gửi (D-05). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id) {
        service.cancelRequest(actor.userId(), parseUuid(id));
    }

    /** Sai định dạng UUID trả 404 NOT_FOUND giống AccountController.revokeDevice. */
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Contact request not found");
        }
    }
}
```

### 2.2 GlobalExceptionHandler & Immutable DTO Record Pattern
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:.../GlobalExceptionHandler.java`, `feature/phase-3-auth:.../dto/AuthDtos.java`.
- **Lưu ý bẫy Jackson với `boolean isContact`:** Với Java `record`, luôn gắn `@JsonProperty("isContact")` cho trường `boolean isContact` để Jackson không bao giờ rút gọn thành `"contact"` mà giữ đúng tên `"isContact"` trong `docs/API_CONTRACT.md#2.3`.

```java
// Bổ sung vào GlobalExceptionHandler.java (trên nhánh tích hợp Phase 3):
@ExceptionHandler(MissingServletRequestParameterException.class)
public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
    // Tránh Spring rơi vào handler Exception chung (500 INTERNAL) khi client gọi thiếu query param.
    return body(HttpStatus.BAD_REQUEST, "VALIDATION", "Missing required parameter: " + e.getParameterName(), Map.of());
}
```

```java
// DTO records cho Phase 4 (theo mẫu AuthDtos.java):
package com.utechat.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Các DTO của phân hệ Danh bạ & Kết bạn (API_CONTRACT 2.3): record bất biến, camelCase. */
public final class ContactDtos {
    private ContactDtos() { }

    /** Hồ sơ rút gọn trong lời mời kết bạn: tuyệt đối KHÔNG trả SĐT khi chưa phải bạn bè. */
    public record UserSummaryDto(String id, String displayName, String avatarUrl) { }

    /**
     * Phản hồi tìm kiếm theo SĐT E.164: giữ đủ trường cũ của API_CONTRACT (isContact, hasPendingRequest)
     * và mở rộng tương thích ngược cho 5 trạng thái UI + pendingRequestId.
     */
    public record UserLookupResponse(
            String id,
            String displayName,
            String avatarUrl,
            @JsonProperty("isContact") boolean isContact,
            boolean hasPendingRequest,
            String relationshipStatus,
            String pendingRequestId,
            String pendingRequestDirection
    ) { }

    public record SendContactRequestBody(String addresseeId) { }

    public record ContactRequestDto(
            String id,
            UserSummaryDto requester,
            UserSummaryDto addressee,
            String status,
            String createdAt,
            String respondedAt
    ) { }

    public record ContactDto(
            String id,
            String phone,
            String displayName,
            String avatarUrl,
            boolean blocked,
            String createdAt
    ) { }
}
```

### 2.3 `LookupRateLimiter` & `TimeConfig` Pattern (Greenfield In-Memory Sliding Window)
- **Tệp mẫu đối chiếu:** *No direct analog* (bảng `auth_throttles` trong `V1__initial_schema.sql#L51-L53` khóa `purpose` nên không dùng được cho Lookup; xem `ThrottleService.java` để giữ đồng nhất cấu trúc `ApiException(..., Map.of("retryAfterSeconds", retry))`).
- **Quy chuẩn chuẩn tắc:**
  1. Cung cấp `@Bean Clock clock() { return Clock.systemUTC(); }` trong `TimeConfig.java`.
  2. Trong `LookupRateLimiter`, nhận `Clock`, `maxRequests` (`@Value("${app.lookup.max-requests:10}")`), `windowSeconds` (`@Value("${app.lookup.window-seconds:60}")`).
  3. Dùng `ConcurrentHashMap<UUID, Deque<Instant>>`, đồng bộ trên từng `Deque` của `userId` (`compute`), loại bỏ timestamp cũ `<= now - window`, tính `retryAfterSeconds >= 1` khi vượt ngưỡng `10` và ném `ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ..., Map.of("retryAfterSeconds", retryAfterSeconds))`.

```java
// Trích xuất chuẩn tắc LookupRateLimiter.java:
@Component
public class LookupRateLimiter {
    private static final int CLEANUP_THRESHOLD = 10_000;
    private final ConcurrentHashMap<UUID, Deque<Instant>> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxRequests;
    private final Duration window;

    @Autowired
    public LookupRateLimiter(Clock clock,
            @Value("${app.lookup.max-requests:10}") int maxRequests,
            @Value("${app.lookup.window-seconds:60}") long windowSeconds) {
        this.clock = clock;
        this.maxRequests = maxRequests;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** Kiểm tra và ghi nhận 1 lượt tìm SĐT cho userId; vượt ngưỡng ném 429 kèm retryAfterSeconds. */
    public void checkAndRecord(UUID userId) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        // Dọn bucket hết hạn khi map phình to trên server đơn nút (ADR-019).
        if (buckets.size() > CLEANUP_THRESHOLD) {
            buckets.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    purgeExpired(e.getValue(), cutoff);
                    return e.getValue().isEmpty();
                }
            });
        }
        Deque<Instant> deque = buckets.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (deque) {
            purgeExpired(deque, cutoff);
            if (deque.size() >= maxRequests) {
                Instant oldest = deque.peekFirst();
                long millisLeft = oldest == null ? window.toMillis()
                        : Math.max(1L, Duration.between(now, oldest.plus(window)).toMillis());
                long retryAfterSeconds = Math.max(1L, (long) Math.ceil(millisLeft / 1000.0));
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many phone lookup requests", Map.of("retryAfterSeconds", retryAfterSeconds));
            }
            deque.addLast(now);
        }
    }

    private static void purgeExpired(Deque<Instant> deque, Instant cutoff) {
        // Loại các mốc <= cutoff (đã ngoài cửa sổ trượt 60 giây).
        while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
            deque.pollFirst();
        }
    }
}
```

### 2.4 Service Pattern: `JdbcTemplate`, `TransactionTemplate`, `FOR UPDATE` & `SyncEventWriter`
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:.../AccountService.java` (mẫu `JdbcTemplate`, `@Transactional`, `?::jsonb`), `V1__initial_schema.sql#L60-L76,L151-L177`.
- **Quy chuẩn bắt buộc cho 3 bẫy PostgreSQL lớn (`04-RESEARCH.md#3`):**
  1. **Bẫy PostgreSQL Aborted Transaction (`D-06`):** `sendRequest` bọc bước kiểm tra + `INSERT INTO contact_requests` trong `TransactionTemplate.execute(...)`. Bắt `DuplicateKeyException` (vi phạm `idx_contact_requests_pending_pair`) **BÊN NGOÀI** `TransactionTemplate` sau khi transaction cũ đã rollback sạch, rồi mới truy vấn lại bản ghi `PENDING` thắng cuộc để trả `409 REQUEST_ALREADY_PENDING` kèm `details` (`existingRequestId`, `direction`, `requesterId`, `addresseeId`, `status`, `createdAt`).
  2. **Khóa hàng & Thứ tự UUID chống Deadlock (`D-07`):** `acceptRequest`, `rejectRequest`, `cancelRequest` dùng `SELECT ... FROM contact_requests WHERE id = ? FOR UPDATE` trong `@Transactional`. Khi `acceptRequest` chèn 2 dòng bạn bè vào `contacts`, luôn sắp xếp `(minId, maxId)` trước `(maxId, minId)` dựa trên `UUID.compareTo` để 2 thread không bao giờ khóa chéo ngược chiều.
  3. **Bảo vệ dòng chặn khi Unfriend (`D-01`):** `DELETE FROM contacts WHERE ((owner_id = ? AND contact_user_id = ?) OR (owner_id = ? AND contact_user_id = ?)) AND blocked = FALSE RETURNING owner_id, contact_user_id` — không xóa dòng `blocked = TRUE` của bên đã chặn.

```java
// Trích xuất mẫu SyncEventWriter.java (theo AccountService.java + V1__initial_schema.sql):
@Component
public class SyncEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SyncEventWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Ghi 1 bản ghi sync_events (JSONB) trong cùng transaction của service gọi (D-08). */
    public void write(UUID userId, String eventType, String entityType, String entityId, Object payload) {
        try {
            String payloadJson = json.writeValueAsString(payload);
            jdbc.update("INSERT INTO public.sync_events (user_id, event_type, entity_type, entity_id, payload_json) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb)",
                    userId, eventType, entityType, entityId, payloadJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize sync event payload", e);
        }
    }
}
```

```java
// Trích xuất mẫu xử lý đua đồng thời với TransactionTemplate (ContactRequestService.sendRequest):
ContactRequestDto inserted;
try {
    inserted = tx.execute(status -> insertPendingRequest(actorId, addresseeId));
} catch (DuplicateKeyException conflict) {
    // Transaction cũ đã rollback sạch nên connection không còn trạng thái aborted; truy vấn lại dòng PENDING thắng cuộc.
    throw buildAlreadyPendingConflict(actorId, addresseeId);
}
```

### 2.5 Backend Test Patterns (`LookupRateLimiterTest` & `ContactsApiTest`)
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:backend/src/test/java/com/utechat/backend/auth/AuthApiTest.java` (cho `EmbeddedPostgres` + `ScriptUtils` + `MockMvc`) và `HealthControllerTest.java` (cho pure JUnit 5).
- **Đặc trưng kế thừa:**
  1. `ContactsApiTest` dùng `@SpringBootTest` + `@AutoConfigureMockMvc` + `EmbeddedPostgres.builder().start()` nạp trực tiếp `src/main/resources/db/migration/V1__initial_schema.sql` trong khối `static { ... }`.
  2. Dùng `@DynamicPropertySource` tiêm URL JDBC nhúng + `app.otp.cooldown-seconds=0` (để helper `register(phone, deviceId, password)` đăng ký nhanh nhiều tài khoản test độc lập qua `/api/auth/register/*`).
  3. Kiểm thử đồng thời thật (`concurrentMutualFriendRequestsProduceOnePendingAndOne409`, `doubleAcceptIsIdempotentWithoutDuplicateContactsOrSyncEvents`) dùng `ExecutorService` 2 luồng + `CountDownLatch` đồng bộ thời điểm bắn request song song, sau đó kiểm tra trực tiếp số dòng trong PostgreSQL qua `@Autowired JdbcTemplate`.

```java
// Trích xuất từ mẫu AuthApiTest.java sang ContactsApiTest.java:
@SpringBootTest
@AutoConfigureMockMvc
class ContactsApiTest {
    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
            try (Connection c = PG.getPostgresDatabase().getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource("src/main/resources/db/migration/V1__initial_schema.sql"));
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("app.otp.cooldown-seconds", () -> "0");
    }
    // ... @Autowired MockMvc mvc; ObjectMapper json; JdbcTemplate jdbc;
}
```

---

## 3. Android Client Data, Session & Network Patterns (`app/`)

### 3.1 Gradle Version Catalog Pattern
- **Tệp mẫu đối chiếu:** `gradle/libs.versions.toml`, `app/build.gradle.kts`.
- **Thay đổi:** Chỉ bổ sung `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` vào `libs.versions.toml` và `implementation(libs.swiperefreshlayout)` trong `app/build.gradle.kts` (`ADR-025`). Không sửa phiên bản nền tảng dùng chung.

### 3.2 `SessionManager.getUserId()` Pattern (Cold-Start Offline Support, `D-10`)
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:app/src/main/java/com/utechat/data/session/SessionManager.java`, `JwtService.java#L37-L41`.
- **Đặc trưng kế thừa:** `JwtService.issueAccessToken` gán `sub = userId.toString()`. Khi mở app offline từ `SplashFragment` -> `nav_chat`, `AuthRepository.getCurrentUser()` chưa có giá trị (`null`), nên `SessionManager` cần `getUserId()` giải mã phần payload JWT (phần giữa 2 dấu `.`) bằng `java.util.Base64.getUrlDecoder()` + `Gson`.
- **Lưu ý tương thích:** Dùng `java.util.Base64.getUrlDecoder()` (API 26+ trùng `minSdk = 26`, chạy được cả trên JVM thuần trong unit test) và tách helper static `extractUserIdFromJwt(@Nullable String jwt)` trả `null` nếu token hỏng (bảo đảm `SessionManagerTest` dùng `"header.payload.signature"` vẫn pass).

```java
// Bổ sung vào SessionManager.java (tích hợp từ Phase 3):
@Nullable public String getUserId() {
    return extractUserIdFromJwt(getToken());
}

/** Giải mã claim `sub` từ JWT để hiển thị cache khi cold-start offline; không dùng thay cho xác thực server. */
@Nullable public static String extractUserIdFromJwt(@Nullable String jwt) {
    if (jwt == null) return null;
    String[] parts = jwt.split("\\.");
    if (parts.length < 2) return null;
    try {
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
        String json = new String(decoded, StandardCharsets.UTF_8);
        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        return obj.has("sub") && !obj.get("sub").isJsonNull() ? obj.get("sub").getAsString() : null;
    } catch (Exception e) {
        return null;
    }
}
```

### 3.3 Room DAO & Reconciliation Pattern (Bảo toàn `AppDatabase` Version 1)
- **Tệp mẫu đối chiếu:** `app/src/main/java/com/utechat/data/local/dao/ContactDao.java`, `UserDao.java`, `MessageDao.java` (`@Transaction default`), `SyncStateDao.java`, `app/schemas/com.utechat.data.local.AppDatabase/1.json`.
- **Đặc trưng kế thừa & 2 Bẫy bắt buộc tránh:**
  1. **Không sửa `@Entity` hay `AppDatabase` (`version = 1`):** Mở rộng `UserDao` & `ContactDao` chỉ bằng `@Query` / `@Transaction default` và POJO `ContactRequestWithUser`.
  2. **Bảo toàn `allowMessagesFromNonContacts` & `phone` (`04-RESEARCH.md` Pitfall #6):** Khi `UserDao.upsertPreservingPrivacy` cập nhật user từ `/api/contacts` (không có `allowMessagesFromNonContacts`) hoặc từ `/api/contact-requests` (không có `phone` lẫn `allowMessagesFromNonContacts`), nếu `user_cache` đã có dòng `id` đó thì **giữ nguyên** `allowMessagesFromNonContacts` và giữ `phone` cũ nếu incoming `phone == null`. Nếu chưa có mới chèn với `allowMessagesFromNonContacts = true` mặc định.
  3. **Reconciliation theo phân vùng:** `replaceFriends` chỉ xóa `WHERE blocked = 0` trước khi chèn danh sách bạn bè mới (không đụng dòng `blocked = 1`), và `replaceBlocked` chỉ xóa `WHERE blocked = 1`. `replacePendingRequests` xóa `WHERE status = 'PENDING'` rồi chèn lại danh sách mới.

```java
// ContactRequestWithUser.java (POJO Room mới trong com.utechat.data.local.dao):
package com.utechat.data.local.dao;

import androidx.annotation.Nullable;
import androidx.room.Embedded;
import com.utechat.data.local.entity.ContactRequestEntity;

/** POJO ghép lời mời kết bạn với tên/avatar đã cache trong user_cache để hiển thị offline. */
public class ContactRequestWithUser {
    @Embedded public ContactRequestEntity request;
    @Nullable public String requesterName;
    @Nullable public String requesterAvatarUrl;
    @Nullable public String addresseeName;
    @Nullable public String addresseeAvatarUrl;
}
```

```java
// Mở rộng UserDao.java theo mẫu @Transaction default của MessageDao.java:
@Dao
public interface UserDao {
    @Upsert void insert(UserEntity user);
    @Query("SELECT * FROM user_cache WHERE id = :id") LiveData<UserEntity> getUserById(String id);
    // Đọc đồng bộ trên luồng đĩa: đã có sẵn từ Phase 3 trên main.
    @Query("SELECT * FROM user_cache WHERE id = :id") UserEntity findById(String id);
    @Query("SELECT * FROM user_cache WHERE phone = :phone") LiveData<UserEntity> getUserByPhone(String phone);

    /**
     * Cập nhật hồ sơ vào user_cache nhưng giữ nguyên cờ allowMessagesFromNonContacts (và phone cũ nếu null)
     * vì API danh bạ/lời mời không trả các trường riêng tư này.
     */
    @Transaction
    default void upsertPreservingPrivacy(List<UserEntity> incoming) {
        if (incoming == null) return;
        for (UserEntity user : incoming) {
            UserEntity existing = findById(user.getId());
            if (existing != null) {
                user.setAllowMessagesFromNonContacts(existing.getAllowMessagesFromNonContacts());
                if (user.getPhone() == null) user.setPhone(existing.getPhone());
            }
            insert(user);
        }
    }
}
```

```java
// Mở rộng ContactDao.java (giữ nguyên 5 phương thức cũ):
@Query("SELECT cr.*, req.displayName AS requesterName, req.avatarUrl AS requesterAvatarUrl, "
        + "addr.displayName AS addresseeName, addr.avatarUrl AS addresseeAvatarUrl "
        + "FROM contact_request cr "
        + "LEFT JOIN user_cache req ON req.id = cr.requesterId "
        + "LEFT JOIN user_cache addr ON addr.id = cr.addresseeId "
        + "WHERE cr.status = 'PENDING' ORDER BY cr.createdAt DESC")
LiveData<List<ContactRequestWithUser>> getPendingRequestsWithUsers();

@Query("SELECT COUNT(*) FROM contact_request WHERE addresseeId = :userId AND status = 'PENDING'")
LiveData<Integer> getIncomingPendingCount(String userId);

@Query("DELETE FROM contact WHERE blocked = 0") void deleteAllFriends();
@Query("DELETE FROM contact WHERE blocked = 1") void deleteAllBlocked();
@Query("DELETE FROM contact WHERE contactUserId = :userId") void deleteContactByUserId(String userId);
@Query("DELETE FROM contact_request WHERE status = 'PENDING'") void deletePendingRequests();
@Query("DELETE FROM contact_request WHERE id = :requestId") void deleteRequestById(String requestId);
@Query("SELECT * FROM contact WHERE contactUserId = :userId LIMIT 1") ContactEntity findContactSync(String userId);
@Upsert void insertContacts(List<ContactEntity> contacts);
@Upsert void insertRequests(List<ContactRequestEntity> requests);

@Transaction
default void replaceFriends(List<ContactEntity> friends, List<UserEntity> users, UserDao userDao) {
    userDao.upsertPreservingPrivacy(users);
    deleteAllFriends();
    if (friends != null && !friends.isEmpty()) insertContacts(friends);
}
```

### 3.4 Retrofit API, DTOs & Repository Seam Pattern (`ContactsResult<T>`)
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:.../AuthApi.java`, `AuthDtos.java`, `AuthResult.java`, `RealAuthRepository.java`, `ContactsRepository.java`, `FakeRepositoriesTest.java`.
- **Đặc trưng kế thừa:**
  1. `ContactApi`: đường dẫn tương đối `"api/..."` không có `/` đầu (`BACKEND_BASE_URL` kết thúc bằng `/`).
  2. `ContactsRepository`: **bắt buộc giữ nguyên 100%** 3 chữ ký Phase 2 (`getFriends(ownerId)`, `getBlockedUsers(ownerId)`, `searchByPhone(phoneE164)`) để `FakeRepositoriesTest` không gãy biên dịch.
  3. `ContactsResult<T>`: bổ sung `retryAfterSeconds`, `existingRequestId`, `existingDirection` từ `error.details` của backend (`429 RATE_LIMITED` và `409 REQUEST_ALREADY_PENDING`).
  4. `RealContactsRepository`: dùng chung single-thread `Executor diskExecutor` từ `Injection` với `db::clearAllTables`. Trước khi gọi mạng chụp `String ownerAtStart = currentUserIdProvider.getCurrentUserId()`; trước khi ghi Room trên `diskExecutor`, đối chiếu lại `ownerAtStart != null && ownerAtStart.equals(currentUserIdProvider.getCurrentUserId())` để ngăn rò dữ liệu tài khoản cũ nếu người dùng vừa đăng xuất/đổi tài khoản (`D-10`). Lỗi mạng `onFailure` trả `ContactsResult.error("NETWORK")` và **không** xóa cache Room (`D-09`).

```java
// ContactApi.java (11 endpoints theo docs/API_CONTRACT.md 2.3):
public interface ContactApi {
    @GET("api/users/lookup") Call<UserLookupResponse> lookupUser(@Query("phone") String phoneE164);
    @POST("api/contact-requests") Call<ContactRequestDto> sendContactRequest(@Body SendContactRequestBody body);
    @GET("api/contact-requests") Call<List<ContactRequestDto>> getContactRequests(
            @Query("status") String status, @Query("type") String type);
    @POST("api/contact-requests/{id}/accept") Call<ContactRequestDto> acceptContactRequest(@Path("id") String id);
    @POST("api/contact-requests/{id}/reject") Call<ContactRequestDto> rejectContactRequest(@Path("id") String id);
    @DELETE("api/contact-requests/{id}") Call<Void> cancelContactRequest(@Path("id") String id);
    @GET("api/contacts") Call<List<ContactDto>> getContacts();
    @GET("api/contacts/blocked") Call<List<ContactDto>> getBlockedContacts();
    @DELETE("api/contacts/{userId}") Call<Void> unfriend(@Path("userId") String userId);
    @POST("api/contacts/{userId}/block") Call<Void> blockUser(@Path("userId") String userId);
    @DELETE("api/contacts/{userId}/block") Call<Void> unblockUser(@Path("userId") String userId);
}
```

```java
// Mẫu kiểm tra cô lập tài khoản & parse ErrorBody trong RealContactsRepository.java:
String ownerAtStart = currentUserIdProvider.getCurrentUserId();
api.getContacts().enqueue(new Callback<List<ContactDto>>() {
    @Override public void onResponse(Call<List<ContactDto>> c, Response<List<ContactDto>> r) {
        if (!r.isSuccessful() || r.body() == null) {
            out.setValue(parseError(r));
            return;
        }
        List<ContactDto> body = r.body();
        diskExecutor.execute(() -> {
            // Chặn race condition D-10: bỏ qua kết quả nếu đã logout hoặc đổi tài khoản trong lúc chờ mạng.
            if (ownerAtStart == null || !ownerAtStart.equals(currentUserIdProvider.getCurrentUserId())) return;
            // Ánh xạ ContactDto -> ContactEntity + UserEntity rồi gọi contacts.replaceFriends(...)
            out.postValue(ContactsResult.ok(true));
        });
    }
    @Override public void onFailure(Call<List<ContactDto>> c, Throwable t) {
        // Mất mạng giữ nguyên dữ liệu đã cache trong Room (D-09).
        out.setValue(ContactsResult.error("NETWORK"));
    }
});
```

### 3.5 `Injection.java` Wiring Pattern
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:app/src/main/java/com/utechat/di/Injection.java`.
- **Đặc trưng kế thừa:** Dùng lại chính biến `DISK` executor (`Executors.newSingleThreadExecutor()`) và cung cấp `CurrentUserIdProvider`:
  - Trong mode Real: ưu tiên `authRepo.getCurrentUser().getValue()`, fallback `SessionManager.get().getUserId()` (khi cold-start offline `SplashFragment` -> `nav_chat`), và trả `null` ngay nếu `!SessionManager.get().hasToken()` (ngăn race lúc logout).
  - Trong mode Fake (`Debug && USE_FAKE_DATA`): trả `FakeContactsRepository` dùng provider từ `provideAuthRepository(context).getCurrentUser()` (fallback `DemoData.ALICE`).

```java
// Mở rộng Injection.java cho Contacts (tích hợp từ Phase 3):
public static synchronized CurrentUserIdProvider provideCurrentUserIdProvider(Context context) {
    AuthRepository authRepo = provideAuthRepository(context);
    if (useFake()) {
        return () -> {
            UserEntity user = authRepo.getCurrentUser().getValue();
            return user != null ? user.getId() : DemoData.ALICE;
        };
    }
    return () -> {
        SessionManager session = SessionManager.get();
        if (session == null || !session.hasToken()) return null;
        UserEntity user = authRepo.getCurrentUser().getValue();
        return user != null ? user.getId() : session.getUserId();
    };
}
```

---

## 4. Android UI, MVVM, Navigation & Resources Patterns (`app/`)

### 4.1 Validator, Error Mapping & `ContactsViewModel` (`CreationExtras` + `SavedStateHandle`)
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:.../AuthValidator.java`, `AuthErrors.java`, `AuthViewModel.java`, `ProfileViewModel.java`.
- **Đặc trưng kế thừa:**
  1. `ContactsValidator`: tái sử dụng `AuthValidator.toE164(input)` cho SĐT Việt Nam (hỗ trợ `0901111111`, `901 111 111`, `+84901111111`), đồng thời chấp nhận số E.164 quốc tế hợp lệ `^\+[1-9][0-9]{7,14}$` sau khi bỏ khoảng trắng/dấu gạch ngang (`D-04`). Tái sử dụng `AuthValidator.displayPhone` & `AuthValidator.initials`.
  2. `ContactsViewModel`: dùng `ViewModelProvider.Factory.from(new ViewModelInitializer<>(...))` nhận `SavedStateHandle`, `ContactsRepository`, `CurrentUserIdProvider`.
     - `SavedStateHandle` giữ `filterQuery` (màn Danh bạ), `selectedTab` (`0 = Incoming`, `1 = Outgoing` ở màn Lời mời), `lastPhoneQuery` (màn Tìm kiếm).
     - Giữ `MutableLiveData<ContactsResult<UserLookupResult>> lookupState` trong ViewModel để thẻ kết quả 5 trạng thái sống sót qua xoay màn hình, và dùng wrapper `Event<T>` (theo tinh thần `SessionEvents.consume()`) cho thông báo Snackbar một lần.

```java
// Mẫu Factory & LiveData lọc bạn bè trong ContactsViewModel.java (theo AuthViewModel.java):
public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
        new ViewModelInitializer<>(ContactsViewModel.class, extras -> {
            Application app = extras.get(APPLICATION_KEY);
            return new ContactsViewModel(
                    createSavedStateHandle(extras),
                    Injection.provideContactsRepository(app),
                    Injection.provideCurrentUserIdProvider(app));
        }));

// Kết hợp danh sách bạn bè từ Room với từ khóa lọc nội bộ lưu trong SavedStateHandle:
private final MediatorLiveData<List<UserEntity>> filteredFriends = new MediatorLiveData<>();
```

### 4.2 RecyclerView `ListAdapter + DiffUtil` & Confirmation Dialog Pattern
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:.../ui/profile/DeviceAdapter.java`, `DevicesFragment.java`.
- **Đặc trưng kế thừa:**
  1. Mọi `RecyclerView` (`FriendsAdapter`, `ContactRequestsAdapter`, `BlockedUsersAdapter`) bắt buộc kế thừa `ListAdapter<T, Holder>` với `DiffUtil.ItemCallback<T>`; tuyệt đối không dùng `notifyDataSetChanged()`.
  2. Dải ngăn cách dùng `MaterialDividerItemDecoration` inset `76dp` và `setLastItemDecorated(false)` (đúng như `DevicesFragment.java`).
  3. Mọi thao tác hủy kết bạn (`unfriend`), chặn (`block`), bỏ chặn (`unblock`) mở hộp thoại xác nhận `MaterialAlertDialogBuilder` trước khi gọi API.
  4. **Quy tắc giao diện bắt buộc (`D-02`):** Thẻ bạn bè (`item_contact_friend.xml` và thẻ `FRIEND` trong `fragment_search_user.xml`) **tuyệt đối ẩn nút "Nhắn tin"** ở Phase 4 (không để nút disabled, không hiện thông báo kỹ thuật lộ Phase 5).

```java
// Mẫu Adapter theo DeviceAdapter.java:
public class BlockedUsersAdapter extends ListAdapter<UserEntity, BlockedUsersAdapter.Holder> {
    public interface OnUnblockListener { void onUnblock(UserEntity user); }
    private final OnUnblockListener listener;

    public BlockedUsersAdapter(OnUnblockListener listener) {
        super(new DiffUtil.ItemCallback<UserEntity>() {
            @Override public boolean areItemsTheSame(@NonNull UserEntity a, @NonNull UserEntity b) {
                return a.getId().equals(b.getId());
            }
            @Override public boolean areContentsTheSame(@NonNull UserEntity a, @NonNull UserEntity b) {
                return Objects.equals(a.getDisplayName(), b.getDisplayName())
                        && Objects.equals(a.getPhone(), b.getPhone())
                        && Objects.equals(a.getAvatarUrl(), b.getAvatarUrl());
            }
        });
        this.listener = listener;
    }
    // ... ViewHolder giữ ItemBlockedUserBinding
}
```

### 4.3 Navigation (`nav_contacts.xml`), Toolbar, DayNight XML & Test Compatibility
- **Tệp mẫu đối chiếu:** `feature/phase-3-auth:.../res/navigation/nav_profile.xml`, `MainActivity.java`, `fragment_devices.xml`, `fragment_profile.xml`, `menu_save.xml`, `app/src/androidTest/java/com/utechat/FoundationNavigationTest.java`.
- **Ràng buộc quan trọng đã xác minh trong codebase:**
  1. **`FoundationNavigationTest.java#L40-L42`:** Kiểm thử hiện có kiểm tra `onView(withText(R.string.title_contacts)).check(matches(isDisplayed()))` khi chuyển sang tab Danh bạ. Vì vậy `ContactsListFragment` **bắt buộc** giữ tiêu đề `MaterialToolbar` là `app:title="@string/title_contacts"`.
  2. **`MainActivity.java#L34-L35`:** `bottomNav` chỉ hiện với `contactsListFragment` và tự động ẩn khi điều hướng sang `friendRequestsFragment`, `searchUserFragment`, `blockedUsersFragment`.
  3. **Màu icon Menu trên `MaterialToolbar`:** `ic_search.xml` dùng `android:tint="?attr/colorOnSurface"` (đen ở nền sáng), trong khi nền `Widget.UTEChat.Toolbar` là `@color/ute_bar` (`#0A6099` xanh đậm). Trong `menu_contacts.xml`, gắn `app:iconTint="@color/ute_on_bar"` (hoặc chỉnh tint trong code khi inflate menu) để icon tìm kiếm luôn trắng rõ ràng ở cả Light và Dark mode.
  4. **Ngôn ngữ & Chuỗi (`ADR-025`):** Chỉ chỉnh sửa `values/strings_contacts.xml` và `values-vi/strings_contacts.xml`. Không sửa `strings_auth.xml`, `strings_profile.xml`, `strings_common.xml`.

```xml
<!-- Mẫu nav_contacts.xml (theo nav_profile.xml): -->
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_contacts"
    app:startDestination="@id/contactsListFragment">
    <fragment android:id="@+id/contactsListFragment" android:name="com.utechat.ui.contacts.ContactsListFragment">
        <action android:id="@+id/action_contacts_to_requests" app:destination="@id/friendRequestsFragment" />
        <action android:id="@+id/action_contacts_to_search" app:destination="@id/searchUserFragment" />
        <action android:id="@+id/action_contacts_to_blocked" app:destination="@id/blockedUsersFragment" />
    </fragment>
    <fragment android:id="@+id/friendRequestsFragment" android:name="com.utechat.ui.contacts.FriendRequestsFragment" />
    <fragment android:id="@+id/searchUserFragment" android:name="com.utechat.ui.contacts.SearchUserFragment" />
    <fragment android:id="@+id/blockedUsersFragment" android:name="com.utechat.ui.contacts.BlockedUsersFragment" />
</navigation>
```

### 4.4 Android JVM Unit Test Patterns
- **Tệp mẫu đối chiếu:** `app/src/test/java/com/utechat/data/repository/FakeRepositoriesTest.java`, `app/src/test/java/com/utechat/ui/splash/HealthViewModelTest.java`, `feature/phase-3-auth:.../AuthValidatorTest.java`.
- **Đặc trưng kế thừa:**
  1. `@Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();` cho mọi test LiveData (`ContactsViewModelTest`, `RealContactsRepositoryTest`, `FakeRepositoriesTest`).
  2. `RealContactsRepositoryTest` tiêm `Executor direct = Runnable::run;` làm `diskExecutor` để toàn bộ ghi Room chạy đồng bộ trong test, mock `ContactApi` bằng `retrofit2.Call<T>` giả/Mockito kiểm chứng đầy đủ: parse 5 trạng thái lookup, `429 retryAfterSeconds`, `409 existingRequestId + direction`, `upsertPreservingPrivacy`, mất mạng giữ nguyên cache, và hủy ghi khi `CurrentUserIdProvider` đổi/rỗng lúc logout (`D-10`).

---

## 5. Mandatory Coding & Security Rules Checklist (`GEMINI.md`, `AGENTS.md`, `DEPLOYMENT_SECURITY.md`)

Mọi plan và code được sinh ra từ tài liệu pattern này bắt buộc đáp ứng:
1. **100% Java 17** trên cả Android và Backend; tuyệt đối không dùng Kotlin, Compose, Coroutines, Hilt, hoặc Spring Data JPA.
2. **100% Educational Vietnamese In-Code Comments** trên mọi lớp, phương thức, truy vấn SQL, LiveData, Adapter, XML layout/navigation và test case (giải thích cả *làm gì* và *tại sao* phục vụ bảo vệ đồ án).
3. **Bảo mật V1 (`docs/DEPLOYMENT_SECURITY.md`):**
   - Kiểm quyền đầy đủ ở server (`addressee_id`, `requester_id`, `BLOCKED` 2 chiều, `404` khi tìm người tắt `discoverable` hoặc bị block).
   - Không rò rỉ SĐT trong `ContactRequestDto`, không log JWT/password, không hardcode URL backend, luôn dùng relative path `"api/..."` qua `ApiClient` đã gắn `AuthInterceptor`.
4. **Dọn cache cô lập tài khoản (`D-10`):** Mọi thao tác Room chạy trên cùng `DISK` executor với `clearAllTables()` và đối chiếu lại `ownerAtStart` trước khi ghi.
