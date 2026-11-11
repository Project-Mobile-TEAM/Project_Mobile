# Phase 3: Authentication & Profile Management - Pattern Mapping

**Phase:** 03 — Authentication & Profile Management  
**Date:** 2026-11-11  
**Author:** GSD Pattern Mapper  
**Status:** COMPLETE  

---

## 1. Overview & Component Inventory

Bảng dưới đây tổng hợp toàn bộ các tệp mã nguồn sẽ được tạo mới hoặc chỉnh sửa trong Phase 03, phân loại theo vai trò kiến trúc, luồng dữ liệu, tệp mẫu đối chiếu (analog) hiện có trong codebase, hoặc quy chuẩn chuẩn tắc (canonical standard pattern) nếu là thành phần mới hoàn toàn (greenfield).

### 1.1 Backend Spring Boot 3 Inventory

| Tệp mục tiêu | Trạng thái | Vai trò kiến trúc | Tệp mẫu đối chiếu (Analog) | Quy chuẩn áp dụng |
|---|---|---|---|---|
| `backend/pom.xml` | Modified | Dependency Management | `backend/pom.xml` | Khai báo `spring-boot-starter-security`, `validation`, `jjwt-api:0.12.6`, `jjwt-impl`, `jjwt-jackson` |
| `backend/.../controller/AuthController.java` | Created | REST Controller (`/api/auth/**`) | `controller/HealthController.java` | `@RestController`, `@RequestMapping`, Constructor Injection, HTTP Status Codes chuẩn, comment Tiếng Việt |
| `backend/.../controller/AccountController.java` | Created | REST Controller (`/api/me/**`, `/api/account/**`) | `controller/HealthController.java` | `@RestController`, Principal `AuthenticatedActor`, `@AuthenticationPrincipal`, comment Tiếng Việt |
| `backend/.../dto/auth/*.java` (16 DTO records) | Created | Data Transfer Objects | `dto/HealthResponse.java` | Java `record` bất biến, Jackson serialization tự động, comment mục đích từng trường |
| `backend/.../service/AuthService.java` | Created | Core Auth Business Logic | *No analog (greenfield)* | `@Service`, constructor injection, `@Transactional`, phối hợp CSDL PostgreSQL và các service phụ |
| `backend/.../service/AccountService.java` | Created | Profile & Account Management | *No analog (greenfield)* | `@Service`, quản lý thiết bị, đổi mật khẩu, xóa mềm CSDL trong 1 transaction duy nhất |
| `backend/.../service/ThrottleService.java` | Created | Anti-Brute-Force Engine | *No analog (greenfield)* | Quản lý bảng `auth_throttles`, tính retry-after, ném `LockedException` (HTTP 423) |
| `backend/.../service/OtpService.java` | Created | OTP Generation & Verification | *No analog (greenfield)* | SecureRandom 6 số, SHA-256 hash, hạn 5m, cooldown 60s, ghi `demo_sms_outbox`, trả `demoOtp` |
| `backend/.../config/SecurityConfig.java` | Created | Spring Security 6 Config | `config/CorsConfig.java` | `SecurityFilterChain` bean, stateless session, tắt CSRF, phân quyền endpoint, đăng ký JWT filter |
| `backend/.../security/JwtService.java` | Created | JWT Issue & Validation | *No analog (greenfield)* | JJWT 0.12.6, HMAC-SHA256, 30 ngày cho access token, 10 phút cho registration/reset token |
| `backend/.../security/JwtAuthenticationFilter.java` | Created | OkHttp Request Filter | *No analog (greenfield)* | Kế thừa `OncePerRequestFilter`, giải mã Bearer JWT, kiểm tra `token_valid_after` từ `user_devices` |
| `backend/.../security/AuthenticatedActor.java` | Created | Principal Domain Object | *No analog (greenfield)* | Record/Class chứa `UUID userId` và `String deviceIdHash` đại diện danh tính đã xác thực |
| `backend/.../exception/GlobalExceptionHandler.java` | Created | REST Exception Handling | *No analog (greenfield)* | `@RestControllerAdvice`, bắt domain exceptions, format JSON `{ "error": { "code", "message", "details" } }` |
| `backend/.../util/HashUtils.java` | Created | Hashing Helper | *No analog (greenfield)* | Tiện ích `sha256Hex` bằng `java.security.MessageDigest` |
| `backend/.../auth/AuthControllerTest.java` | Created | REST Unit/Integration Test | `health/HealthControllerTest.java` | `@WebMvcTest` / `@SpringBootTest`, `MockMvc`, kiểm thử status, jsonPath, không rò rỉ secret |
| `backend/.../auth/AuthThrottleServiceTest.java` | Created | Service Unit Test | `health/HealthControllerTest.java` | Kiểm tra bộ đếm brute-force, ngưỡng 5 lần sai khóa 15m, 20 lần sai khóa 1h |
| `backend/.../auth/OtpServiceTest.java` | Created | Service Unit Test | `health/HealthControllerTest.java` | Kiểm tra sinh OTP, băm SHA-256, hết hạn 5 phút, cooldown 60s, tối đa 5 lần thử |
| `backend/.../account/AccountControllerTest.java` | Created | REST Controller Test | `health/HealthControllerTest.java` | Kiểm tra đổi mật khẩu, xem/thu hồi thiết bị, xóa mềm tài khoản giải phóng SĐT |

---

### 1.2 Android Client Inventory

