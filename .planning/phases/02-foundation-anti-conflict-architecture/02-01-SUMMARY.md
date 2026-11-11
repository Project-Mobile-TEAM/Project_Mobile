---
phase: 02-foundation-anti-conflict-architecture
plan: "01"
subsystem: api
status: complete
completed: 2026-11-11
tags: [spring-boot, maven, jdbc, supabase]
provides:
  - Backend Maven Java17 và readiness JDBC thật
affects: [authentication, messaging]
key-files:
  created:
    - backend/pom.xml
    - backend/src/main/java/com/utechat/backend/controller/HealthController.java
    - backend/src/test/java/com/utechat/backend/health/HealthControllerTest.java
key-decisions:
  - Liveness không phụ thuộc DB; readiness mới chạy SELECT 1 theo API_CONTRACT
  - Credential lấy từ env, không trả chi tiết exception
requirements-completed: []
coverage:
  - id: backend-foundation
    description: Backend chạy độc lập và readiness kết nối Supabase thật
    requirement: REQ-ui-standards
    verification:
      - kind: integration
        ref: backend/src/test/java/com/utechat/backend/health/HealthControllerTest.java
        status: pass
      - kind: integration
        ref: "HTTP GET /api/health/ready: 200 UP/CONNECTED"
        status: pass
    human_judgment: false
---

# 02-01 — Backend và kết nối Supabase

Backend Spring Boot 3.3.5 biên dịch Java17 qua Maven Wrapper 3.9.11; HikariCP dùng cấu hình Supabase từ .env.

## Kết quả và commit

Hai task cấu hình backend và health được commit cùng nhau trong `1fe95a9`.
HealthControllerTest: **4/4 pass** (liveness không chạm DB, readiness thành công, exception, kết quả null).

HTTP thật ngày 2026-11-11:
- /api/health: HTTP200, status UP.
- /api/health/ready: HTTP200, status UP, database CONNECTED.
- CORS preflight từ http://10.0.2.2: HTTP200, Allow-Origin khớp origin.

## Điều chỉnh so với plan

- Theo API_CONTRACT, SELECT1 chỉ ở /ready; /health là liveness.
- Default properties từ .env có ưu tiên thấp hơn OS env/CLI; không dùng System.setProperty để ghi đè cấu hình vận hành.
- CORS wildcard chỉ trong profile dev, không cookie credentials. Response lỗi chỉ trả mã trạng thái cố định.
- DataSource nằm ở application.yml để mọi profile dùng chung biến môi trường; application-dev.yml chỉ cấu hình dev. Không có password mặc định hay URL dự phòng giả.

## Giới hạn và bước sau

Chưa có API nghiệp vụ Auth/Contacts/Chat, JWT hoặc Spring Security — thuộc các phase sau. Khi triển khai phải chọn profile vận hành và HTTPS.
REQ-ui-standards mới hoàn thành phần nền tảng; chưa đánh dấu toàn requirement vì tuỳ chỉnh ngôn ngữ/theme thuộc Phase3.
