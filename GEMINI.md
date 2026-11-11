# UTEChat Project Development Guidelines & Rules

## 1. Quy chuẩn bắt buộc về Comment Code (Educational In-Code Comments) - QUAN TRỌNG NHẤT
> **Yêu cầu cốt lõi từ người dùng**: Khi sinh hoặc chỉnh sửa bất kỳ đoạn code nào (Java, XML, SQL, Spring Boot, Script), TOÀN BỘ thành viên / AI agent BẮT BUỘC phải comment ý nghĩa rõ ràng vào các dòng code bằng **Tiếng Việt**.

- **Độ chi tiết & Phong cách**: Comment ngắn gọn, súc tích, dễ hiểu, giải thích bản chất "dòng code / khối lệnh này làm gì và nhằm mục đích gì trong hệ thống".
- **Mục tiêu giáo dục**: 
  1. Giúp sinh viên khi đọc lại code có thể nắm bắt và hiểu cặn kẽ 100% logic một cách dễ dàng nhất.
  2. Chuẩn bị cho buổi bảo vệ đồ án: sinh viên có thể giải trình tự tin, mạch lạc và sửa code trực tiếp ngay lập tức khi giảng viên ("thầy") hỏi vấn đáp hoặc yêu cầu demo thay đổi logic.
- **Phạm vi áp dụng**:
  - **Android Java**: Khai báo biến, ViewModel LiveData, Room Entity & DAO queries, Retrofit interfaces, Repository seams (Real & Fake), Service Locator (`Injection.java`), Adapter RecyclerView, Fragment Lifecycle.
  - **Android XML**: Layouts, Navigation graphs (giải thích từng `<action>` và destination), Menu, Resource strings (`strings_*.xml`).
  - **Spring Boot Java**: Controllers, Services, Entities, DTOs, HikariCP/PostgreSQL configs, CORS, Security.
  - **Database SQL**: Bảng, Khóa ngoại, Indexes, RLS logic, Seed demo data.

## 2. Công nghệ & Ràng buộc kiến trúc (Constraints)
- **Android**: Java 17, Views + XML, ViewBinding, MVVM + LiveData, Navigation Component (Single Activity), Room SQLite, Retrofit + OkHttp. Tuyệt đối không dùng Kotlin/Coroutine/Compose/Hilt.
- **Backend**: Spring Boot 3, Java 17, Maven (`pom.xml`), JDBC kết nối Supabase Transaction Pooler (port 6543, `prepareThreshold=0`).
- **Gói mã nguồn**: `com.utechat` (Android) và `com.utechat.backend` (Backend).
- **Ngôn ngữ giao tiếp**: Tiếng Việt cho toàn bộ phản hồi và trao đổi với người dùng.

## 3. Bảo mật triển khai V1 — bắt buộc từ khi viết tính năng
- Tuân thủ [docs/DEPLOYMENT_SECURITY.md](docs/DEPLOYMENT_SECURITY.md), cụ thể hóa ADR-017/018/019; mọi plan và phần code liên quan phải mang theo các yêu cầu này.
- Release dùng HTTPS/WSS với chứng chỉ hợp lệ; HTTP chỉ cho Debug nội bộ. Không TrustAll hoặc bỏ xác minh hostname.
- Dùng URL backend cấu hình tập trung, không hardcode IP/domain trong module. WebSocket dùng header Bearer trên handshake, không JWT trong URL.
- Secret chỉ ở backend/CI; REST, WS và media phải kiểm quyền tại server. Nginx terminate TLS; Spring 8080 chỉ nội bộ; coturn TLS 443 dùng secondary IP theo ADR-013.
- Không tuyên bố đã bảo mật production chỉ vì có tài liệu hoặc build pass; phải qua kiểm thử hosted trước phát hành.