| Tệp mục tiêu | Trạng thái | Vai trò kiến trúc | Tệp mẫu đối chiếu (Analog) | Quy chuẩn áp dụng |
|---|---|---|---|---|
| `gradle/libs.versions.toml` & `app/build.gradle.kts` | Modified | Build Config | `gradle/libs.versions.toml` | Khai báo `androidx.security:security-crypto:1.1.0-alpha06` |
| `app/.../data/local/SessionManager.java` | Created | Secure Local Session Storage | *No analog (greenfield)* | `EncryptedSharedPreferences` với MasterKey AES-256, fallback an toàn cho môi trường test JVM |
| `app/.../data/remote/interceptor/AuthInterceptor.java` | Created | OkHttp Bearer Interceptor | *No analog (greenfield)* | Gắn `Authorization: Bearer <token>`, phân biệt nghiêm ngặt lỗi mạng vs HTTP 401 |
| `app/.../data/remote/api/ApiClient.java` | Modified | Retrofit HTTP Client Setup | `data/remote/api/ApiClient.java` | Bổ sung `AuthInterceptor` vào `OkHttpClient.Builder`, giữ logging an toàn (BASIC) |
| `app/.../data/remote/api/AuthApi.java` | Modified | Retrofit Endpoint Interface | `data/remote/api/AuthApi.java`, `ApiService.java` | Định nghĩa toàn bộ endpoint `/api/auth/**`, `/api/me/**`, `/api/account/devices/**` |
| `app/.../data/remote/model/auth/*.java` | Created | Retrofit DTO Models | `data/remote/model/HealthDto.java` | Java class thuần, no-arg constructor, getters/setters cho Gson |
| `app/.../data/repository/AuthRepository.java` | Modified | Seam Interface | `data/repository/AuthRepository.java` | Giữ nguyên 3 hàm cũ cho tương thích test, mở rộng các phương thức auth/profile Phase 3 |
| `app/.../data/repository/real/RealAuthRepository.java` | Modified | Production Repository | `data/repository/HealthRepository.java`, `real/RealAuthRepository.java` | Constructor injection `UserDao`, `AuthApi`, `SessionManager`, `AppExecutors` |
| `app/.../data/repository/fake/FakeAuthRepository.java` | Modified | Debug Mock Repository | `data/repository/fake/FakeAuthRepository.java` | Giả lập toàn bộ thao tác auth/profile bằng in-memory `DemoData` phục vụ demo preview |
| `app/.../di/Injection.java` | Modified | Pure Java Service Locator | `di/Injection.java` | Bổ sung `provideSessionManager()`, cập nhật `provideAuthRepository()` |
| `app/.../di/ViewModelFactory.java` | Modified | ViewModel Provider Factory | `di/ViewModelFactory.java` | Hỗ trợ khởi tạo `AuthViewModel` và `ProfileViewModel` có tham số repository |
| `app/.../ui/auth/AuthViewModel.java` | Created | Auth MVVM State Holder | `ui/splash/HealthViewModel.java` | Quản lý LiveData trạng thái (IDLE, LOADING, SUCCESS, ERROR, LOCKED), hủy Call khi onCleared |
| `app/.../ui/profile/ProfileViewModel.java` | Created | Profile MVVM State Holder | `ui/splash/HealthViewModel.java` | LiveData hồ sơ, danh sách thiết bị, công tắc riêng tư, đổi mật khẩu, xóa tài khoản |
| `app/.../ui/auth/LoginFragment.java` | Modified | Dual-Mode Login Screen | `ui/auth/LoginFragment.java`, `ui/splash/SplashFragment.java` | ViewBinding, toggle 2 tab (Mật khẩu / OTP), hiển thị Snackbar mã demo, xử lý lỗi HTTP 423 |
| `app/.../ui/auth/RegisterFragment.java` | Created | Phone Input Screen | `ui/auth/LoginFragment.java` | Nhập SĐT, format E.164 tự động, kiểm tra regex, chuyển sang OtpVerifyFragment |
| `app/.../ui/auth/OtpVerifyFragment.java` | Created | 6-Digit OTP Screen | `ui/splash/SplashFragment.java` | Bộ đếm ngược 60s, nút gửi lại, Snackbar hiển thị mã kèm nút "Điền ngay" (Auto-fill) |
| `app/.../ui/auth/RegisterCompleteFragment.java` | Created | Name & Password Setup | `ui/auth/LoginFragment.java` | Nhập displayName, mật khẩu $\ge 8$ ký tự, hoàn tất đăng ký nhận token |
| `app/.../ui/auth/ForgotPasswordFragment.java` | Created | Password Recovery Phone Screen | `ui/auth/LoginFragment.java` | Nhập SĐT yêu cầu mã reset mật khẩu |
| `app/.../ui/auth/ResetPasswordFragment.java` | Created | New Password Screen | `ui/auth/LoginFragment.java` | Nhập mật khẩu mới với resetToken |
| `app/.../ui/profile/ProfileFragment.java` | Modified | Profile & Settings Screen | `ui/profile/ProfileFragment.java`, `ui/splash/SplashFragment.java` | Xem/sửa thông tin, công tắc riêng tư, đổi mật khẩu dialog, đổi ngôn ngữ/theme dialog, logout, soft delete dialog |
| `app/.../ui/profile/DeviceManagementFragment.java` | Created | Active Devices Screen | `ui/splash/SplashFragment.java` | RecyclerView danh sách thiết bị, badge "Thiết bị này", nút thu hồi thiết bị từ xa |
| `app/.../ui/profile/DeviceAdapter.java` | Created | RecyclerView Device Adapter | *No analog (greenfield)* | `RecyclerView.Adapter`, `DiffUtil.ItemCallback`, ViewBinding `ItemDeviceBinding` |
| `app/.../util/PhoneUtils.java` | Created | E.164 Phone Normalizer | *No analog (greenfield)* | Tiện ích chuẩn hóa `0...` thành `+84...`, regex `^\+[1-9]\d{7,14}$` |
| `app/.../util/ThemeHelper.java` | Created | DayNight Mode Manager | *No analog (greenfield)* | Áp dụng `AppCompatDelegate.setDefaultNightMode`, lưu preference |
| `app/.../util/LanguageHelper.java` | Created | In-App Language Manager | *No analog (greenfield)* | Áp dụng `AppCompatDelegate.setApplicationLocales`, lưu preference |
| `app/.../util/AppExecutors.java` | Created | Background Threading Helper | *No analog (greenfield)* | Disk IO pool và Main thread executor phục vụ Room/Callback an toàn |
| XML Layouts & Dialogs (11 files) | Created/Mod | UI Presentation (Material 3) | `res/layout/fragment_login.xml`, `fragment_splash.xml` | `LinearLayout`/`ConstraintLayout`, theme attributes (`?attr/colorSurface`), `minHeight=48dp` |
| `res/navigation/nav_auth.xml` | Modified | Auth Navigation Graph | `res/navigation/nav_auth.xml` | Khai báo các fragment auth và actions điều hướng kèm `popUpTo` |
| `res/navigation/nav_profile.xml` | Modified | Profile Navigation Graph | `res/navigation/nav_profile.xml` | Khai báo `ProfileFragment` và `DeviceManagementFragment` |
| String Resources (4 files) | Modified | Internationalization (en & vi) | `res/values/strings_auth.xml`, `res/values-vi/strings_auth.xml` | Cặp chuỗi song ngữ Anh - Việt cho toàn bộ màn hình xác thực và hồ sơ |
| Android Unit Tests (5 files) | Created | JVM Unit Tests | `ui/splash/HealthViewModelTest.java`, `data/repository/FakeRepositoriesTest.java` | JUnit 4, `InstantTaskExecutorRule`, Mockito, kiểm thử JVM nhanh không cần emulator |

---

## 2. Backend Spring Boot 3 Patterns

### 2.1 Controller Pattern
- **Tệp mẫu đối chiếu:** `backend/src/main/java/com/utechat/backend/controller/HealthController.java`
- **Đặc trưng kế thừa:**
  1. Sử dụng annotation `@RestController` và `@RequestMapping("/api/...")`.
  2. Bắt buộc dùng **Constructor Injection** cho toàn bộ Service / JdbcTemplate để hỗ trợ thay thế mock trong Unit Test (không dùng `@Autowired` trên trường).
  3. Trả về `ResponseEntity<T>` với kiểu dữ liệu rõ ràng (DTO / record), không trả về kiểu `Object` hay `Map` mơ hồ.
  4. Phân định rõ ràng mã HTTP: `200 OK`, `201 CREATED`, `204 NO_CONTENT`, `401 UNAUTHORIZED`, `423 LOCKED`, `429 TOO_MANY_REQUESTS`.
  5. Tuyệt đối không để lộ mật khẩu, mã hash, thông tin stacktrace CSDL ra ngoài JSON phản hồi.

