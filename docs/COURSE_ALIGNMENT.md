# UTEChat — Đối chiếu thiết kế với tài liệu giảng dạy

> **Trạng thái:** DRAFT 2026-11-09, cập nhật chuẩn hóa V1 2026-11-10. Nguồn đối chiếu: 7 file slide `session-01.pdf` đến `session-07.pdf` của giảng viên và `rubric_vn.pdf`.
> **Nguyên tắc:** phần **app Android** bám đúng công nghệ và mẫu code mà slide dạy. Những gì slide **không dạy** (WebRTC, ML Kit, coturn) là **phần mở rộng sáng tạo V1**, nhưng phải làm theo **cùng mẫu kiến trúc** của slide (MVVM, LiveData, Executor, Repository, Room, WorkManager). Phần **server** slide không quy định công nghệ (chọn Spring Boot 3 + Supabase PostgreSQL 13 bảng V1). Các tính năng khác (2FA TOTP, bot AI, bước nhận diện bạn bè) là định hướng tương lai V2.
> Ký hiệu cột Kết luận: **Khớp** = giống slide. **Điều chỉnh** = đã sửa thiết kế cho khớp slide. **Mở rộng** = ngoài slide, giữ mẫu của slide.

## 1. Ma trận đối chiếu

### 1.1 Nền tảng và giao diện (Session 1, 2, 3)

| Chủ đề | Slide dạy | Thiết kế của nhóm | Kết luận |
|---|---|---|---|
| Ngôn ngữ | Java (Kotlin chỉ là tuỳ chọn) | Java (package `com.utechat`) | Khớp |
| Giao diện | Views + XML, **View Binding**, không `findViewById` | Views + XML, View Binding | Khớp |
| Mức SDK | `minSdk 26`, `compileSdk 36`, `targetSdk 36` | Dự án chốt `minSdk 26`, `compileSdk 35`, `targetSdk 35` (ADR-023) | Điều chỉnh: dự án chốt SDK 35 để ổn định và phù hợp môi trường hiện tại |
| Build | Gradle **Kotlin DSL** + `gradle/libs.versions.toml` | Ghi vào ADR-023 | Điều chỉnh |
| Bố cục | `ConstraintLayout` gốc, giữ phẳng, `0dp` với ràng buộc | Dùng ConstraintLayout | Khớp |
| Theme | `Theme.Material3.DayNight.NoActionBar`, màu qua thuộc tính theme, `values-night` | ADR-021 | Khớp |
| Màn hình | **Một Activity + NavHostFragment**, Navigation, **Safe Args chỉ truyền id** | ADR-023 | Điều chỉnh |
| Fragment | View Binding gán trong `onViewCreated`, gán `null` trong `onDestroyView`, quan sát bằng `getViewLifecycleOwner()` | ADR-023 | Điều chỉnh |
| Danh sách | `RecyclerView` + `ListAdapter` + `DiffUtil` (`areItemsTheSame` theo id), `submitList`, không `notifyDataSetChanged` | Danh sách hội thoại, tin nhắn, danh bạ | Khớp |
| Material 3 | Card, Snackbar, dialog, bottom navigation | Dùng | Khớp |
| Tài nguyên | Chuỗi trong `values` và `values-vi`, có dấu, không viết cứng, dimen/colour trong tài nguyên | ADR-021 | Khớp |
| Truy cập | Tương phản ≥ 4.5:1, nút chạm ≥ 48dp, `contentDescription`, đơn vị `sp` | ADR-021 | Khớp |
| Vòng đời, xoay | Activity bị tạo lại, lưu trạng thái nhỏ bằng `onSaveInstanceState` hoặc `SavedStateHandle` | ADR-021 | Khớp |
| Quy ước | Một `TAG` cho mỗi lớp, tài nguyên `snake_case`, đặt tên Java chuẩn | ADR-023 | Điều chỉnh |

### 1.2 Kiến trúc và dữ liệu (Session 4)

