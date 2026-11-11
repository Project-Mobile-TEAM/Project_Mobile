# Phase 2: Foundation & Anti-Conflict Architecture - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-11
**Phase:** 2-Foundation & Anti-Conflict Architecture
**Areas discussed:** Backend Architecture & Build Tool, Navigation & Bottom Navigation Layout, Seam & Fake Repositories, Walking Skeleton Health Probe

---

## Backend Architecture & Build Tool

### Build Tool
| Option | Description | Selected |
|--------|-------------|----------|
| Maven (pom.xml) | Độc lập với Android Gradle, chuẩn mực đồ án Spring Boot, có wrapper mvnw riêng | ✓ |
| Gradle (build.gradle) | Đồng bộ hệ sinh thái build với Android project | |
| You decide | Chọn phương án tối ưu nhất cho nhóm sinh viên | |

**User's choice:** Maven (pom.xml)
**Notes:** Dùng Maven độc lập trong `backend/` giúp tách bạch rõ ràng giữa Android client và Spring Boot server, tránh xung đột Gradle version giữa hai module.

### Credentials Loading
| Option | Description | Selected |
|--------|-------------|----------|
| dotenv-java + application-dev.yml | Tự động nạp .env ở root, cấu hình HikariCP kết nối Supabase Pooler (port 6543, prepareThreshold=0) an toàn không lộ secret | ✓ |
| Environment Variables hệ thống | Yêu cầu export biến môi trường SUPABASE_DB_URL thủ công trước khi chạy | |
| You decide | Tự động hóa tốt nhất, tránh sinh viên phải gõ lệnh cấu hình phức tạp | |

**User's choice:** dotenv-java + application-dev.yml
**Notes:** Giúp các thành viên chỉ cần clone code về và có sẵn file `.env` cục bộ là chạy được ngay không cần cấu hình biến môi trường hệ thống.

### Java Version
| Option | Description | Selected |
|--------|-------------|----------|
| Java 17 LTS | Trùng khớp 100% với compileOptions của Android app, tương thích tối đa với máy tính của các thành viên trong nhóm | ✓ |
| Java 21 LTS | Khai thác tính năng mới của Java 21 (Virtual Threads nếu cần) | |
| You decide | Đảm bảo tính tương thích cao nhất | |

**User's choice:** Java 17 LTS
**Notes:** Đồng nhất với compileOptions của Android project (`VERSION_17`).

### Network & CORS
| Option | Description | Selected |
|--------|-------------|----------|
| Port 8080 + CORS * | Cho phép mọi nguồn (dev mode: 10.0.2.2, localhost, LAN) để Android Emulator và máy thật dễ dàng kết nối | ✓ |
| Port 8080 + Chặn CORS | Chỉ cho phép localhost và 10.0.2.2 | |
| You decide | Cấu hình mạng tối ưu cho phát triển | |

**User's choice:** Port 8080 + CORS cho phép mọi nguồn

---

## Navigation & Bottom Navigation Layout

### Navigation Tabs
| Option | Description | Selected |
|--------|-------------|----------|
| 3 tab: Tin nhắn, Danh bạ, Cá nhân | Gọn gàng chuẩn Zalo/Telegram; tính năng Gọi WebRTC (Phase 6) mở trực tiếp từ hội thoại hoặc danh bạ | ✓ |
| 4 tab: Tin nhắn, Cuộc gọi, Danh bạ, Cá nhân | Thêm 1 tab riêng quản lý lịch sử cuộc gọi | |
| You decide | Chuẩn UX nhắn tin di động | |

**User's choice:** 3 tab: Tin nhắn (Chats), Danh bạ (Contacts), Cá nhân (Profile)