```java
// Ví dụ trích xuất từ mẫu HealthController.java đối chiếu sang AuthController.java:
package com.utechat.backend.controller;

import com.utechat.backend.dto.auth.*;
import com.utechat.backend.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller xử lý các luồng xác thực (Đăng ký, Đăng nhập, Quên mật khẩu, Đăng xuất).
 * Tuân thủ nghiêm ngặt hợp đồng API_CONTRACT mục 2.1 và nguyên tắc không rò rỉ credential.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // Constructor injection giúp dễ dàng thay thế bằng Mock trong Unit Test
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Bước 1 Đăng ký: Nhận SĐT E.164, kiểm tra trùng lặp và gửi mã OTP.
     */
    @PostMapping("/register/otp")
    public ResponseEntity<RegisterOtpResponse> requestRegisterOtp(@RequestBody RegisterOtpRequest request) {
        // Gọi nghiệp vụ sinh thử thách OTP trong CSDL
        RegisterOtpResponse response = authService.requestRegisterOtp(request.phone(), request.deviceId());
        return ResponseEntity.ok(response);
    }

    /**
     * Đăng nhập bằng mật khẩu: Kiểm tra brute-force, đối soát BCrypt và cấp JWT 30 ngày.
     */
    @PostMapping("/login/password")
    public ResponseEntity<AuthSuccessResponse> loginPassword(@RequestBody PasswordLoginRequest request) {
        // Thực hiện xác thực danh tính và ghi nhận phiên thiết bị
        AuthSuccessResponse response = authService.loginPassword(
                request.phone(), request.password(), request.deviceId(), request.deviceName());
        return ResponseEntity.ok(response);
    }
}
```

---

### 2.2 DTO Record Pattern
- **Tệp mẫu đối chiếu:** `backend/src/main/java/com/utechat/backend/dto/HealthResponse.java`
- **Đặc trưng kế thừa:**
  1. Sử dụng Java `record` (tính năng từ Java 14+ / 17 LTS) để tạo DTO bất biến (immutable), giảm thiểu boilerplate code (tự sinh constructor, getters, `equals()`, `hashCode()`, `toString()`).
  2. Jackson trong Spring Boot tự động chuyển đổi tên các thành phần record thành thuộc tính JSON `camelCase`.
  3. Đính kèm chú thích Tiếng Việt trên từng thành phần giải thích ý nghĩa nghiệp vụ.

```java
// Ví dụ trích xuất từ mẫu HealthResponse.java đối chiếu sang DTO của Auth:
package com.utechat.backend.dto.auth;

/**
 * DTO yêu cầu gửi mã OTP đăng ký.
 * Record bất biến: Jackson tự động parse JSON từ client sang đối tượng Java.
 *
 * @param phone Số điện thoại định dạng chuẩn E.164 (+84...)
 * @param deviceId Định danh duy nhất của thiết bị gửi yêu cầu
 */
public record RegisterOtpRequest(
    String phone,
    String deviceId
) { }

/**
 * DTO phản hồi khi gửi OTP thành công kèm mã demo cho môi trường dev.
 *
 * @param challengeId UUID thử thách để dùng xác minh ở bước sau
 * @param expiresInSeconds Thời gian hiệu lực của mã (300 giây = 5 phút)
 * @param demoOtp Mã OTP gửi kèm khi bật profile dev/demo giúp kiểm thử 1-tap
 */
public record RegisterOtpResponse(
    String challengeId,
    int expiresInSeconds,
    String demoOtp
) { }
```

---

### 2.3 Service Pattern (Greenfield Canonical Standard)
- **Tệp mẫu đối chiếu:** *No analog (greenfield trong backend)*
- **Quy chuẩn chuẩn tắc:**
  1. Đánh dấu `@Service` trên lớp nghiệp vụ.
  2. Sử dụng `@Transactional` (Spring Framework) cho các thao tác làm thay đổi nhiều bảng dữ liệu (ví dụ: xác thực OTP + cập nhật CSDL, hoặc đổi mật khẩu + thu hồi các thiết bị khác, hoặc xóa mềm tài khoản).
     - **QUAN TRỌNG (P1 - Commit Thất bại Độc lập):** Với `ThrottleService.recordPasswordLoginFailure` và `recordOtpFailure`, BẮT BUỘC dùng `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Nếu dùng transaction mặc định, khi `AuthService.loginPassword` ném `BadCredentialsException`, Spring sẽ rollback toàn bộ transaction khiến số lần nhập sai trong `auth_throttles` bị hoàn tác! `REQUIRES_NEW` đảm bảo việc tăng biến đếm thất bại được COMMIT NGAY LẬP TỨC vào PostgreSQL độc lập với ngoại lệ đăng nhập.
  3. Tương tác CSDL trực tiếp qua `JdbcTemplate` với câu truy vấn tham số hóa (Parameterized SQL) chống triệt để tấn công SQL Injection.
  4. Đọc và trả về DTO record, không làm lộ các model nội bộ của CSDL ra tầng Controller.

```java
package com.utechat.backend.service;

import com.utechat.backend.dto.auth.AuthSuccessResponse;
import com.utechat.backend.dto.auth.UserDto;
import com.utechat.backend.security.JwtService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dịch vụ xử lý nghiệp vụ xác thực người dùng.
 * Phối hợp kiểm tra brute-force, đối soát mật khẩu BCrypt và cấp phát token JWT.
 */
