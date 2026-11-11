# Phase 02: Foundation & Anti-Conflict Architecture — Pattern Mapping (PATTERNS.md)

> **Tài liệu tham chiếu:** [02-CONTEXT.md](file:///c:/Users/Admin/Desktop/Project-Mobile/.planning/phases/02-foundation-anti-conflict-architecture/02-CONTEXT.md), [02-RESEARCH.md](file:///c:/Users/Admin/Desktop/Project-Mobile/.planning/phases/02-foundation-anti-conflict-architecture/02-RESEARCH.md), [AGENTS.md](file:///c:/Users/Admin/Desktop/Project-Mobile/AGENTS.md), [GEMINI.md](file:///c:/Users/Admin/Desktop/Project-Mobile/GEMINI.md)  
> **Mục tiêu tài liệu:** Định hình danh mục toàn bộ các tệp cần tạo mới/chỉnh sửa trong Phase 2, phân loại vai trò kiến trúc, ánh xạ mã nguồn tương đồng hiện có (codebase analogs) hoặc cung cấp mẫu chuẩn tắc (canonical standard patterns) cho các thành phần mới hoàn toàn (greenfield), đồng thời hướng dẫn chi tiết chuẩn mực chú thích mã nguồn bằng Tiếng Việt phục vụ mục tiêu giáo dục và bảo vệ đồ án.

---

## 1. Bản đồ Tổng thể Kiến trúc & Luồng Dữ liệu (Architecture & Data Flow Map)

Hệ thống Phase 2 triển khai mô hình **Walking Skeleton** và **Anti-Conflict Architecture** kết nối 3 tầng:
```
[Android Client (Java 17)]                 [Spring Boot 3 Backend]                [Supabase PostgreSQL]
SplashFragment / View / Stubs
        │ (DataBinding / Events)
        ▼
ViewModel (HealthViewModel)
        │ (LiveData)
        ▼
Repository Seam Layer
(HealthRepo / AuthRepo / ContactsRepo / ChatRepo)
        ├──[USE_FAKE_DATA=true]──► Fake Repositories (5 Demo Accounts InMemory)
        └──[USE_FAKE_DATA=false]─► Local Room SQLite (13 Entities) + Remote Retrofit
                                            │
                                            ▼ (HTTP GET /api/health)
                                   HealthController
                                            │ (JdbcTemplate)
                                            ▼
                                   HikariCP (prepareThreshold=0)
                                            │ (Port 6543 Tx Pooler)
                                            ▼
                                   Supabase Cloud PostgreSQL (SELECT 1)
```

---

## 2. Danh mục Phân loại Tệp & Điểm Tương đồng (File Classification & Analogs)

| Phân loại kiến trúc | Tệp cần tạo mới / chỉnh sửa | Điểm tương đồng trong dự án (Analog) | Loại mẫu chuẩn tắc |
|---|---|---|---|
| **Build & Config (Backend)** | `backend/pom.xml`<br>`backend/mvnw`, `backend/mvnw.cmd`<br>`backend/.mvn/wrapper/maven-wrapper.properties`<br>`backend/src/main/resources/application.yml`<br>`backend/src/main/resources/application-dev.yml` | Không có sẵn (Greenfield) | Maven Spring Boot 3 + Dotenv + HikariCP chuẩn Java 17 |
| **Backend REST API** | `backend/src/main/java/com/utechat/backend/BackendApplication.java`<br>`backend/src/main/java/com/utechat/backend/config/CorsConfig.java`<br>`backend/src/main/java/com/utechat/backend/controller/HealthController.java`<br>`backend/src/main/java/com/utechat/backend/dto/HealthResponse.java` | Không có sẵn (Greenfield) | Spring Boot REST Controller + Dotenv bootloader + `SELECT 1` JDBC ping |
| **Build & Config (Android)** | `app/build.gradle.kts`<br>`app/src/main/AndroidManifest.xml`<br>`app/src/main/res/xml/network_security_config.xml` | [app/build.gradle.kts](file:///c:/Users/Admin/Desktop/Project-Mobile/app/build.gradle.kts)<br>[app/src/main/AndroidManifest.xml](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/main/AndroidManifest.xml) | Cấu hình Gradle buildConfigField, Cleartext Network Security, App Permissions |
| **Host & Dynamic Navigation** | `app/src/main/java/com/utechat/MainActivity.java`<br>`app/src/main/res/layout/activity_main.xml`<br>`app/src/main/res/menu/bottom_nav_menu.xml`<br>`app/src/main/res/drawable/ic_tab_*.xml` | [MainActivity.java](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/main/java/com/utechat/MainActivity.java)<br>[activity_main.xml](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/main/res/layout/activity_main.xml) | Pure Single Activity + NavHostFragment + BottomNavigationView 3 tabs |
| **Nested Navigation Graphs** | `app/src/main/res/navigation/nav_graph.xml`<br>`app/src/main/res/navigation/nav_auth.xml`<br>`app/src/main/res/navigation/nav_chat.xml`<br>`app/src/main/res/navigation/nav_contacts.xml`<br>`app/src/main/res/navigation/nav_profile.xml` | Không có sẵn (Greenfield) | Navigation Component `<include>` nested graphs + Safe destination actions |
| **Partitioned Strings** | `app/src/main/res/values/strings_common.xml`<br>`app/src/main/res/values/strings_auth.xml`<br>`app/src/main/res/values/strings_chat.xml`<br>`app/src/main/res/values/strings_contacts.xml`<br>`app/src/main/res/values/strings_profile.xml`<br>*(kèm bộ tương ứng trong `res/values-vi/`)* | [app/src/main/res/values/strings.xml](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/main/res/values/strings.xml) | Phân vùng chuỗi đa ngôn ngữ (EN default / VI localized) |
| **Stub UI & Fragments** | `app/src/main/java/com/utechat/ui/auth/LoginFragment.java`<br>`app/src/main/java/com/utechat/ui/chat/ChatListFragment.java`<br>`app/src/main/java/com/utechat/ui/contacts/ContactsListFragment.java`<br>`app/src/main/java/com/utechat/ui/profile/ProfileFragment.java`<br>*(kèm các file layout `fragment_*.xml`)* | Không có sẵn (Greenfield) | Fragment ViewBinding chuẩn khóa destination |
| **Room SQLite v1** | `app/src/main/java/com/utechat/data/local/Converters.java`<br>`app/src/main/java/com/utechat/data/local/AppDatabase.java`<br>13 Entity trong `com.utechat.data.local.entity.*`<br>DAOs trong `com.utechat.data.local.dao.*` | [backend/src/main/resources/db/migration/V1__initial_schema.sql](file:///c:/Users/Admin/Desktop/Project-Mobile/backend/src/main/resources/db/migration/V1__initial_schema.sql) | Room 2.6.1 Entity (Java 17) + TypeConverters + RoomDatabase Singleton |
| **Networking & Remote API** | `app/src/main/java/com/utechat/data/remote/model/HealthDto.java`<br>`app/src/main/java/com/utechat/data/remote/api/ApiService.java`<br>`app/src/main/java/com/utechat/data/remote/api/ApiClient.java` | Không có sẵn (Greenfield) | Retrofit 2.11 + OkHttp 4.12 Logging + Gson POJO |
| **Seams & Fake Repositories** | `com.utechat.data.repository.AuthRepository`<br>`com.utechat.data.repository.ContactsRepository`<br>`com.utechat.data.repository.ChatRepository`<br>`com.utechat.data.repository.fake.Fake*Repository`<br>`com.utechat.data.repository.real.Real*Repository` | [backend/src/main/resources/db/demo/V2__seed_demo_data.sql](file:///c:/Users/Admin/Desktop/Project-Mobile/backend/src/main/resources/db/demo/V2__seed_demo_data.sql) | Seam Interface Pattern + InMemory Seeded Data (5 demo accounts Phase 1) |
| **Dependency Injection** | `app/src/main/java/com/utechat/di/Injection.java`<br>`app/src/main/java/com/utechat/di/ViewModelFactory.java` | Không có sẵn (Greenfield) | Manual DI / Service Locator + ViewModelProvider.Factory (Slide bài giảng) |
| **End-to-End Probe UI** | `app/src/main/java/com/utechat/ui/splash/SplashFragment.java`<br>`app/src/main/java/com/utechat/ui/splash/HealthViewModel.java`<br>`app/src/main/java/com/utechat/data/repository/HealthRepository.java`<br>`app/src/main/res/layout/fragment_splash.xml` | Không có sẵn (Greenfield) | MVVM LiveData Probe + Auto-nav / Retry / Offline bypass |
| **Unit Testing** | `backend/src/test/java/com/utechat/backend/HealthControllerTest.java`<br>`app/src/test/java/com/utechat/ConvertersTest.java`<br>`app/src/test/java/com/utechat/Fake*RepositoryTest.java`<br>`app/src/test/java/com/utechat/HealthViewModelTest.java` | [app/src/test/java/com/utechat/ExampleUnitTest.java](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/test/java/com/utechat/ExampleUnitTest.java) | MockMvc Spring Test + JUnit 4 + Mockito + Arch Core Testing InstantTaskExecutorRule |

---

## 3. Các Mẫu Chuẩn Tắc & Đoạn Mã Khuôn Mẫu (Canonical Pattern Blueprints)

### 3.1 Backend Spring Boot 3 & Kết nối Supabase (`backend/`)
- **Điểm tương đồng (Analog):** Greenfield (Chưa có code Java trong `backend/src/main/java`).
- **Nguồn dữ liệu tham chiếu:** [V1__initial_schema.sql](file:///c:/Users/Admin/Desktop/Project-Mobile/backend/src/main/resources/db/migration/V1__initial_schema.sql), [.env.example](file:///c:/Users/Admin/Desktop/Project-Mobile/.env.example).
- **Quy tắc chú thích giáo dục (Vietnamese Comments):** Chú thích rõ vai trò nạp tệp `.env`, cấu hình HikariCP cổng 6543 với PgBouncer Transaction Pooler, và mục đích của câu lệnh ping `SELECT 1`.

#### Pattern A1: Khởi động Spring Boot nạp `.env` (`BackendApplication.java`)
```java
package com.utechat.backend;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Điểm khởi chạy chính của ứng dụng máy chủ UTEChat Backend (Spring Boot 3 / Java 17).
 * Mục đích giáo dục:
 * - Nạp tự động các biến môi trường từ tệp .env (CSDL Supabase) trước khi khởi tạo Spring Context.
 * - Cho phép ứng dụng chạy linh hoạt cả từ thư mục gốc hoặc thư mục con backend mà không cần cấu hình thủ công.
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        // Kiểm tra vị trí tệp .env: ưu tiên thư mục hiện tại, nếu không có thì tìm ở thư mục cha
        String envDir = Files.exists(Paths.get(".env")) ? "." : (Files.exists(Paths.get("../.env")) ? ".." : ".");
        Dotenv dotenv = Dotenv.configure()
                .directory(envDir)
                .ignoreIfMissing()
                .load();

        // Nạp các biến cấu hình CSDL vào System Properties để tệp application.yml phân giải biến
        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });

        // Khởi động container Spring Boot và các REST Controllers
        SpringApplication.run(BackendApplication.class, args);
    }
}
```

#### Pattern A2: Health Controller kiểm tra kết nối CSDL thực tế (`HealthController.java`)
```java
package com.utechat.backend.controller;

import com.utechat.backend.dto.HealthResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;

/**
 * REST Controller cung cấp API kiểm tra tình trạng sức khỏe hệ thống (Health Check Probe).
 * Phục vụ Walking Skeleton xác minh kết nối xuyên suốt từ Client Android -> Backend -> CSDL Supabase.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    // JdbcTemplate dùng để thực thi truy vấn SQL trực tiếp qua HikariCP DataSource
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Endpoint kiểm tra liveness và kết nối CSDL thực tế: GET /api/health
     * Trả về HTTP 200 kèm JSON {status: "UP", database: "CONNECTED"} nếu SELECT 1 thành công.
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> checkHealth() {
        try {
            // Thực thi truy vấn kiểm tra kết nối nhẹ nhất tới Supabase PostgreSQL
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (result != null && result == 1) {
                return ResponseEntity.ok(new HealthResponse("UP", "CONNECTED", Instant.now().toString()));
            } else {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new HealthResponse("DOWN", "UNEXPECTED_RESPONSE", Instant.now().toString()));
            }
        } catch (Exception e) {
            // Khi không kết nối được CSDL (mất mạng, sai mật khẩu, pooler từ chối)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("DOWN", "DISCONNECTED: " + e.getMessage(), Instant.now().toString()));
        }
    }

    /**
     * Endpoint readiness probe theo chuẩn hợp đồng API_CONTRACT.md mục 2.0: GET /api/health/ready
     */
    @GetMapping("/health/ready")
    public ResponseEntity<HealthResponse> checkReadiness() {
        return checkHealth();
    }
}
```

---

### 3.2 Khóa Điểm Nóng Mạng & Cấu hình Android (`app/build.gradle.kts` & `network_security_config.xml`)
- **Điểm tương đồng (Analog):** [app/build.gradle.kts](file:///c:/Users/Admin/Desktop/Project-Mobile/app/build.gradle.kts), [app/src/main/AndroidManifest.xml](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/main/AndroidManifest.xml).
- **Quy tắc chú thích giáo dục:** Giải thích vì sao phải dùng `buildConfigField`, tại sao `10.0.2.2` là IP loopback của máy chủ cho Android Emulator, và vì sao Android 9+ chặn kết nối HTTP thường nếu không có Network Security Config.

#### Pattern B1: Khóa biến môi trường trong Gradle (`app/build.gradle.kts`)
```kotlin
// Trích xuất đoạn cấu hình bổ sung vào defaultConfig:
defaultConfig {
    applicationId = "com.utechat"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // [HOTSPOT 1] Cố định URL Backend: 10.0.2.2 là loopback trỏ về localhost máy tính dev từ Android Emulator
    buildConfigField("String", "BACKEND_BASE_URL", "\"http://10.0.2.2:8080/\"")
    // [HOTSPOT 6] Cờ điều khiển nguồn dữ liệu: true = Fake Repositories; false = Backend & Room thật
    buildConfigField("boolean", "USE_FAKE_DATA", "true")

    // Cấu hình vị trí xuất file lược đồ Room SQLite để kiểm tra tính toàn vẹn (Schema Validation)
    javaCompileOptions {
        annotationProcessorOptions {
            arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
        }
    }
}
```

#### Pattern B2: Cấu hình An ninh Mạng cho phép HTTP Nội bộ (`network_security_config.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Cấu hình An ninh Mạng (Network Security Config) của UTEChat.
    Mục đích: Cho phép gửi lưu lượng HTTP văn bản rõ (cleartext traffic) tới máy tính dev trong quá trình kiểm thử,
    vượt qua cơ chế chặn HTTP mặc định từ Android 9 (API 28+).
-->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <!-- Loopback của máy phát triển khi chạy qua Android Emulator -->
        <domain includeSubdomains="true">10.0.2.2</domain>
        <!-- Máy chủ nội bộ trên cùng thiết bị -->
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
    </domain-config>

    <!-- Cấu hình dự phòng hỗ trợ test trên điện thoại thật cắm cùng mạng Wi-Fi LAN nội bộ -->
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

---

### 3.3 Kiến trúc Single Activity & Điều hướng Động (`MainActivity.java`)
- **Điểm tương đồng (Analog):** [app/src/main/java/com/utechat/MainActivity.java](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/main/java/com/utechat/MainActivity.java).
- **Quy tắc chú thích giáo dục:** Giải thích cơ chế liên kết `NavigationUI.setupWithNavController`, cách khớp ID giữa Menu và Navigation Graph, và logic ẩn/hiện thanh `BottomNavigationView` dựa trên `destination.getId()`.

#### Pattern C1: Single Activity điều phối (`MainActivity.java`)
```java
package com.utechat;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import com.utechat.databinding.ActivityMainBinding;

/**
 * Activity duy nhất (Pure Single Activity) của toàn bộ ứng dụng UTEChat.
 * Quản lý vòng đời ứng dụng tập trung, chứa NavHostFragment và điều phối BottomNavigationView.
 */
public class MainActivity extends AppCompatActivity {

    // ViewBinding liên kết trực tiếp với layout activity_main.xml (không dùng findViewById)
    private ActivityMainBinding binding;
    // NavController quản lý ngăn xếp chuyển màn hình (Backstack) và các hành động điều hướng
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Nạp giao diện thông qua ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Tìm NavHostFragment làm khung chứa các màn hình Fragment
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();

            // Tự động kết nối BottomNavigationView với NavController (chuyển tab dựa trên ID trùng khớp)
            NavigationUI.setupWithNavController(binding.bottomNav, navController);

            // Đăng ký lắng nghe sự kiện chuyển đổi màn hình để tự động ẩn/hiện thanh điều hướng dưới
            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                int id = destination.getId();
                // Chỉ hiển thị BottomNav tại 3 tab chính cấp cao nhất của hệ thống
                if (id == R.id.chatListFragment || id == R.id.contactsListFragment || id == R.id.profileFragment) {
                    binding.bottomNav.setVisibility(View.VISIBLE);
                } else {
                    // Ẩn thanh BottomNav trên Splash, Đăng nhập, Đăng ký và các màn hình chi tiết
                    binding.bottomNav.setVisibility(View.GONE);
                }
            });
        }
    }
}
```

#### Pattern C2: Menu điều hướng dưới 3 Tab (`res/menu/bottom_nav_menu.xml`)
> [!IMPORTANT]
> ID của từng thẻ `<item>` **bắt buộc phải khớp chính xác** với ID của đồ thị con (`nav_chat`, `nav_contacts`, `nav_profile`) hoặc fragment đích để `NavigationUI` chuyển tab chuẩn.

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Menu điều hướng 3 tab chính: Tin nhắn (Chats), Danh bạ (Contacts), Cá nhân (Profile) -->
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/nav_chat"
        android:icon="@drawable/ic_tab_chat"
        android:title="@string/tab_chat" />
    <item
        android:id="@+id/nav_contacts"
        android:icon="@drawable/ic_tab_contacts"
        android:title="@string/tab_contacts" />
    <item
        android:id="@+id/nav_profile"
        android:icon="@drawable/ic_tab_profile"
        android:title="@string/tab_profile" />
</menu>
```

---

### 3.4 Kiến trúc Đồ thị Điều hướng Lồng Khóa Điểm Nóng 3 (`res/navigation/`)
- **Điểm tương đồng (Analog):** Greenfield (Chưa có thư mục `res/navigation`).
- **Quy tắc chú thích giáo dục:** Giải thích vai trò của thẻ `<include app:graph="...">`, thuộc tính `app:popUpTo` và `app:popUpToInclusive` để giải phóng màn hình chào (Splash) khỏi backstack.

#### Pattern D1: Đồ thị gốc `nav_graph.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Đồ thị điều hướng gốc (Root Navigation Graph) của ứng dụng UTEChat.
    [HOTSPOT 3] Áp dụng Nested Navigation Graphs: nhúng các đồ thị con độc lập qua thẻ <include>.
    Mỗi thành viên trong nhóm chỉ chỉnh sửa file đồ thị con của mình, tránh 100% xung đột merge XML.
-->
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_graph"
    app:startDestination="@id/splashFragment">

    <!-- Điểm bắt đầu của ứng dụng: Màn hình Splash kiểm tra kết nối hệ thống -->
    <fragment
        android:id="@+id/splashFragment"
        android:name="com.utechat.ui.splash.SplashFragment"
        android:label="Splash">
        <!-- Chuyển sang luồng xác thực (Auth) khi chưa đăng nhập, xóa Splash khỏi backstack -->
        <action
            android:id="@+id/action_splash_to_auth"
            app:destination="@id/nav_auth"
            app:popUpTo="@id/splashFragment"
            app:popUpToInclusive="true" />
        <!-- Chuyển thẳng vào luồng chat khi đã đăng nhập trước đó -->
        <action
            android:id="@+id/action_splash_to_main"
            app:destination="@id/nav_chat"
            app:popUpTo="@id/splashFragment"
            app:popUpToInclusive="true" />
    </fragment>

    <!-- Nhúng đồ thị Xác thực & Đăng nhập (Thành viên 2 - Phase 3) -->
    <include app:graph="@navigation/nav_auth" />

    <!-- Nhúng đồ thị Tin nhắn & Hội thoại (Thành viên 4 - Phase 5) -->
    <include app:graph="@navigation/nav_chat" />

    <!-- Nhúng đồ thị Danh bạ & Kết bạn (Thành viên 3 - Phase 4) -->
    <include app:graph="@navigation/nav_contacts" />

    <!-- Nhúng đồ thị Hồ sơ cá nhân (Thành viên 2 - Phase 3) -->
    <include app:graph="@navigation/nav_profile" />

</navigation>
```

#### Pattern D2: Đồ thị con Mẫu (`nav_auth.xml`, `nav_chat.xml`, `nav_contacts.xml`, `nav_profile.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Đồ thị điều hướng nhánh Chat: Quản lý danh sách hội thoại và chi tiết tin nhắn -->
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_chat"
    app:startDestination="@id/chatListFragment">

    <!-- Màn hình danh sách cuộc trò chuyện -->
    <fragment
        android:id="@+id/chatListFragment"
        android:name="com.utechat.ui.chat.ChatListFragment"
        android:label="Chats" />

</navigation>
```

---

### 3.5 Khóa Phân Vùng Chuỗi Đa Ngôn Ngữ Điểm Nóng 4 (`res/values/strings_*.xml`)
- **Điểm tương đồng (Analog):** [app/src/main/res/values/strings.xml](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/main/res/values/strings.xml).
- **Quy tắc chú thích giáo dục:** Giải thích cơ chế tự động gộp XML của Android Resource Compiler (AAPT2) và cấu trúc phân vùng chuỗi Anh-Việt chuẩn hóa.

#### Pattern E1: Phân vùng Chuỗi Tiếng Anh Mặc định (`res/values/strings_common.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Chuỗi dùng chung toàn ứng dụng (Tiếng Anh mặc định) -->
<resources>
    <string name="app_name">UTEChat</string>
    <string name="tab_chat">Chats</string>
    <string name="tab_contacts">Contacts</string>
    <string name="tab_profile">Profile</string>
    <string name="action_retry">Retry</string>
    <string name="action_offline_continue">Continue Offline</string>
    <string name="status_checking">Checking connection...</string>
    <string name="status_server_up">Server: UP</string>
    <string name="status_server_down">Server: DOWN</string>
    <string name="status_db_connected">Database: CONNECTED</string>
    <string name="status_db_disconnected">Database: DISCONNECTED</string>
</resources>
```

#### Pattern E2: Phân vùng Chuỗi Tiếng Việt Bản Địa Hóa (`res/values-vi/strings_common.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Chuỗi dùng chung toàn ứng dụng (Bản địa hóa Tiếng Việt khi thiết bị chuyển ngôn ngữ) -->
<resources>
    <string name="app_name">UTEChat</string>
    <string name="tab_chat">Tin nhắn</string>
    <string name="tab_contacts">Danh bạ</string>
    <string name="tab_profile">Cá nhân</string>
    <string name="action_retry">Thử lại</string>
    <string name="action_offline_continue">Tiếp tục chế độ Offline</string>
    <string name="status_checking">Đang kiểm tra kết nối hệ thống...</string>
    <string name="status_server_up">Máy chủ: HOẠT ĐỘNG</string>
    <string name="status_server_down">Máy chủ: LỖI KẾT NỐI</string>
    <string name="status_db_connected">Cơ sở dữ liệu: ĐÃ KẾT NỐI</string>
    <string name="status_db_disconnected">Cơ sở dữ liệu: MẤT KẾT NỐI</string>
</resources>
```

---

### 3.6 Đóng Băng Lược Đồ CSDL Room SQLite v1 Điểm Nóng 5 (`com.utechat.data.local.*`)
- **Điểm tương đồng (Analog):** [V1__initial_schema.sql](file:///c:/Users/Admin/Desktop/Project-Mobile/backend/src/main/resources/db/migration/V1__initial_schema.sql).
- **Quy tắc chú thích giáo dục:**
  - Giải thích ý nghĩa của từng annotation Room: `@Entity(tableName = "...")`, `@PrimaryKey`, `@NonNull`, `@ColumnInfo`, `@TypeConverters`.
  - Nêu rõ tại sao kiểu `Date` chuyển thành `Long` (timestamp mili-giây) và `UUID` chuyển thành `String` để SQLite lưu trữ được.
  - Phân tích cặn kẽ 13 Entity tương ứng với 13 bảng CSDL trong `docs/ERD.md`.

#### Pattern F1: Bộ chuyển đổi kiểu dữ liệu (`Converters.java`)
```java
package com.utechat.data.local;

import androidx.room.TypeConverter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Lớp chứa các bộ chuyển đổi kiểu dữ liệu (TypeConverter) cho Room SQLite.
 * SQLite chỉ lưu được kiểu nguyên thủy (TEXT, INTEGER, REAL, BLOB).
 * Các phương thức này chuyển đổi kiểu Java phức tạp (Date, UUID, List) thành kiểu lưu trữ được.
 */
public class Converters {

    private static final Gson gson = new Gson();

    // Chuyển đổi Date sang Long (mili-giây) để lưu vào cột INTEGER
    @TypeConverter
    public static Date fromTimestamp(Long value) {
        return value == null ? null : new Date(value);
    }

    // Chuyển đổi Long từ CSDL sang đối tượng Date trong Java
    @TypeConverter
    public static Long dateToTimestamp(Date date) {
        return date == null ? null : date.getTime();
    }

    // Chuyển đổi UUID sang String để lưu vào cột TEXT
    @TypeConverter
    public static String uuidToString(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    // Chuyển đổi chuỗi String từ CSDL thành UUID trong Java
    @TypeConverter
    public static UUID stringToUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    // Chuyển đổi danh sách List<String> thành chuỗi JSON
    @TypeConverter
    public static String listToString(List<String> list) {
        return list == null ? null : gson.toJson(list);
    }

    // Chuyển đổi chuỗi JSON từ CSDL thành danh sách List<String>
    @TypeConverter
    public static List<String> stringToList(String value) {
        if (value == null) return Collections.emptyList();
        Type listType = new TypeToken<List<String>>() {}.getType();
        return gson.fromJson(value, listType);
    }
}
```

#### Pattern F2: Room Entity Mẫu — Khóa Đơn và Khóa Phức Hợp (`UserEntity.java` & `ContactEntity.java`)
```java
package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.util.Date;

/**
 * Thực thể Room đại diện cho bảng 'users' trong SQLite.
 * Lưu trữ thông tin tài khoản, số điện thoại E.164 và quyền riêng tư nhận tin.
 */
@Entity(tableName = "users", indices = {@Index(value = "phone_e164", unique = true)})
public class UserEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    private String id; // Định danh tài khoản định dạng UUID

    @NonNull
    @ColumnInfo(name = "phone_e164")
    private String phoneE164; // Số điện thoại chuẩn quốc tế (ví dụ: +84901111111)

    @NonNull
    @ColumnInfo(name = "display_name")
    private String displayName; // Tên hiển thị người dùng

    @ColumnInfo(name = "avatar_url")
    private String avatarUrl; // Đường dẫn ảnh đại diện

    @NonNull
    @ColumnInfo(name = "password_hash")
    private String passwordHash; // Mật khẩu băm BCrypt

    @ColumnInfo(name = "allow_messages_from_non_contacts")
    private boolean allowMessagesFromNonContacts = true; // Cho phép nhận tin từ người lạ

    @NonNull
    @ColumnInfo(name = "status")
    private String status = "ACTIVE"; // Trạng thái tài khoản: ACTIVE, SUSPENDED, DELETED

    @ColumnInfo(name = "created_at")
    private Date createdAt; // Thời điểm tạo tài khoản

    // Constructor, Getters & Setters đầy đủ với chú thích rõ ràng
    public UserEntity(@NonNull String id, @NonNull String phoneE164, @NonNull String displayName,
                      @NonNull String passwordHash, boolean allowMessagesFromNonContacts,
                      @NonNull String status, Date createdAt) {
        this.id = id;
        this.phoneE164 = phoneE164;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.allowMessagesFromNonContacts = allowMessagesFromNonContacts;
        this.status = status;
        this.createdAt = createdAt;
    }

    @NonNull
    public String getId() { return id; }
    public void setId(@NonNull String id) { this.id = id; }

    @NonNull
    public String getPhoneE164() { return phoneE164; }
    public void setPhoneE164(@NonNull String phoneE164) { this.phoneE164 = phoneE164; }

    @NonNull
    public String getDisplayName() { return displayName; }
    public void setDisplayName(@NonNull String displayName) { this.displayName = displayName; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    @NonNull
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(@NonNull String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isAllowMessagesFromNonContacts() { return allowMessagesFromNonContacts; }
    public void setAllowMessagesFromNonContacts(boolean allowMessagesFromNonContacts) { this.allowMessagesFromNonContacts = allowMessagesFromNonContacts; }

    @NonNull
    public String getStatus() { return status; }
    public void setStatus(@NonNull String status) { this.status = status; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
```

```java
package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import java.util.Date;

/**
 * Thực thể Room đại diện cho bảng 'contacts' trong SQLite.
 * Sử dụng Khóa chính kết hợp (Composite Primary Key: owner_id + contact_user_id).
 * Quản lý cả quan hệ bạn bè (blocked = false) và danh sách chặn (blocked = true).
 */
@Entity(tableName = "contacts",
        primaryKeys = {"owner_id", "contact_user_id"},
        indices = {@Index(value = {"owner_id", "blocked"})})
public class ContactEntity {

    @NonNull
    @ColumnInfo(name = "owner_id")
    private String ownerId; // ID người sở hữu danh bạ

    @NonNull
    @ColumnInfo(name = "contact_user_id")
    private String contactUserId; // ID người bạn hoặc người bị chặn

    @ColumnInfo(name = "blocked")
    private boolean blocked; // true = Đã chặn; false = Bạn bè bình thường

    @ColumnInfo(name = "created_at")
    private Date createdAt; // Thời điểm kết bạn hoặc chặn

    public ContactEntity(@NonNull String ownerId, @NonNull String contactUserId, boolean blocked, Date createdAt) {
        this.ownerId = ownerId;
        this.contactUserId = contactUserId;
        this.blocked = blocked;
        this.createdAt = createdAt;
    }

    @NonNull
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(@NonNull String ownerId) { this.ownerId = ownerId; }

    @NonNull
    public String getContactUserId() { return contactUserId; }
    public void setContactUserId(@NonNull String contactUserId) { this.contactUserId = contactUserId; }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
```

#### Pattern F3: Quản lý Singleton CSDL Room v1 (`AppDatabase.java`)
```java
package com.utechat.data.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import com.utechat.data.local.dao.*;
import com.utechat.data.local.entity.*;

/**
 * Lớp CSDL Room chính (AppDatabase) phiên bản 1 của UTEChat.
 * [HOTSPOT 5] Khóa cứng toàn bộ 13 bảng CSDL để các Phase 3-6 phát triển song song không bị xung đột schema.
 */
@Database(
    entities = {
        UserEntity.class,
        UserDeviceEntity.class,
        OtpChallengeEntity.class,
        AuthSessionEntity.class,
        AuthThrottleEntity.class,
        ContactRequestEntity.class,
        ContactEntity.class,
        ConversationEntity.class,
        ConversationMemberEntity.class,
        MessageEntity.class,
        AttachmentEntity.class,
        CallEntity.class,
        SyncEventEntity.class
    },
    version = 1,
    exportSchema = true
)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "utechat.db";
    private static volatile AppDatabase INSTANCE;

    // Khai báo các DAO truy xuất dữ liệu cục bộ
    public abstract UserDao userDao();
    public abstract ContactDao contactDao();
    public abstract ConversationDao conversationDao();
    public abstract MessageDao messageDao();
    public abstract SyncEventDao syncEventDao();

    /**
     * Phương thức khởi tạo mẫu Singleton an toàn đa luồng (Double-checked locking).
     */
    public static AppDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            DATABASE_NAME
                    )
                    // Cho phép xóa và tạo lại bảng trong giai đoạn dev nếu có sai lệch schema nhẹ (ADR-020)
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
```

---

### 3.7 Kiến trúc Seam, Kho Dữ liệu Giả Lập & Service Locator Điểm Nóng 6
- **Điểm tương đồng (Analog):** [V2__seed_demo_data.sql](file:///c:/Users/Admin/Desktop/Project-Mobile/backend/src/main/resources/db/demo/V2__seed_demo_data.sql).
- **Quy tắc chú thích giáo dục:**
  - Giải thích kiến trúc Seam (đường nối trừu tượng qua Interface): Cho phép thay thế việc gọi backend thật bằng dữ liệu mẫu mà không làm thay đổi 1 dòng code nào của ViewModel hay Fragment.
  - Phân tích cặn kẽ 5 tài khoản demo: Alice (ID `1111...`), Bob (ID `2222...`), Charlie (ID `3333...`), David (ID `4444...`), Eve (ID `5555...`).
  - Hướng dẫn Service Locator (`Injection.java`) và `ViewModelFactory.java` theo giáo trình môn học.

#### Pattern G1: Định nghĩa Seam Interface (`AuthRepository.java`)
```java
package com.utechat.data.repository;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.entity.UserEntity;

/**
 * Seam Interface cho phân hệ Xác thực người dùng (Auth).
 * Đóng vai trò hợp đồng giao tiếp giữa ViewModel và tầng dữ liệu (Fake hoặc Real).
 */
public interface AuthRepository {
    // Thực hiện đăng nhập bằng số điện thoại và mật khẩu
    LiveData<UserEntity> login(String phoneE164, String password);
    // Lấy thông tin tài khoản hiện đang đăng nhập
    LiveData<UserEntity> getCurrentUser();
    // Đăng xuất và xóa phiên đăng nhập
    void logout();
}
```

#### Pattern G2: Kho Dữ liệu Giả Lập nạp sẵn Dữ liệu Mẫu Phase 1 (`FakeAuthRepository.java`)
```java
package com.utechat.data.repository.fake;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.AuthRepository;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Kho dữ liệu giả lập cho phân hệ Auth.
 * Nạp sẵn dữ liệu 5 tài khoản demo chuẩn theo tệp V2__seed_demo_data.sql:
 * 1. Alice Nguyễn (+84901111111)
 * 2. Bob Trần (+84902222222)
 * 3. Charlie Lê (+84903333333)
 * 4. David Phạm (+84904444444)
 * 5. Eve Hoàng (+84905555555)
 */
public class FakeAuthRepository implements AuthRepository {

    private final Map<String, UserEntity> demoUsers = new HashMap<>();
    private final MutableLiveData<UserEntity> currentUserLiveData = new MutableLiveData<>();

    public FakeAuthRepository() {
        seedDemoUsers();
    }

    private void seedDemoUsers() {
        // Nạp chính xác 5 tài khoản thử nghiệm từ Phase 1
        demoUsers.put("+84901111111", new UserEntity(
                "11111111-1111-1111-1111-111111111111", "+84901111111", "Alice Nguyễn", "hashed_pwd", true, "ACTIVE", new Date()));
        demoUsers.put("+84902222222", new UserEntity(
                "22222222-2222-2222-2222-222222222222", "+84902222222", "Bob Trần", "hashed_pwd", true, "ACTIVE", new Date()));
        demoUsers.put("+84903333333", new UserEntity(
                "33333333-3333-3333-3333-333333333333", "+84903333333", "Charlie Lê", "hashed_pwd", true, "ACTIVE", new Date()));
        demoUsers.put("+84904444444", new UserEntity(
                "44444444-4444-4444-4444-444444444444", "+84904444444", "David Phạm", "hashed_pwd", false, "ACTIVE", new Date()));
        demoUsers.put("+84905555555", new UserEntity(
                "55555555-5555-5555-5555-555555555555", "+84905555555", "Eve Hoàng", "hashed_pwd", true, "ACTIVE", new Date()));
    }

    @Override
    public LiveData<UserEntity> login(String phoneE164, String password) {
        MutableLiveData<UserEntity> result = new MutableLiveData<>();
        UserEntity user = demoUsers.get(phoneE164);
        if (user != null) {
            currentUserLiveData.setValue(user);
            result.setValue(user);
        } else {
            result.setValue(null); // Đăng nhập thất bại (số không tồn tại)
        }
        return result;
    }

    @Override
    public LiveData<UserEntity> getCurrentUser() {
        return currentUserLiveData;
    }

    @Override
    public void logout() {
        currentUserLiveData.setValue(null);
    }
}
```

#### Pattern G3: Service Locator Điều Phối Nguồn Dữ Liệu (`Injection.java`)
```java
package com.utechat.di;

import android.content.Context;
import com.utechat.BuildConfig;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.remote.api.ApiService;
import com.utechat.data.repository.*;
import com.utechat.data.repository.fake.*;
import com.utechat.data.repository.real.*;

/**
 * Service Locator (Bộ cấp phát phụ thuộc thủ công thuần Java).
 * Bám sát giáo trình Android Slide Session 4 (không dùng Dagger/Hilt hay Kapt).
 * Điều phối chuyển đổi giữa Fake Repositories và Real Repositories thông qua cờ USE_FAKE_DATA.
 */
public class Injection {

    // Singleton cache cho các repository
    private static AuthRepository authRepository;
    private static ContactsRepository contactsRepository;
    private static ChatRepository chatRepository;
    private static HealthRepository healthRepository;

    /**
     * Cung cấp HealthRepository phục vụ đầu dò kiểm tra hệ thống Walking Skeleton.
     */
    public static HealthRepository provideHealthRepository() {
        if (healthRepository == null) {
            ApiService apiService = ApiClient.getRetrofit().create(ApiService.class);
            healthRepository = new HealthRepository(apiService);
        }
        return healthRepository;
    }

    /**
     * Cung cấp AuthRepository:
     * - Khi BuildConfig.USE_FAKE_DATA = true -> Trả về FakeAuthRepository
     * - Khi BuildConfig.USE_FAKE_DATA = false -> Trả về RealAuthRepository
     */
    public static AuthRepository provideAuthRepository(Context context) {
        if (authRepository == null) {
            if (BuildConfig.USE_FAKE_DATA) {
                authRepository = new FakeAuthRepository();
            } else {
                AppDatabase db = AppDatabase.getInstance(context);
                ApiService api = ApiClient.getRetrofit().create(ApiService.class);
                authRepository = new RealAuthRepository(db.userDao(), api);
            }
        }
        return authRepository;
    }

    public static ContactsRepository provideContactsRepository(Context context) {
        if (contactsRepository == null) {
            if (BuildConfig.USE_FAKE_DATA) {
                contactsRepository = new FakeContactsRepository();
            } else {
                AppDatabase db = AppDatabase.getInstance(context);
                ApiService api = ApiClient.getRetrofit().create(ApiService.class);
                contactsRepository = new RealContactsRepository(db.contactDao(), api);
            }
        }
        return contactsRepository;
    }

    public static ChatRepository provideChatRepository(Context context) {
        if (chatRepository == null) {
            if (BuildConfig.USE_FAKE_DATA) {
                chatRepository = new FakeChatRepository();
            } else {
                AppDatabase db = AppDatabase.getInstance(context);
                ApiService api = ApiClient.getRetrofit().create(ApiService.class);
                chatRepository = new RealChatRepository(db.conversationDao(), db.messageDao(), api);
            }
        }
        return chatRepository;
    }
}
```

#### Pattern G4: ViewModel Factory Chuẩn Bài Giảng (`ViewModelFactory.java`)
```java
package com.utechat.di;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.utechat.ui.splash.HealthViewModel;

/**
 * Lớp khởi tạo ViewModel có tham số (ViewModelProvider.Factory).
 * Tuân thủ chính xác chuẩn cấu trúc slide bài giảng môn Lập trình Thiết bị Di động.
 */
public class ViewModelFactory implements ViewModelProvider.Factory {

    private final Context context;

    public ViewModelFactory(Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        // Cấp phát HealthViewModel kèm HealthRepository được inject từ Service Locator
        if (modelClass.isAssignableFrom(HealthViewModel.class)) {
            return (T) new HealthViewModel(Injection.provideHealthRepository());
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
```

---

### 3.8 Đầu Dò Walking Skeleton End-to-End (`SplashFragment.java` & `HealthViewModel.java`)
- **Điểm tương đồng (Analog):** Greenfield.
- **Quy tắc chú thích giáo dục:**
  - Giải thích mô hình MVVM (Model - View - ViewModel) và cơ chế LiveData quan sát trạng thái bất đồng bộ.
  - Chú thích rõ ràng vòng đời Fragment (`onCreateView`, `onViewCreated`, `onDestroyView`) và cách giải phóng ViewBinding (`binding = null`) để tránh rò rỉ bộ nhớ (Memory Leak).
  - Giải thích tính năng tự động chuyển màn hình (`postDelayed`) và các phương án dự phòng khi mất kết nối mạng.

#### Pattern H1: ViewModel Quản Lý Trạng Thái Probe (`HealthViewModel.java`)
```java
package com.utechat.ui.splash;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.utechat.data.remote.model.HealthDto;
import com.utechat.data.repository.HealthRepository;

/**
 * ViewModel chịu trách nhiệm quản lý trạng thái kiểm tra kết nối hệ thống (Walking Skeleton Probe).
 * Tách biệt hoàn toàn logic gọi mạng ra khỏi giao diện SplashFragment.
 */
public class HealthViewModel extends ViewModel {

    // Enum biểu thị các trạng thái khả dĩ của màn hình Splash
    public enum ProbeStatus {
        LOADING, SUCCESS, ERROR
    }

    private final HealthRepository healthRepository;
    private final MutableLiveData<ProbeStatus> statusLiveData = new MutableLiveData<>(ProbeStatus.LOADING);
    private final MutableLiveData<HealthDto> healthDataLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> errorMessageLiveData = new MutableLiveData<>();

    public HealthViewModel(HealthRepository healthRepository) {
        this.healthRepository = healthRepository;
        checkHealth();
    }

    public LiveData<ProbeStatus> getStatus() { return statusLiveData; }
    public LiveData<HealthDto> getHealthData() { return healthDataLiveData; }
    public LiveData<String> getErrorMessage() { return errorMessageLiveData; }

    /**
     * Kích hoạt gọi API kiểm tra máy chủ backend và CSDL Supabase.
     */
    public void checkHealth() {
        statusLiveData.setValue(ProbeStatus.LOADING);
        healthRepository.checkHealth(new HealthRepository.HealthCallback() {
            @Override
            public void onSuccess(HealthDto healthDto) {
                healthDataLiveData.postValue(healthDto);
                statusLiveData.postValue(ProbeStatus.SUCCESS);
            }

            @Override
            public void onError(String message) {
                errorMessageLiveData.postValue(message);
                statusLiveData.postValue(ProbeStatus.ERROR);
            }
        });
    }

    /**
     * Cho phép người dùng bấm "Thử lại" khi kiểm tra kết nối thất bại.
     */
    public void retry() {
        checkHealth();
    }
}
```

#### Pattern H2: Fragment Màn Hình Chào & Đầu Dò Xác Minh (`SplashFragment.java`)
```java
package com.utechat.ui.splash;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import com.utechat.R;
import com.utechat.databinding.FragmentSplashBinding;
import com.utechat.di.ViewModelFactory;

/**
 * Màn hình Splash kiêm Đầu dò Walking Skeleton End-to-End.
 * Hiển thị huy hiệu trạng thái kết nối máy chủ và CSDL, cung cấp nút Thử lại hoặc Bỏ qua Offline.
 */
public class SplashFragment extends Fragment {

    private FragmentSplashBinding binding;
    private HealthViewModel viewModel;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Nạp layout fragment_splash.xml qua ViewBinding
        binding = FragmentSplashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Khởi tạo ViewModel qua ViewModelFactory
        ViewModelFactory factory = new ViewModelFactory(requireContext());
        viewModel = new ViewModelProvider(this, factory).get(HealthViewModel.class);

        setupObservers();
        setupListeners();
    }

    private void setupObservers() {
        // Quan sát trạng thái kết nối từ ViewModel
        viewModel.getStatus().observe(getViewLifecycleOwner(), status -> {
            if (status == HealthViewModel.ProbeStatus.LOADING) {
                binding.progressBar.setVisibility(View.VISIBLE);
                binding.layoutBadges.setVisibility(View.GONE);
                binding.layoutRetry.setVisibility(View.GONE);
                binding.tvStatus.setText(R.string.status_checking);
            } else if (status == HealthViewModel.ProbeStatus.SUCCESS) {
                binding.progressBar.setVisibility(View.GONE);
                binding.layoutBadges.setVisibility(View.VISIBLE);
                binding.layoutRetry.setVisibility(View.GONE);
                binding.badgeServer.setText(R.string.status_server_up);
                binding.badgeDb.setText(R.string.status_db_connected);

                // Chờ 1.5 giây để người dùng nhận diện rồi tự động chuyển sang luồng Auth
                handler.postDelayed(() -> {
                    if (isAdded()) {
                        Navigation.findNavController(requireView()).navigate(R.id.action_splash_to_auth);
                    }
                }, 1500);
            } else if (status == HealthViewModel.ProbeStatus.ERROR) {
                binding.progressBar.setVisibility(View.GONE);
                binding.layoutBadges.setVisibility(View.VISIBLE);
                binding.layoutRetry.setVisibility(View.VISIBLE);
                binding.badgeServer.setText(R.string.status_server_down);
                binding.badgeDb.setText(R.string.status_db_disconnected);
            }
        });
    }

    private void setupListeners() {
        // Xử lý sự kiện khi bấm nút Thử lại
        binding.btnRetry.setOnClickListener(v -> viewModel.retry());

        // Cho phép tiếp tục chế độ Offline (vào thẳng Auth) ngay cả khi không bật backend
        binding.btnContinueOffline.setOnClickListener(v -> {
            Navigation.findNavController(requireView()).navigate(R.id.action_splash_to_auth);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Hủy liên kết ViewBinding khi view bị hủy để ngăn ngừa rò rỉ bộ nhớ (Memory Leak)
        binding = null;
        handler.removeCallbacksAndMessages(null);
    }
}
```

---

### 3.9 Mẫu Kiểm Thử Tự Động (Unit Testing Patterns)
- **Điểm tương đồng (Analog):** [ExampleUnitTest.java](file:///c:/Users/Admin/Desktop/Project-Mobile/app/src/test/java/com/utechat/ExampleUnitTest.java).
- **Quy tắc chú thích giáo dục:** Giải thích vì sao cần `InstantTaskExecutorRule` khi kiểm thử `LiveData`, cách kiểm tra kết quả `assertNotNull` và `assertEquals`.

#### Pattern I1: Kiểm thử Backend Spring Boot (`HealthControllerTest.java`)
```java
package com.utechat.backend;

import com.utechat.backend.controller.HealthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử tự động cho HealthController bằng MockMvc.
 * Mục đích giáo dục: Đảm bảo endpoint /api/health trả về đúng mã HTTP 200 và cấu trúc JSON chuẩn.
 */
@WebMvcTest(HealthController.class)
public class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("GET /api/health thành công khi CSDL Supabase phản hồi SELECT 1 = 1")
    public void testHealthCheckSuccess() throws Exception {
        // Giả lập kết quả truy vấn CSDL trả về 1 (kết nối thành công)
        Mockito.when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("CONNECTED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
```

#### Pattern I2: Kiểm thử Android ViewModel LiveData (`HealthViewModelTest.java`)
```java
package com.utechat;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.utechat.data.remote.model.HealthDto;
import com.utechat.data.repository.HealthRepository;
import com.utechat.ui.splash.HealthViewModel;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * Kiểm thử đơn vị cho HealthViewModel.
 * InstantTaskExecutorRule ép LiveData thực thi ngay trên luồng kiểm thử thay vì background loopers.
 */
public class HealthViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private HealthRepository healthRepository;

    @Captor
    private ArgumentCaptor<HealthRepository.HealthCallback> callbackCaptor;

    private HealthViewModel viewModel;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        viewModel = new HealthViewModel(healthRepository);
    }

    @Test
    public void testCheckHealth_whenSuccess_updatesLiveDataToSuccess() {
        // Bắt lấy callback được chuyển vào repository
        verify(healthRepository).checkHealth(callbackCaptor.capture());

        HealthDto fakeDto = new HealthDto("UP", "CONNECTED", "2026-11-11T08:00:00Z");
        callbackCaptor.getValue().onSuccess(fakeDto);

        // Kiểm tra LiveData chuyển sang trạng thái SUCCESS
        assertEquals(HealthViewModel.ProbeStatus.SUCCESS, viewModel.getStatus().getValue());
        assertEquals("CONNECTED", viewModel.getHealthData().getValue().getDatabase());
    }
}
```

---

## 4. Những Điều Cần Tránh Tuyệt Đối (Anti-Pattern Warnings)

1. **Tuyệt đối không dùng Kotlin / Coroutines / Flow / Jetpack Compose / Dagger-Hilt:**
   - Dự án bị ràng buộc 100% bằng Java 17, XML Layouts, MVVM LiveData, Room SQLite thuần, và Service Locator thủ công.
2. **Tuyệt đối không viết mã nguồn "chay" thiếu chú thích Tiếng Việt:**
   - Mỗi file Java, XML, SQL sinh ra phải tuân thủ nghiêm ngặt quy định chú thích giáo dục theo [AGENTS.md](file:///c:/Users/Admin/Desktop/Project-Mobile/AGENTS.md). Sinh viên phải bảo vệ đồ án dựa trên các dòng chú thích này.
3. **Tuyệt đối không dùng CIDR trong Network Security Config:**
   - Thẻ `<domain>192.168.0.0/16</domain>` sẽ gây lỗi biên dịch hoặc lỗi parser khi chạy Android. Hãy dùng tên miền cụ thể hoặc cấu hình `<base-config>`.
4. **Tuyệt đối không thiếu `prepareThreshold=0` trong kết nối Supabase Pooler:**
   - Cổng 6543 là PgBouncer ở chế độ Transaction; thiếu `prepareThreshold=0` sẽ dẫn đến lỗi nghiêm trọng `prepared statement "S_1" does not exist`.
5. **Tuyệt đối không lệch ID giữa Bottom Navigation Menu và Navigation Graphs:**
   - ID trong `bottom_nav_menu.xml` phải trùng 100% với `@id/nav_chat`, `@id/nav_contacts`, `@id/nav_profile` để `NavigationUI` tự động xử lý tab chuyển động mạch lạc và lưu giữ backstack.

---

## 5. Kết luận & Chuyển Giao Cho Bộ Lập Kế Hoạch (Hand-off to Planner)

Tài liệu `02-PATTERNS.md` này đã:
1. Xác định danh mục toàn diện các tệp cần tạo mới / chỉnh sửa cho Phase 2.
2. Cung cấp đầy đủ các mẫu chuẩn tắc (canonical blueprints) cho cả phần Backend Spring Boot 3 và Android Client Java 17.
3. Nhúng chặt chẽ tiêu chuẩn chú thích Tiếng Việt cho từng tầng kỹ thuật.

Bộ lập kế hoạch (`/gsd-plan-phase`) có thể sử dụng trực tiếp các mẫu trên để tạo các tệp kế hoạch `02-01-PLAN.md`, `02-02-PLAN.md`, `02-03-PLAN.md`, `02-04-PLAN.md`.

## PATTERN MAPPING COMPLETE