### Anti-Conflict Nav Strategy
| Option | Description | Selected |
|--------|-------------|----------|
| Nested Nav Graphs (<include>) | nav_graph.xml gốc chỉ include 4 file con (nav_auth.xml, nav_chat.xml, nav_contacts.xml, nav_profile.xml); 4 thành viên code song song 100% không conflict file XML | ✓ |
| Single monolithic nav_graph.xml | Gom chung toàn bộ fragment vào 1 file duy nhất (dễ gây conflict khi 4 người cùng merge) | |
| You decide | Giải pháp phân tách tốt nhất | |

**User's choice:** Nested Nav Graphs (<include>)

### Resource Strings Strategy
| Option | Description | Selected |
|--------|-------------|----------|
| Split Resource Files | Tách biệt hoàn toàn thành strings_common.xml, strings_auth.xml, strings_chat.xml, strings_contacts.xml, strings_profile.xml; triệt tiêu xung đột git res/values | ✓ |
| Single strings.xml | Dùng chung 1 file và dùng prefix quy ước | |
| You decide | | |

**User's choice:** Split Resource Files

### Activity Pattern
| Option | Description | Selected |
|--------|-------------|----------|
| Pure Single Activity (MainActivity) | Điều phối luồng Auth vs Main App hoàn toàn qua NavController, ẩn BottomNavigationView khi ở màn hình Auth, đúng chuẩn hiện đại của Google và slide giảng dạy | ✓ |
| Two Activities (AuthActivity + MainActivity) | Tách thành 2 Activity độc lập, đăng nhập xong dùng Intent mở MainActivity | |
| You decide | Kiến trúc Activity chuẩn | |

**User's choice:** Pure Single Activity (MainActivity)

---

## Seam & Fake Repositories

### Android Dependency Injection
| Option | Description | Selected |
|--------|-------------|----------|
| Manual DI / Service Locator (Injection.java) | Thuần Java bám sát slide môn học, cung cấp Singleton Repository & Room Database, không cần thư viện annotation cồng kềnh, sinh viên dễ kiểm soát và viết Unit test | ✓ |
| Dagger Hilt | Khung Dependency Injection tiêu chuẩn của Google, cần thêm plugin Kapt/KSP và cấu hình phức tạp hơn | |
| You decide | | |

**User's choice:** Manual DI / Service Locator (Injection.java)

### Seam Toggle
| Option | Description | Selected |
|--------|-------------|----------|
| BuildConfig / Config Flag (USE_FAKE_DATA) | Cho phép từng bạn sinh viên bật/tắt Fake Repository trong Injection.java một cách tức thì mà không cần chuyển build variant phức tạp trong Android Studio | ✓ |
| Build Flavors (mock / prod) | Tách gradle flavors devFake và prodReal độc lập trong build.gradle.kts | |
| You decide | | |

**User's choice:** BuildConfig / Config Flag (USE_FAKE_DATA)

### Fake Repos Scope
| Option | Description | Selected |
|--------|-------------|----------|
| Trọn bộ 3 Seam: AuthRepository, ContactsRepository, ChatRepository | Đầy đủ Interface + Fake implementation với dữ liệu mẫu sẵn có (5 tài khoản demo đã seed), giúp 4 bạn vào Phase 3, 4, 5 code được ngay không đợi nhau | ✓ |
| Chỉ tạo AuthRepository | Các thành viên Phase 4, 5 tự định nghĩa Interface khi bắt đầu phase của họ | |
| You decide | | |

**User's choice:** Trọn bộ 3 Seam: AuthRepository, ContactsRepository, ChatRepository

### Room Entities Hotspot
| Option | Description | Selected |
|--------|-------------|----------|
| Khai báo toàn bộ 13 Room Entities chuẩn theo docs/ERD.md | Đồng bộ 1-1 với Supabase CSDL đã tạo ở Phase 1, chốt cứng schema Room V1, chặn hoàn toàn xung đột SQLite / Migration giữa các thành viên | ✓ |
| Chỉ tạo 4 Entities cốt lõi | Các entities còn lại giao cho từng thành viên tự thêm khi đến phase của mình | |
| You decide | | |

**User's choice:** Khai báo toàn bộ 13 Room Entities chuẩn theo docs/ERD.md

