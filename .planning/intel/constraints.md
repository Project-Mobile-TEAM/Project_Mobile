# Synthesized Constraints

> [!NOTE]
> **Historical Snapshot:** File này là snapshot trích xuất ràng buộc tại thời điểm Ingest Docs ban đầu (2026-11-10). Nguồn chân lý hiện hành là các ràng buộc đã cập nhật tại [docs/API_CONTRACT.md](file:///C:/Users/Admin/Desktop/Project-Mobile/docs/API_CONTRACT.md), [docs/ARCHITECTURE_DECISIONS.md](file:///C:/Users/Admin/Desktop/Project-Mobile/docs/ARCHITECTURE_DECISIONS.md) và [.planning/PROJECT.md](file:///C:/Users/Admin/Desktop/Project-Mobile/.planning/PROJECT.md).

## API Contract Architecture & Security
- source: docs/API_CONTRACT.md
- type: api-contract
- content: Tất cả REST endpoints đều sử dụng tiền tố '/api'. Xác thực bằng Bearer JWT token gửi trong header Authorization. Mọi phản hồi lỗi phải tuân thủ schema JSON đồng nhất: '{ "error": { "code": string, "message": string } }'. Không sử dụng refresh token; token có thời hạn 30 ngày và kiểm tra iat >= token_valid_after của thiết bị trên từng yêu cầu.

## WebSocket Frame & Event Specification
- source: docs/API_CONTRACT.md
- type: protocol
- content: Kết nối WebSocket tại endpoint '/ws' với query parameter '?token={jwt}'. Khung truyền tin JSON có cấu trúc '{ "v": 1, "type": string, "id": string, "payload": object }'. Hỗ trợ các event types: message:new, message:status, conversation:typing, call:signal, auth:revoked. Client phải duy trì ping/pong heartbeat và thực hiện exponential backoff reconnect khi đứt kết nối.

## Media Upload & Storage Limits
- source: docs/API_CONTRACT.md
- type: nfr
- content: Endpoint tải lên multipart POST /api/media áp dụng giới hạn dung lượng: Hình ảnh tối đa 10MB, Ghi âm giọng nói tối đa 10MB và thời lượng <= 5 phút, Video tối đa 50MB, Tệp tin đính kèm tối đa 25MB. Server phải kiểm tra MIME type qua cả file header magic bytes và extension. Tải tệp qua GET /api/media/{id} yêu cầu xác thực quyền thành viên hội thoại.

## Database Schema & Row-Level Security
- source: docs/ERD.md
- type: schema
- content: Server sử dụng PostgreSQL trên Supabase (mô hình M2). Tất cả các bảng phải bật Row Level Security (RLS) mà không định nghĩa policy công khai để vô hiệu hóa hoàn toàn Supabase Public Data API. Các khóa chính sử dụng UUID cho thực thể trừ bảng messages sử dụng BIGINT tự tăng. Client Android sử dụng Room SQLite với schema đối chiếu tương ứng.

## Android Course & Rubric Technology Constraints
- source: docs/COURSE_ALIGNMENT.md
- type: nfr
- content: Ứng dụng Android bắt buộc viết bằng Java thuần, View Binding, Single Activity Architecture với Navigation Component và Safe Args (chỉ truyền ID). Phải tuân thủ mô hình MVVM với LiveData, AppExecutors (single disk thread), thủ công AppContainer Dependency Injection. Tuyệt đối không sử dụng Kotlin, Coroutines, Flow, Jetpack Compose, hoặc Hilt/Dagger để đảm bảo tính đồng nhất với slide bài giảng và rubric môn học.

## Cloud Infrastructure & Deployment
- source: docs/ARCHITECTURE_DECISIONS.md
- type: nfr
- content: Hệ thống backend chạy trên một EC2 instance Ubuntu 24.04: Nginx reverse proxy phân luồng HTTPS (cổng 443) tới Spring Boot service (/api và /ws) và coturn STUN/TURN (UDP 3478 cùng dải cổng relay). Cơ sở dữ liệu tách biệt trên Supabase. Triển khai tự động thông qua GitHub Actions CI/CD.
