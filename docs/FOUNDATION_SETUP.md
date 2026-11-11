# UTEChat — Chạy nền tảng Phase 2

## Backend

Dùng JDK17 trở lên; Maven biên dịch bytecode Java17. Wrapper pin Maven3.9.11.
Từ root repository, tạo .env theo SUPABASE_SETUP.md, rồi:

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Backend tự tìm .env ở thư mục chạy hoặc thư mục cha, không ghi secret vào source.
Biến môi trường hệ điều hành và tham số dòng lệnh có ưu tiên cao hơn .env.
Các tham chiếu ${DB_HOST}, ${DB_USER} trong .env được Spring resolve khi đọc cấu hình.

| Endpoint | Ý nghĩa |
|---|---|
| GET http://localhost:8080/api/health | Server sống, không truy vấn CSDL |
| GET http://localhost:8080/api/health/ready | SELECT1 thật qua JDBC, UP/CONNECTED hoặc HTTP503 |

Readiness không trả exception message. CORS wildcard chỉ bật ở profile dev, không cookie credentials.
Pool Hikari tối đa5, transaction6543, sslmode=require và prepareThreshold=0 lấy từ .env.
Backend không tự chạy migration trên6543; dùng hướng dẫn Flyway session5432 của Phase1.

## Android

Java17, minSdk26, compile/target35, package com.utechat, XML Views/ViewBinding.
AGP và Gradle Wrapper hiện có được giữ nguyên; Gradle daemon có thể cần JDK mới hơn Java source.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

Emulator dùng URL mặc định http://10.0.2.2:8080/. Chạy backend trước khi mở app.
Điện thoại thật cùng LAN cần IP máy chạy backend:

```powershell
.\gradlew.bat "-PbackendBaseUrl=http://192.168.1.10:8080/" installDebug
```

URL phải kết thúc bằng dấu /. Debug cho phép HTTP để thử emulator/LAN.
Release chặn cleartext và tắt Fake; khi triển khai thật phải cấu hình backendBaseUrl HTTPS.
Quy tắc bắt buộc cho các phase tiếp theo: [DEPLOYMENT_SECURITY.md](DEPLOYMENT_SECURITY.md). Hiện chưa có gate build kiểm URL HTTPS hoặc cấu hình prod/Nginx/chứng chỉ; assembleRelease thành công chưa chứng minh bản hosted hoạt động.
Log HTTP Debug chỉ ở BASIC, không ghi body/header xác thực.

## Luồng kiểm tra

SplashFragment → HealthViewModel → HealthRepository → Retrofit → /api/health/ready → Supabase.
HealthRepository luôn gọi thật, không phụ thuộc USE_FAKE_DATA.

- Kết nối tốt: hiện trạng thái bằng chữ/màu, chờ1.5 giây rồi sang Auth.
- Lỗi mạng: hiện Thử lại và Tiếp tục ngoại tuyến.
- Server còn sống nhưng DB lỗi: tách rõ máy chủ đã kết nối và dữ liệu chưa sẵn sàng.
- Xoay màn hình: ViewModel giữ request; callback cũ không ghi đè retry mới.
- Rời màn hình: observer/Binding/Handler được giải phóng đúng vòng đời.

Auth ở Phase2 là màn hình khung. Nút Xem giao diện mẫu chỉ có ở Debug và mở ba tab.
Nút này không xác thực người dùng hoặc cấp JWT. Đăng nhập thật thuộc Phase3.

## Room và ranh giới các module

Người dùng đã xác nhận dùng **10 bảng Room trong ERD mục4**, không sao chép13 bảng server:

user_cache, contact_request, contact, conversation, member, message,
attachment_transfer, message_fts, message_translation, sync_state.

Không lưu password_hash, OTP, auth_sessions hay auth_throttles trên Android.
message có localId tự tăng, serverId nullable và clientId unique; ACK giữ localId cũ.
FTS4 được Room đồng bộ từ message.text và normalized_text; chuẩn hóa riêng đ/Đ và dấu Unicode.
Cursor sync_state chỉ tăng trong transaction và tách theo tài khoản.
Mọi DAO đồng bộ phải chạy trên executor ở repository; không bật allowMainThreadQueries.

AppDatabase version1 xuất schema tại app/schemas. Destructive migration chỉ bật ở Debug.
Release phải có migration khi nâng version. Khi triển khai đăng xuất/đổi tài khoản ở Phase3,
cần xóa cache/hàng đợi của tài khoản cũ trước khi mở cache phiên mới.

| Điểm nóng | Chủ sở hữu |
|---|---|
| app/build.gradle.kts, gradle/libs.versions.toml, AppDatabase, Manifest | Nhóm trưởng |
| nav_auth, strings_auth, AuthApi, AuthRepository | Nhóm Auth |
| nav_contacts, strings_contacts, ContactApi, ContactsRepository | Nhóm Contacts |
| nav_chat, strings_chat, ChatApi, ChatRepository | Nhóm Chat |
| nav_profile, strings_profile | Nhóm Auth/Profile |
| MediaApi và màn hình media/call sau này | Nhóm Media |

Injection cung cấp singleton repository; USE_FAKE_DATA=true chỉ ở Debug.
Fake dùng5 hồ sơ công khai và quan hệ đúng seed, hoàn toàn trong bộ nhớ.
RealContacts/RealChat hiện chỉ đọc Room. Login/gửi tin Real từ chối rõ ràng tới phase nghiệp vụ,
không trả thành công giả. Các endpoint Auth/Contacts/Chat/Media được thêm vào interface riêng.

## Kiểm thử

- Backend MockMvc: liveness không gọi DB; readiness thành công/thất bại/null; không lộ exception.
- JVM Android: converters, dấu tiếng Việt, Fake, retry/stale callback của HealthViewModel.
- Android instrumentation: SQLite/FTS thật, ACK không trùng, cursor không lùi và schema không credential.
- Giao diện thật: kiểm probe, tab, xoay màn hình, lỗi mạng và DayNight trên emulator.

Xem báo cáo kiểm định trong .planning/phases/02-foundation-anti-conflict-architecture.

## Lưu ý build trên Windows

Trong môi trường kiểm thử này, Gradle đôi lúc không xóa được output cũ (`AccessDeniedException`).
Nếu gặp lỗi này, dừng Gradle daemon và dùng PowerShell xóa đúng thư mục `app/build` của repo, rồi build lại.
Đây là output sinh tự động; không xóa `app/schemas`, source hoặc dữ liệu cấu hình.
AndroidX Test JUnit 1.3.0 / Espresso 3.7.0 được dùng để chạy được trên emulator API 37 có sẵn;
compileSdk/targetSdk của ứng dụng vẫn là 35. Chưa kiểm thử trên thiết bị thật hoặc API 26.