@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ThrottleService throttleService;

    // Constructor injection các phụ thuộc cần thiết
    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                       JwtService jwtService, ThrottleService throttleService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttleService = throttleService;
    }

    /**
     * Xác thực tài khoản bằng số điện thoại và mật khẩu.
     * Enforce kiểm tra khóa brute-force trước khi kiểm tra mật khẩu.
     */
    @Transactional
    public AuthSuccessResponse loginPassword(String phone, String password, String deviceId, String deviceName) {
        // 1. Kiểm tra xem tài khoản hoặc thiết bị có đang bị khóa do nhập sai quá nhiều lần không
        throttleService.checkPasswordLoginThrottle(phone, deviceId);

        // 2. Truy vấn thông tin người dùng từ CSDL theo SĐT E.164
        // ... (kiểm tra password qua passwordEncoder.matches) ...

        // 3. Nếu sai mật khẩu: tăng bộ đếm failure_count trong auth_throttles và ném lỗi
        // 4. Nếu đúng: reset bộ đếm, cập nhật/tạo bản ghi user_devices và cấp JWT 30 ngày
        return null;
    }
}
```

---

### 2.4 Spring Security & JWT Pattern
- **Tệp mẫu đối chiếu:** `backend/src/main/java/com/utechat/backend/config/CorsConfig.java` *(cho ranh giới @Configuration)* và chuẩn tắc Spring Security 6.
- **Đặc trưng kế thừa & Quy chuẩn:**
  1. `SecurityConfig`: Cấu hình `SecurityFilterChain` không lưu trạng thái (`SessionCreationPolicy.STATELESS`), vô hiệu hóa CSRF (`csrf.disable()`), mở quyền truy cập công khai cho `/api/health/**` và `/api/auth/**` (ngoại trừ `/api/auth/logout`), yêu cầu xác thực cho tất cả endpoint còn lại.
  2. `JwtAuthenticationFilter`: Kế thừa `OncePerRequestFilter`, trích xuất header `Authorization: Bearer <token>`, phân tích JWT qua `JwtService`, kiểm tra tính hợp lệ của phiên thiết bị (`user_devices.token_valid_after`) trước khi đưa danh tính vào `SecurityContextHolder`.
  3. `PasswordEncoder`: Đăng ký bean `BCryptPasswordEncoder(10)` đảm bảo chi phí băm $\ge 10$ theo yêu cầu kiểm tra của đồ án.

```java
// Cấu hình Spring Security 6 chuẩn:
package com.utechat.backend.config;

import com.utechat.backend.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Cấu hình ranh giới bảo mật ứng dụng với Spring Security 6.
 * Áp dụng mô hình REST không trạng thái (Stateless) với Bearer JWT.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Tắt CSRF vì REST API dùng Bearer Token, không dùng Cookie
            .csrf(csrf -> csrf.disable())
            // Chế độ không lưu phiên làm việc trên server (Stateless)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Phân quyền ranh giới endpoint theo API_CONTRACT
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/api/auth/logout").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/me/**", "/api/account/**").authenticated()
                .anyRequest().authenticated()
            )
            // Đặt JWT Filter chặn trước UsernamePasswordAuthenticationFilter mặc định
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Thuật toán BCrypt với cost factor = 10 theo tiêu chuẩn đồ án
        return new BCryptPasswordEncoder(10);
    }
}
```

---

### 2.5 REST Exception Handling Pattern (Greenfield Canonical Standard)
- **Tệp mẫu đối chiếu:** *No analog (greenfield trong backend)*
- **Quy chuẩn chuẩn tắc:**
  1. Sử dụng `@RestControllerAdvice` để bắt tập trung mọi ngoại lệ từ tầng Controller/Service.
  2. Định dạng phản hồi lỗi đồng nhất 100% theo quy ước `API_CONTRACT.md` mục 1:
     `{ "error": { "code": "...", "message": "...", "details": { ... } } }`.
  3. Ánh xạ các domain exceptions sang HTTP Status tương ứng:
     - `LockedException` $\rightarrow$ HTTP 423 `LOCKED` (kèm `retryAfterSeconds` trong `details`).
     - `RateLimitException` $\rightarrow$ HTTP 429 `OTP_RATE_LIMIT`.
     - `BadCredentialsException` $\rightarrow$ HTTP 401 `BAD_CREDENTIALS`.
     - `ResourceConflictException` $\rightarrow$ HTTP 409 `PHONE_TAKEN`.
     - `InvalidOtpException` $\rightarrow$ HTTP 400 `OTP_INVALID` / `OTP_EXPIRED`.

---

### 2.6 Backend Unit Test Pattern
- **Tệp mẫu đối chiếu:** `backend/src/test/java/com/utechat/backend/health/HealthControllerTest.java`
- **Đặc trưng kế thừa:**
  1. Sử dụng `@WebMvcTest(TargetController.class)` kết hợp `@MockBean` cho các dịch vụ phụ thuộc để cô lập phạm vi kiểm thử HTTP nhanh chóng mà không cần khởi động toàn bộ ngữ cảnh Spring Boot.
  2. Sử dụng `MockMvc` mô phỏng các yêu cầu REST, kiểm tra mã trạng thái HTTP (`status().isOk()`, `status().isUnauthorized()`, v.v.).
  3. Sử dụng `jsonPath` kiểm tra các trường trong JSON trả về và xác minh không làm rò rỉ các chuỗi nhạy cảm (secret, password, stacktrace).

```java
// Trích xuất mẫu từ HealthControllerTest.java áp dụng cho AuthControllerTest.java:
package com.utechat.backend.auth;

import com.utechat.backend.controller.AuthController;
import com.utechat.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử hợp đồng HTTP cho phân hệ xác thực.
 * Đảm bảo định dạng phản hồi chuẩn và không rò rỉ credential.
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private AuthService authService;

    @Test
    void registerOtpReturnsChallengeIdAndDemoOtp() throws Exception {
        // Mô phỏng service trả về thử thách OTP
        when(authService.requestRegisterOtp("+84901234567", "device-test-123"))
                .thenReturn(new com.utechat.backend.dto.auth.RegisterOtpResponse("chal-uuid", 300, "123456"));

        String requestJson = """
                {
                    "phone": "+84901234567",
                    "deviceId": "device-test-123"
                }
                """;

        mvc.perform(post("/api/auth/register/otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").value("chal-uuid"))
                .andExpect(jsonPath("$.demoOtp").value("123456"));
    }
}
```

---

## 3. Android Client Architecture & Patterns

### 3.1 Secure Local Session Storage Pattern (`SessionManager` & `SessionStorage`)
- **Tệp mẫu đối chiếu:** *No analog (greenfield trong client)*
- **Quy chuẩn chuẩn tắc:**
  1. Sử dụng thư viện `androidx.security:security-crypto` để lưu trữ token JWT và các dữ liệu định danh người dùng an toàn bằng Android Hardware Keystore (chuẩn mã hóa `MasterKey.DEFAULT_MASTER_KEY_ALIAS` với `AES256_GCM`).
  2. **Nguyên tắc Bảo mật Fail-Closed (P1 - Tuyệt đối không fallback plaintext trên thiết bị thật):**
     - Tách riêng interface `SessionStorage`:
       - `EncryptedSessionStorage`: Hiện thực sản phẩm chạy trên Android. Nếu gặp lỗi Keystore (hỏng hardware keystore, lỗi phân vùng bảo mật), BẮT BUỘC ném `SecurityStorageException` và báo lỗi cho người dùng, dừng tiến trình xác thực; TUYỆT ĐỐI KHÔNG âm thầm chuyển sang lưu token bản rõ trong SharedPreferences thường.
       - `InMemorySessionStorage`: Hiện thực in-memory (dùng `HashMap`) phục vụ riêng cho môi trường Unit Test JVM JUnit mà không cần Android Keystore.
  3. Quản lý vòng đời `deviceId`: Sinh chuỗi UUID ngẫu nhiên khi cài đặt ứng dụng lần đầu, lưu cố định và **không bao giờ bị xóa** ngay cả khi người dùng thực hiện đăng xuất tài khoản.
  4. Hỗ trợ phương thức `clearSession()` dọn sạch token, userId, thông tin cá nhân khi đăng xuất hoặc hết hạn phiên.

```java
package com.utechat.data.local;

import android.content.Context;
import com.utechat.data.local.storage.EncryptedSessionStorage;
import com.utechat.data.local.storage.SessionStorage;
import java.util.UUID;

/**
 * Quản lý phiên đăng nhập và khóa xác thực cục bộ của người dùng.
 * Sử dụng SessionStorage (mặc định EncryptedSessionStorage AES-256) bảo vệ chống trích xuất dữ liệu.
 * Áp dụng nguyên tắc fail-closed: lỗi keystore thì dừng và báo lỗi, không lưu plaintext.
 */