| Chủ đề | Slide dạy | Thiết kế của nhóm | Kết luận |
|---|---|---|---|
| Kiến trúc | Tầng UI (Fragment, ViewModel) và tầng dữ liệu (Repository). Mũi tên chỉ xuống. **Không có tầng domain** | ADR-023 | Điều chỉnh |
| ViewModel | `ViewModel`, `LiveData`, `MediatorLiveData`, `SavedStateHandle`, **lớp trạng thái UI bất biến** (`withX`) | ADR-023 | Điều chỉnh |
| Bất đồng bộ | `AppExecutors` (một luồng đĩa), `Executor`, `postValue`. **Không dùng coroutine hay Flow** | ADR-023 | Điều chỉnh |
| Khởi tạo đối tượng | `Application` dựng sẵn (`ChatApp`, `AppContainer`), `ViewModelProvider.Factory` | ADR-023 | Điều chỉnh |
| Một nguồn sự thật | Repository, UI chỉ quan sát Room | ADR-009 | Khớp |
| Room | `@Entity`, `@Dao`, truy vấn đọc trả `LiveData`, `@TypeConverter` cho `Instant`, `exportSchema = true`, migration, **không truy cập trên luồng chính** | ERD.md mục 4, ADR-020 (chỉ bật destructive migration ở debug) | Khớp |
| Cài đặt người dùng | **SharedPreferences** (khoá `apply()`) | Cài đặt ngôn ngữ, theme, bật tắt thông báo | Khớp |
| Cấm | Fragment/Activity không được chạm DAO hay database | Kiểm tra khi rà soát chéo | Khớp |

### 1.3 Mạng, thời gian thực, nền (Session 5)

| Chủ đề | Slide dạy | Thiết kế của nhóm | Kết luận |
|---|---|---|---|
| HTTP | **Retrofit + OkHttp + Gson** (DTO phản chiếu JSON, tách khỏi entity Room, có mapper) | ADR-023 | Điều chỉnh |
| Gọi API | Giao diện `ChatApi` trả `Call<T>`, đường dẫn tương đối, `@Body`, `@Path`, `@Query` | API_CONTRACT.md | Khớp |
| Tiền tố API | Đường dẫn dạng `api/auth/login`, `api/conversations/{id}/messages` | Bỏ `/v1`, dùng `/api/...` | Điều chỉnh |
| Lỗi | `ApiCall.run` trả `ApiResult` (không ném), `ErrorMessages` ánh xạ lỗi sang chuỗi (400, 401, 403, 404, 409, `IOException`) | ADR-023, API_CONTRACT.md mục 1 | Điều chỉnh |
| Interceptor | `AuthInterceptor` thêm `Authorization: Bearer`, logging chỉ ở debug, timeouts (connect 15 s, read 30 s, write 60 s) | ADR-023 | Điều chỉnh |
| Địa chỉ server | `BuildConfig.BASE_URL` (debug: `http://10.0.2.2:8080/`) | ADR-023 | Khớp |
| HTTP không mã hoá | `network_security_config.xml` chỉ cho `10.0.2.2`, `localhost`, `127.0.0.1`, đặt ở **debug source set** | ADR-023 | Khớp |
| Offline-first | `observeMessages(id)` trả `LiveData`, `refreshLatest`, `loadOlder` (**phân trang theo `before`**, `PAGE_SIZE = 50`), `upsertByClientId` | ADR-009, F-10, F-14 | Khớp |
| Trạng thái | `UiState` duy nhất, không che danh sách khi có lỗi (có cache thì giữ danh sách và hiện Snackbar) | F-23 | Khớp |
| WebSocket | **OkHttp WebSocket** dùng cùng `OkHttpClient`, khung JSON thuần có trường `type` (không dùng STOMP), **nối lại 1, 2, 4… tối đa 30 giây**, dừng nối lại khi bị từ chối token, **bắt kịp bằng REST sau khi nối lại qua `GET /api/sync?after=<syncEventId>`**, chống trùng bằng `clientId` | ADR-008, F-14 | Khớp |
| Hàng đợi gửi | `SendMessageWorker` chỉ nhận `clientId`, trả `success` hoặc `retry`, `MAX_ATTEMPTS = 3`, **một chuỗi duy nhất tên `send-messages`**, `ExistingWorkPolicy.APPEND_OR_REPLACE`, ràng buộc `CONNECTED`, backoff lũy tiến 10 giây | ADR-009, F-11 | Điều chỉnh |
| Đồng bộ định kỳ | `PeriodicWorkRequest` 15 phút, `enqueueUniquePeriodicWork` với `KEEP`, gọi `/api/sync?after=<syncEventId>` | ADR-012 | Khớp |
| Thông báo | `NotificationChannel` `messages` tạo một lần lúc khởi động, quyền `POST_NOTIFICATIONS` (API 33+), `NotificationCompat`, `NavDeepLinkBuilder`, `FLAG_IMMUTABLE`, **bị từ chối thì app vẫn chạy** | F-19 | Khớp |
| Dịch vụ nền | Foreground service, receiver; mặc định dùng WorkManager cho việc "phải làm" | F-19, F-21 | Khớp |