---

## Walking Skeleton Health Probe

### Health Endpoint
| Option | Description | Selected |
|--------|-------------|----------|
| Full DB Ping: GET /api/health | Trả về status UP + database CONNECTED (thực thi query SELECT 1 trên Supabase) để chứng minh thông luồng 100% CSDL | ✓ |
| Basic Ping: GET /api/health | Chỉ trả về status UP của Spring Boot server mà không query CSDL | |
| You decide | | |

**User's choice:** Full DB Ping: GET /api/health

### Android Health UI
| Option | Description | Selected |
|--------|-------------|----------|
| Splash Diagnostic Badge | Trên màn hình Splash hiển thị icon/badge trạng thái kết nối Backend & Database rõ ràng, tự động chuyển sang Auth khi OK hoặc có nút Thử lại nếu mất kết nối | ✓ |
| Dedicated Diagnostic Screen | Thêm 1 màn hình chẩn đoán riêng trong menu Settings để test ping bất kỳ lúc nào | |
| Chỉ log Logcat | Không hiển thị gì trên UI, chỉ xem qua Android Studio Logcat | |
| You decide | | |

**User's choice:** Splash Diagnostic Badge

### Backend URL & Cleartext
| Option | Description | Selected |
|--------|-------------|----------|
| BuildConfig + Network Security Config | URL mặc định http://10.0.2.2:8080/ trong gradle, mở cleartext HTTP cho localhost, 10.0.2.2 và LAN dev qua network_security_config.xml an toàn theo chuẩn Google | ✓ |
| Hardcode URL trong Java | Ghi thẳng URL vào file ApiClient.java | |
| Dynamic Server Dialog | Hiện popup cho nhập IP server nếu không kết nối được 10.0.2.2 | |
| You decide | | |

**User's choice:** BuildConfig + Network Security Config

### Network Client Logging
| Option | Description | Selected |
|--------|-------------|----------|
| HttpLoggingInterceptor (Level.BODY) + Timeout 15s | In toàn bộ HTTP request/response ra Logcat giúp các thành viên dễ debug API, timeout 15s đủ cho Supabase pooler handshake | ✓ |
| Level.BASIC + Timeout 30s | Chỉ in status code và URL | |
| You decide | | |

**User's choice:** HttpLoggingInterceptor (Level.BODY) + Timeout 15s

---

## Educational & Code Commenting Standard

### In-Code Commenting Requirement
| Option | Description | Selected |
|--------|-------------|----------|
| Bắt buộc comment Tiếng Việt chi tiết, súc tích | Toàn bộ thành viên / agent khi sinh code bắt buộc phải comment ý nghĩa từng dòng/khối code quan trọng bằng Tiếng Việt dễ hiểu, hỗ trợ sinh viên học code và trả lời vấn đáp khi giảng viên hỏi bảo vệ đồ án | ✓ |
| Chỉ comment JavaDoc cấp cao | Chỉ viết JavaDoc trên đầu Class và Method | |
| Code tự tường minh không cần comment | Không bắt buộc comment | |

**User's choice:** Bắt buộc comment Tiếng Việt chi tiết, súc tích
**Notes:** Quy tắc này áp dụng xuyên suốt toàn bộ dự án từ Phase 2 đến Phase 7, cho cả Android (Java/XML) và Backend (Spring Boot/SQL). Giúp sinh viên nắm vững 100% bản chất hoạt động của mã nguồn để tự tin giải trình và chỉnh sửa code nếu thầy giáo yêu cầu trực tiếp.

---

## the agent's Discretion

- Material 3 design palette and styling for Splash screen and placeholder fragments.
- Spring Boot sub-package layout inside `com.utechat.backend`.
- Room TypeConverters (UUID, Date, List) for mapping complex database columns.

## Deferred Ideas

- None — discussion remained strictly within the boundaries of Phase 2 Walking Skeleton & Anti-Conflict Architecture.