public class SessionManager {

    private static final String KEY_TOKEN = "jwt_token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_USER_ID = "user_id";

    private final SessionStorage storage;

    /** Constructor chính thức dùng trên Android: áp dụng mã hóa phần cứng Keystore. */
    public SessionManager(Context context) {
        this(new EncryptedSessionStorage(context));
    }

    /** Constructor tiêm phụ thuộc dùng cho JVM Unit Test (với InMemorySessionStorage). */
    public SessionManager(SessionStorage storage) {
        this.storage = storage;
        ensureDeviceId();
    }

    /**
     * Đảm bảo thiết bị luôn có một deviceId cố định từ lần cài đặt đầu tiên.
     */
    private void ensureDeviceId() {
        if (!prefs.contains(KEY_DEVICE_ID)) {
            String newDeviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, newDeviceId).apply();
        }
    }

    public String getToken() { return prefs.getString(KEY_TOKEN, null); }
    public void saveToken(String token) { prefs.edit().putString(KEY_TOKEN, token).apply(); }
    public String getDeviceId() { return prefs.getString(KEY_DEVICE_ID, ""); }

    /**
     * Xóa sạch phiên đăng nhập nhưng giữ lại deviceId cho thiết bị.
     */
    public void clearSession() {
        String deviceId = getDeviceId();
        prefs.edit().clear().putString(KEY_DEVICE_ID, deviceId).apply();
    }
}
```

---

### 3.2 Network Interceptor Pattern (`AuthInterceptor`)
- **Tệp mẫu đối chiếu:** *No analog (greenfield trong client)*
- **Quy chuẩn chuẩn tắc:**
  1. Kế thừa giao diện `okhttp3.Interceptor`.
  2. Đọc token từ `SessionManager`. Nếu yêu cầu có URL thuộc `BuildConfig.BACKEND_BASE_URL` và token tồn tại: tự động bổ sung header `Authorization: Bearer <token>`.
  3. **Phân biệt nghiêm ngặt Lỗi mạng vs Hết hạn phiên & Tương quan phiên (Quyết định D-06, P1):**
     - Bất kỳ ngoại lệ mạng nào (`java.io.IOException`, mất kết nối mạng, timeout) $\rightarrow$ Tuyệt đối KHÔNG xóa token hoặc gọi logout! Ném tiếp exception để Repository/ViewModel xử lý hiển thị trạng thái Offline/Snackbar.
     - **Tương quan phiên & Đúng lý do (P1):** Chỉ kích hoạt `onSessionExpired()` khi đồng thời:
       a) Request gốc có gửi Bearer token và token đó trùng với token hiện tại trong `SessionManager` (tránh request tồn đọng từ phiên cũ làm đăng xuất oan tài khoản người dùng mới đăng nhập).
       b) Server xác nhận token hết hạn/bị thu hồi (qua header `WWW-Authenticate: Bearer error="invalid_token"` hoặc body `SESSION_EXPIRED`). Các lỗi nghiệp vụ như nhập sai mật khẩu cũ tại màn hình đổi mật khẩu được backend trả HTTP 400 `INCORRECT_CURRENT_PASSWORD`, tuyệt đối không làm người dùng bị văng ra ngoài.

```java
package com.utechat.data.remote.interceptor;

import androidx.annotation.NonNull;
import com.utechat.BuildConfig;
import com.utechat.data.local.SessionManager;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Bộ chặn mạng OkHttp chịu trách nhiệm tự động gắn Bearer Token và xử lý mã 401.
 * Tuân thủ nguyên tắc không đăng xuất người dùng khi chỉ bị mất kết nối mạng tạm thời,
 * và kiểm tra tương quan phiên token để không đăng xuất nhầm tài khoản mới.
 */
public class AuthInterceptor implements Interceptor {

    public interface OnSessionExpiredListener {
        void onSessionExpired();
    }

    private final SessionManager sessionManager;
    private final OnSessionExpiredListener expiredListener;

    public AuthInterceptor(SessionManager sessionManager, OnSessionExpiredListener expiredListener) {
        this.sessionManager = sessionManager;
        this.expiredListener = expiredListener;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();
        Request.Builder builder = original.newBuilder();

        String currentToken = sessionManager.getToken();
        boolean hasToken = currentToken != null && !currentToken.isEmpty();
        // Chỉ gắn token khi gọi vào backend chính thức của ứng dụng
        if (hasToken && original.url().toString().startsWith(BuildConfig.BACKEND_BASE_URL)) {
            builder.header("Authorization", "Bearer " + currentToken);
        }

        // Thực hiện cuộc gọi mạng; nếu IOException xảy ra ở đây, nó sẽ văng ra ngoài mà không xóa session
        Response response = chain.proceed(builder.build());

        // Xử lý mã 401 an toàn: chỉ kích hoạt khi request có mang Bearer token và token đó khớp với phiên hiện tại
        if (response.code() == 401 && hasToken) {
            String authHeader = original.header("Authorization");
            String sentToken = authHeader != null && authHeader.startsWith("Bearer ")
                    ? authHeader.substring(7) : null;

            // Kiểm tra tương quan: token bị 401 phải chính là token của tài khoản đang đăng nhập hiện tại
            if (sentToken != null && sentToken.equals(sessionManager.getToken())) {
                String wwwAuth = response.header("WWW-Authenticate");
                boolean isTokenInvalid = wwwAuth == null || wwwAuth.contains("invalid_token") || wwwAuth.contains("Bearer");
                if (isTokenInvalid) {
                    sessionManager.clearSession();
                    if (expiredListener != null) {
                        expiredListener.onSessionExpired();
                    }
                }
            }
        }

        return response;
    }
}
```

---

### 3.3 Retrofit Remote API Pattern
- **Tệp mẫu đối chiếu:** `app/src/main/java/com/utechat/data/remote/api/AuthApi.java` và `ApiService.java`
- **Đặc trưng kế thừa:**
  1. Khai báo các endpoint bằng HTTP annotations: `@POST`, `@GET`, `@PATCH`, `@DELETE`.
  2. Truyền tham số qua `@Body`, `@Path`, `@Query`.
  3. Giá trị trả về luôn được bọc trong `retrofit2.Call<T>` để Repository có thể điều khiển thực thi bất đồng bộ hoặc hủy cuộc gọi (`call.cancel()`).

```java
package com.utechat.data.remote.api;

import com.utechat.data.remote.model.auth.*;
import java.util.List;
import retrofit2.Call;
import org.springframework.web.bind.annotation.RequestParam;
import retrofit2.http.*;