### 1.4 Thiết bị và bảo mật (Session 6)

| Chủ đề | Slide dạy | Thiết kế của nhóm | Kết luận |
|---|---|---|---|
| Quyền chạy lúc dùng | Lớp `PermissionRequester` dùng `registerForActivityResult` **khai báo như một trường**, có giải thích, từ chối hai lần thì mở Settings, **tính năng vẫn dùng được khi từ chối** | F-18, F-19, F-21 | Khớp |
| Ảnh | `PickVisualMedia` (photo picker, không cần quyền), `TakePicture` với `FileProvider` | F-18 | Khớp |
| Voice | `MediaRecorder` (nguồn MIC, MPEG_4, AAC, tệp `.m4a`), bỏ bản quá ngắn, **luôn `release()`**; phát bằng `MediaPlayer` | F-18 | Khớp |
| Video | `Media3 ExoPlayer` + `PlayerView`, giữ trạng thái qua xoay bằng ViewModel; mở ngoài app bằng `FileProvider` | F-18, ADR-011 | Khớp |
| Vị trí | `FusedLocationProviderClient.getCurrentLocation`, ưu tiên `BALANCED_POWER_ACCURACY`, **kiểm tra `null`**, huỷ ở `onDestroyView` | F-18 | Khớp |
| Bản đồ | `play-services-maps`, `SupportMapFragment`, marker, **khoá Maps API lấy từ `local.properties` qua `manifestPlaceholders`, không commit** | F-18 | Điều chỉnh |
| Token | **Keystore AES-256-GCM**, lưu `Base64(IV + ciphertext)` trong SharedPreferences, **giữ token trong bộ nhớ sau lần giải mã đầu** | ADR-018 | Khớp |
| Xác thực | Đăng nhập một lần, server trả **token đã ký**, gửi `Bearer` ở mọi yêu cầu, **401 thì xoá token và về màn hình đăng nhập** (`SessionEvents`) | ADR-018, F-07 | Điều chỉnh |
| Bảo mật bản dựng | `allowBackup="false"`, không ghi token vào log, R8 **không** che được khoá API trong APK, khoá Maps giới hạn theo package và SHA-1 | ADR-024 | Điều chỉnh |
| Danh sách OWASP | M1, M3, M5, M7, M8, M9, M10 và các nhóm MASVS | ADR-024 | Điều chỉnh |

### 1.5 Kiểm thử và phát hành (Session 7)

| Chủ đề | Slide dạy | Thiết kế của nhóm | Kết luận |
|---|---|---|---|
| Kim tự tháp test | Nhiều test JUnit chạy trên JVM, một ít test tích hợp, ít test Espresso | ADR-024 | Điều chỉnh |
| Test double | Ưu tiên **Fake** cho repository, **Mockito** để kiểm tra tương tác, `InstantTaskExecutorRule` cho LiveData, `Runnable::run` làm executor | ADR-024 | Điều chỉnh |
| DAO | Test **Room in-memory** (`allowMainThreadQueries`) trong `androidTest` | ADR-024 | Điều chỉnh |
| UI | **Espresso**, `ActivityScenarioRule`, `RecyclerViewActions`, dữ liệu cục bộ biết trước, **không `Thread.sleep`** | ADR-024 | Điều chỉnh |
| Chất lượng | Android **Lint**, `StrictMode` và LeakCanary chỉ ở debug | ADR-024 | Điều chỉnh |
| Phát hành | `minifyEnabled` + R8, kiểm thử bản release, ký bằng upload keystore (mật khẩu ở `~/.gradle/gradle.properties`, **không đưa vào Git**), APK và AAB, `versionCode` tăng dần | ADR-024 | Điều chỉnh |