/**
 * Ranh giới Retrofit định nghĩa toàn bộ hợp đồng API xác thực và quản lý tài khoản.
 * Khớp hoàn toàn với API_CONTRACT mục 2.1 và 2.2.
 */
public interface AuthApi {

    // --- Xác thực (Public) ---
    @POST("api/auth/register/otp")
    Call<RegisterOtpResponseDto> requestRegisterOtp(@Body RegisterOtpRequestDto body);

    @POST("api/auth/register/verify")
    Call<VerifyOtpResponseDto> verifyRegisterOtp(@Body VerifyOtpRequestDto body);

    @POST("api/auth/register/complete")
    Call<AuthSuccessResponseDto> completeRegistration(@Body CompleteRegisterRequestDto body);

    @POST("api/auth/login/password")
    Call<AuthSuccessResponseDto> loginPassword(@Body PasswordLoginRequestDto body);

    @POST("api/auth/login/otp/request")
    Call<OtpChallengeResponseDto> requestLoginOtp(@Body OtpLoginRequestDto body);

    @POST("api/auth/login/otp/verify")
    Call<AuthSuccessResponseDto> verifyLoginOtp(@Body OtpVerifyLoginRequestDto body);

    // --- Quản lý tài khoản (Authenticated) ---
    @GET("api/me")
    Call<UserDto> getMe();

    @PATCH("api/me")
    Call<UserDto> updateProfile(@Body UpdateProfileRequestDto body);

    @POST("api/v1/me/password")
    Call<Void> changePassword(@Body ChangePasswordRequestDto body);

    // Dùng @HTTP hasBody = true thay cho @DELETE kèm @Body để Retrofit không văng lỗi IllegalArgumentException
    @HTTP(method = "DELETE", path = "api/v1/me", hasBody = true)
    Call<Void> deleteAccount(@Body DeleteAccountRequestDto body);

    @GET("api/v1/account/devices")
    Call<List<DeviceDto>> getDevices();

    @DELETE("api/v1/account/devices/{id}")
    Call<Void> revokeDevice(@Path("id") String deviceId);

    @POST("api/v1/auth/logout")
    Call<Void> logout();
}
```

---

### 3.4 Repository Seam Pattern
- **Tệp mẫu đối chiếu:** `app/src/main/java/com/utechat/data/repository/AuthRepository.java`, `real/RealAuthRepository.java`, `fake/FakeAuthRepository.java`, `HealthRepository.java`
- **Đặc trưng kế thừa:**
  1. Giao diện Seam `AuthRepository`: Phục vụ đảo ngược phụ thuộc (Dependency Inversion), giúp UI Fragment/ViewModel không gắn chặt vào Retrofit hay Room.
  2. Giữ nguyên các hàm có sẵn: `login(phone, password)`, `getCurrentUser()`, `logout()` để đảm bảo `FakeRepositoriesTest` hiện tại tiếp tục pass 100%.
  3. Định nghĩa các callback typed cho từng nghiệp vụ (như `HealthCallback` trong `HealthRepository.java`) hoặc trả về `Call<T>` để ViewModel hủy khi bị giải phóng.
  4. Triển khai `RealAuthRepository`:
     - Nhận `UserDao`, `AuthApi`, `SessionManager`, `AppExecutors` qua Constructor.
     - Khi đăng nhập/đăng ký thành công: Lưu token vào `SessionManager`, lưu hồ sơ người dùng vào `UserDao` qua `@Upsert`.
     - Khi `logout()` hoặc `cleanLogout(boolean notifyServer, Runnable onComplete)`:
       a) Khóa phiên & hủy callback: Đặt cờ dọn dẹp, tăng biến đếm `sessionGeneration` (chặn toàn bộ callback ngầm ghi đè lại dữ liệu vào Room).
       b) Hủy mạng & công việc ngầm: `ApiClient.cancelAllRequests()`, `WorkManager.cancelAllWork()`, ngắt WebSocket.
       c) Báo server (best-effort): nếu có mạng và `notifyServer == true`, gọi `POST /api/v1/auth/logout` (bỏ qua nếu mất mạng hoặc sau khi xóa tài khoản).
       d) Dọn sạch cục bộ (guaranteed): xóa toàn bộ Room qua `appDatabase.clearAllTables()`, xóa token Keystore qua `sessionManager.clearSession()`.
       e) Điều hướng về Login và ngăn chặn đăng nhập tài khoản mới cho đến khi việc xóa hoàn tất.

```java
// Cấu trúc callback an toàn lấy mẫu từ HealthRepository.java:
public interface AuthCallback<T> {
    void onSuccess(T data);
    void onError(String errorCode, String errorMessage);
}
```

---

### 3.5 Dependency Injection Pattern (`Injection.java` & `ViewModelFactory.java`)
- **Tệp mẫu đối chiếu:** `app/src/main/java/com/utechat/di/Injection.java` và `ViewModelFactory.java`
- **Đặc trưng kế thừa:**
  1. Service Locator thuần Java: Không dùng thư viện ngoài như Hilt hay Dagger, tuân thủ yêu cầu đồ án.
  2. Bổ sung `provideSessionManager(Context context)` dùng chung một instance duy nhất.
  3. `ViewModelFactory`: Bổ sung kiểm tra khởi tạo `AuthViewModel` và `ProfileViewModel`.

```java
// Cập nhật ViewModelFactory.java theo mẫu hiện tại:
package com.utechat.di;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.HealthRepository;
import com.utechat.ui.auth.AuthViewModel;
import com.utechat.ui.profile.ProfileViewModel;
import com.utechat.ui.splash.HealthViewModel;

/**
 * Factory khởi tạo ViewModel hỗ trợ Dependency Injection thuần Java.
 */
public class ViewModelFactory implements ViewModelProvider.Factory {

    private final HealthRepository health;
    private final AuthRepository auth;

    public ViewModelFactory(HealthRepository health, AuthRepository auth) {
        this.health = health;
        this.auth = auth;
    }

    public ViewModelFactory(HealthRepository health) {
        this(health, null);
    }

    public ViewModelFactory(AuthRepository auth) {
        this(null, auth);
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass == HealthViewModel.class) {
            return (T) new HealthViewModel(health);
        } else if (modelClass == AuthViewModel.class) {
            return (T) new AuthViewModel(auth);
        } else if (modelClass == ProfileViewModel.class) {
            return (T) new ProfileViewModel(auth);
        }
        throw new IllegalArgumentException("ViewModel chưa đăng ký: " + modelClass.getName());
    }
}
```

---

### 3.6 MVVM ViewModel Pattern
- **Tệp mẫu đối chiếu:** `app/src/main/java/com/utechat/ui/splash/HealthViewModel.java`
- **Đặc trưng kế thừa:**
  1. Trạng thái UI được mô hình hóa bằng Enum rõ ràng: `IDLE`, `LOADING`, `SUCCESS`, `ERROR`, `LOCKED`.
  2. Dữ liệu trạng thái là `MutableLiveData` private, chỉ mở ra ngoài dạng `LiveData` bất biến để bảo vệ dữ liệu không bị Fragment sửa đổi tùy tiện.
  3. Quản lý tham chiếu cuộc gọi mạng đang chạy: `private Call<?> activeCall;`.
  4. Phòng chống Race Condition bằng biến đếm thế hệ yêu cầu: `private int generation = 0;`.
  5. Giải phóng tài nguyên và hủy request mạng trong phương thức `onCleared()`.

```java
// Áp dụng trọn vẹn mẫu HealthViewModel.java sang AuthViewModel.java:
package com.utechat.ui.auth;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.utechat.data.repository.AuthRepository;
import retrofit2.Call;

/**
 * ViewModel quản lý logic và trạng thái của các màn hình xác thực (Đăng nhập, Đăng ký, OTP).
 * Bảo toàn dữ liệu qua sự kiện xoay màn hình và ngăn chặn callback cũ gây xung đột.
 */
public class AuthViewModel extends ViewModel {

    public enum AuthState { IDLE, LOADING, SUCCESS, ERROR, LOCKED }

    private final AuthRepository repository;
    private final MutableLiveData<AuthState> state = new MutableLiveData<>(AuthState.IDLE);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<Integer> retryAfterSeconds = new MutableLiveData<>();
    private final MutableLiveData<String> demoOtp = new MutableLiveData<>();

    private Call<?> activeCall;
    private int generation = 0;

    public AuthViewModel(AuthRepository repository) {
        this.repository = repository;
    }

    public LiveData<AuthState> getState() { return state; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public LiveData<Integer> getRetryAfterSeconds() { return retryAfterSeconds; }
    public LiveData<String> getDemoOtp() { return demoOtp; }

    @Override
    protected void onCleared() {
        super.onCleared();
        generation++;
        if (activeCall != null) {
            activeCall.cancel();
        }
    }
}
```

---

### 3.7 Fragment ViewBinding & Lifecycle Pattern
- **Tệp mẫu đối chiếu:** `app/src/main/java/com/utechat/ui/splash/SplashFragment.java` và `ui/auth/LoginFragment.java`
- **Đặc trưng kế thừa:**
  1. Khởi tạo `binding` trong `onCreateView` bằng phương thức `FragmentXxxBinding.inflate(inflater, container, false)`.
  2. Bắt buộc đặt `binding = null` trong `onDestroyView()` để tránh Memory Leak khi Fragment bị đưa vào back stack.
  3. Lấy ViewModel trong `onViewCreated` thông qua `ViewModelProvider` và `ViewModelFactory`.
  4. Luôn quan sát LiveData bằng `getViewLifecycleOwner()` (không dùng `this` vì vòng đời View của Fragment ngắn hơn vòng đời bản thân Fragment).
  5. Kiểm tra an toàn trước khi điều hướng:
     `if (controller.getCurrentDestination() != null && controller.getCurrentDestination().getId() == R.id.currentFragment) { ... }`.

```java
// Áp dụng mẫu SplashFragment.java cho LoginFragment.java:
package com.utechat.ui.auth;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import com.utechat.R;
import com.utechat.databinding.FragmentLoginBinding;
import com.utechat.di.Injection;
import com.utechat.di.ViewModelFactory;

/**
 * Fragment màn hình đăng nhập hỗ trợ 2 chế độ (Mật khẩu và OTP demo).
 */
public class LoginFragment extends Fragment {

    private FragmentLoginBinding binding;
    private AuthViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this, new ViewModelFactory(
                Injection.provideAuthRepository(requireContext()))).get(AuthViewModel.class);

        // Quan sát trạng thái xử lý từ ViewModel
        viewModel.getState().observe(getViewLifecycleOwner(), this::renderState);
    }

    private void renderState(AuthViewModel.AuthState state) {
        if (binding == null || state == null) return;
        boolean loading = state == AuthViewModel.AuthState.LOADING;
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnLogin.setEnabled(!loading);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Giải phóng tham chiếu View để tránh rò rỉ bộ nhớ
        binding = null;
    }
}
```

---

### 3.8 RecyclerView Adapter Pattern (Greenfield Canonical Standard)
- **Tệp mẫu đối chiếu:** *No analog (greenfield trong client)*
- **Quy chuẩn chuẩn tắc:**
  1. Kế thừa `RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>`.
  2. Sử dụng ViewBinding `ItemDeviceBinding` thay cho `findViewById`.
  3. Định nghĩa Interface Callback cho các hành động trên item (ví dụ nút "Thu hồi thiết bị").
  4. Hỗ trợ hiển thị badge "Thiết bị này" nổi bật cho thiết bị hiện tại và ẩn nút thu hồi trên chính thiết bị đó.

---

### 3.9 Utility Helpers Pattern (Greenfield Canonical Standard)
- **Tệp mẫu đối chiếu:** *No analog (greenfield trong client)*
- **Quy chuẩn chuẩn tắc:**
  1. Lớp tiện ích khai báo `final`, có private constructor rỗng ngăn ngừa việc khởi tạo đối tượng bừa bãi.
  2. Các hàm là `public static` thuần túy, không giữ trạng thái context lâu dài.
  3. **`PhoneUtils`:** Chuẩn hóa đầu số Việt Nam `0...` thành `+84...`, kiểm tra định dạng regex `^\+[1-9]\d{7,14}$`.
  4. **`ThemeHelper`:** Gọi `AppCompatDelegate.setDefaultNightMode(mode)` và lưu vào SharedPreferences.
  5. **`LanguageHelper`:** Gọi `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))` và lưu cấu hình.
  6. **`AppExecutors`:** Quản lý `Executor` luồng nền cho thao tác CSDL Room và `Executor` luồng chính (Main Thread) cho UI.

---

### 3.10 Layout & Navigation XML Patterns
- **Tệp mẫu đối chiếu:** `res/layout/fragment_login.xml`, `res/layout/fragment_splash.xml`, `res/navigation/nav_auth.xml`, `res/navigation/nav_profile.xml`
- **Đặc trưng kế thừa:**
  1. Giao diện Material 3: Sử dụng màu sắc qua theme attributes: `?attr/colorSurface`, `?attr/colorOnSurface`, `?attr/colorOnSurfaceVariant`.
  2. Tiêu chuẩn kích thước và trợ năng (Accessibility): Mọi nút bấm và trường nhập liệu đều có `android:minHeight="48dp"`.
  3. Navigation XML: Phân tách rõ ràng các đồ thị con (`nav_auth.xml` và `nav_profile.xml`). Sử dụng `app:popUpTo` và `app:popUpToInclusive="true"` khi đăng nhập/đăng xuất thành công để dọn sạch back stack điều hướng.

---

### 3.11 i18n String Resources Pattern
- **Tệp mẫu đối chiếu:** `app/src/main/res/values/strings_auth.xml` và `app/src/main/res/values-vi/strings_auth.xml`
- **Đặc trưng kế thừa:**
  1. Mọi chuỗi hiển thị trên giao diện (tiêu đề, nhãn, gợi ý lỗi, nút bấm) tuyệt đối không được hardcode trong Java hoặc layout XML.
  2. Khai báo song song 1-1 giữa thư mục mặc định `values/` (Tiếng Anh) và `values-vi/` (Tiếng Việt).
  3. Phân chia tệp tài nguyên theo phân hệ tính năng: `strings_auth.xml` cho xác thực, `strings_profile.xml` cho quản lý tài khoản.

---

### 3.12 Android JVM Unit Test Pattern
- **Tệp mẫu đối chiếu:** `app/src/test/java/com/utechat/ui/splash/HealthViewModelTest.java` và `data/repository/FakeRepositoriesTest.java`
- **Đặc trưng kế thừa:**
  1. Sử dụng JUnit 4 (`@Test`).
  2. Bắt buộc có quy tắc `@Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();` để LiveData thực thi đồng bộ ngay trên luồng kiểm thử mà không cần looper Android.
  3. Tạo Fake Repository hoặc mock callback để kiểm tra trạng thái LiveData (`getValue()`), bảo đảm test chạy nhanh tức thì trên máy tính lập trình mà không cần khởi động máy ảo Emulator.

```java
// Mẫu kiểm thử ViewModel chuẩn:
package com.utechat.ui.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Kiểm thử đơn vị trên JVM cho AuthViewModel.
 * Xác minh tính hợp lệ của số điện thoại và phản hồi trạng thái LiveData.
 */