### 1.6 Yêu cầu đồ án (Session 1, 5, 6, 7 và rubric)

| Yêu cầu | Nguồn | Thiết kế |
|---|---|---|
| Chat thời gian thực bằng **WebSocket**, **hàng đợi offline**, nối lại, bắt kịp qua `sync_events`, chống trùng | Session 5 (khởi động đồ án) | Khớp |
| Tin **văn bản, ảnh, voice** theo thời gian thực (cộng video file, file tài liệu, vị trí theo rubric) | Session 5 và rubric | Khớp |
| **Demo hai thiết bị gửi nhận tin trên máy chiếu** | Session 5, 7 | ADR-019 |
| **Mỗi thành viên viết một phần thật sự của app Android** | Session 5 | **Phải giữ khi chia việc**: không có người chỉ làm server hay chỉ làm báo cáo |
| Một repo chung, thay đổi qua **pull request được rà soát** | Session 5 | ADR-022 |
| "Hợp đồng API" chốt sớm | Session 6 | `API_CONTRACT.md` |
| Zip gồm mã nguồn app và server có `.git`, báo cáo Word, ảnh chụp, APK | Session 5, 7 và rubric | README |
| Khai báo dùng AI, giải thích được mọi phần mình nộp | Session 1 và rubric | README, ADR-022 |

## 2. Phần mở rộng sáng tạo V1 ngoài slide (vẫn theo mẫu slide)

| Tính năng sáng tạo V1 | Làm theo mẫu nào của slide |
|---|---|
| Đăng ký OTP demo, thiết bị tin cậy, đăng nhập hai cách (chống brute-force `auth_throttles`) | Retrofit + `ApiResult` + `AuthInterceptor`, token Keystore, ViewModel + LiveData |
| Quản lý lời mời kết bạn (`contact_requests`) và danh bạ bạn bè | `RecyclerView` + `ListAdapter` + `DiffUtil`, Room Cache, Retrofit API |
| Gọi thoại WebRTC 1-1 | Báo hiệu qua **WebSocket sẵn có**, `PermissionRequester` cho `RECORD_AUDIO`, foreground service, coturn STUN/TURN |
| Dịch tin nhắn bằng Google ML Kit | Thao tác trong menu nhấn giữ tin nhắn, kết quả lưu Room `message_translation` qua Executor |
| Tìm kiếm offline FTS4 | Room (`@Fts4`) + DAO trả `LiveData`, cột `normalized_text` hỗ trợ tìm tiếng Việt không dấu |

*(Ghi chú: Các tính năng nâng cao như 2FA TOTP, Bước nhận diện bạn bè trên thiết bị lạ, Trợ lý ảo bot AI, Cuộc gọi video thuộc định hướng mở rộng tương lai V2, không triển khai trong V1).*

## 3. Việc cần xác nhận với giảng viên

Slide có nhắc **mô tả đồ án nhóm nằm trên LMS** và một **"reference server" chạy ở cổng 8080** dùng cho demo của giảng viên. Cần bạn kiểm tra trên LMS:

1. Có **mô tả chi tiết đồ án** (danh sách tính năng, yêu cầu kỹ thuật bắt buộc) ngoài rubric không?
2. Giảng viên có **cung cấp reference server hoặc đặc tả API** không, hay mỗi nhóm tự thiết kế server và API? (slide nói "teams define the API contract")
3. Có **yêu cầu bắt buộc về công nghệ phía server** không? Slide không quy định.
4. Cách chấm chi tiết 40 điểm thuyết trình (có yêu cầu mỗi người trình bày một phần Android không?).