public class AuthViewModelTest {

    @Rule
    public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    @Test
    public void initialStateIsIdle() {
        AuthViewModel vm = new AuthViewModel(null);
        assertEquals(AuthViewModel.AuthState.IDLE, vm.getState().getValue());
    }
}
```

---

## 4. Quy chuẩn Bắt Buộc về Comment Code Giáo Dục (Tiếng Việt)

Theo quy định bắt buộc tại `GEMINI.md` và `AGENTS.md`, 100% mã nguồn tạo mới hoặc chỉnh sửa (Backend Java, Android Java, XML Layout, Navigation Graph, SQL) **BẮT BUỘC** phải có comment Tiếng Việt chi tiết, rõ ràng.

### 4.1 Bảng Quy Chuẩn Comment Theo Từng Tầng Mã Nguồn

| Tầng mã nguồn | Nội dung bắt buộc phải giải thích trong comment Tiếng Việt |
|---|---|
| **Android ViewModel** | Giải thích ý nghĩa từng trường `LiveData`, tại sao dùng enum trạng thái, mục đích hủy request trong `onCleared()`, cách phòng ngừa callback cũ ghi đè dữ liệu mới. |
| **Android Fragment** | Giải thích lý do tạo và hủy `binding` trong vòng đời, mục đích quan sát LiveData qua `getViewLifecycleOwner()`, ý nghĩa từng thao tác điều hướng `NavController`. |
| **Android Repository** | Giải thích ranh giới Seam giữa Real và Fake, luồng lưu dữ liệu vào Room SQLite, cách bọc token bảo mật trong `SessionManager`. |
| **Android XML** | Giải thích mục đích từng `<action>` trong graph điều hướng (tại sao dùng `popUpTo`), ý nghĩa các thuộc tính trợ năng và DayNight trong layout. |
| **Backend Controller** | Giải thích mã trạng thái HTTP trả về, ranh giới công khai vs yêu cầu xác thực, cam kết không làm lộ secret ra JSON phản hồi. |
| **Backend Service** | Giải thích thuật toán băm khóa, cơ chế chống tấn công brute-force đa tầng, cách quản lý transaction CSDL và thu hồi token thiết bị. |
| **Backend Security** | Giải thích lý do chọn cơ chế Stateless, cách kiểm tra token revoked qua `token_valid_after`, thuật toán BCrypt cost 10. |

---

## 5. Anti-Patterns & Cạm Bẫy Cần Tránh (Traps & Mitigations)

| Cạm bẫy kỹ thuật | Biểu hiện sai lầm (Anti-Pattern) | Giải pháp chuẩn tắc bắt buộc (Best Practice) |
|---|---|---|
| **1. Nhầm lỗi mạng thành hết hạn phiên** | Bắt `IOException` trong OkHttp hoặc Repository rồi tự động gọi `sessionManager.clearSession()` và điều hướng về Login. | Chỉ kích hoạt logout khi server trả về chính xác mã **HTTP 401**. Bất kỳ ngoại lệ mạng nào khác (`SocketTimeoutException`, `UnknownHostException`) đều phải giữ nguyên phiên và fallback đọc Room SQLite. |
| **2. Rò rỉ dữ liệu khi đổi tài khoản** | Đăng xuất chỉ xóa JWT khỏi bộ nhớ máy, bỏ qua dữ liệu cũ trong CSDL cục bộ. | Triển khai **Clean Logout (D-07)**: khi người dùng đăng xuất, gọi `appDatabase.clearAllTables()` trên luồng nền để dọn sạch 10 bảng Room, đồng thời hủy toàn bộ WorkManager và đóng kết nối WebSocket. |
| **3. Xung đột SĐT khi xóa mềm** | Xóa tài khoản chỉ đặt cờ `status = 'DELETED'`, giữ nguyên `phone_e164` dẫn đến lỗi trùng lặp khi người dùng đăng ký lại số đó. | Khi `DELETE /me`, server thực hiện cập nhật `phone_e164 = NULL` trong CSDL để giải phóng ràng buộc duy nhất (Unique Constraint), cho phép số điện thoại đăng ký lại tài khoản mới. |
| **4. Thiếu phụ thuộc bảo mật trên Android** | Không thêm thư viện mã hóa bảo mật dẫn đến lỗi thiếu class khi biên dịch. | Thêm `androidx.security:security-crypto:1.1.0-alpha06` vào `libs.versions.toml` và `app/build.gradle.kts`. Thiết kế `SessionManager` có cơ chế fallback gracefully khi chạy test JVM. |
| **5. Crash định dạng SĐT** | Gửi trực tiếp số điện thoại người dùng gõ vào API (ví dụ `0901234567`) khiến server từ chối vì không đúng chuẩn E.164. | Tạo tiện ích `PhoneUtils` trên Android tự động biến đổi đầu số `0...` thành `+84...` ngay trước khi bấm gửi, kèm biểu thức chính quy kiểm tra định dạng trước khi gọi mạng. |
| **6. Hardcode chuỗi giao diện** | Viết trực tiếp chữ Tiếng Việt hoặc Tiếng Anh vào thuộc tính `android:text="..."` trong XML hoặc chuỗi Java. | Toàn bộ chuỗi hiển thị phải được đặt trong `res/values/strings_*.xml` và `res/values-vi/strings_*.xml`, cho phép chuyển đổi ngôn ngữ mượt mà trong ứng dụng. |

---

*Tài liệu Pattern Mapping hoàn tất. Sẵn sàng cung cấp dữ liệu đầu vào chuẩn hóa cho giai đoạn lập kế hoạch chi tiết (`/gsd-plan-phase 3`).*
