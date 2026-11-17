# Phase 4 — Đợt 01: Chuẩn hóa số điện thoại

Ngày thực hiện: 17/11/2026.

## Phạm vi đã hoàn thành

Tiện ích `ContactsValidator.normalize(String)` phục vụ bước nhập số khi tìm người:

- Đổi số Việt Nam dạng `0901111111` thành `+84901111111`.
- Giữ số quốc tế có dấu `+`, ví dụ `+14155551234`.
- Loại dấu cách, dấu ngoặc và gạch nối khi chuẩn hóa.
- Từ chối chữ lẫn trong số, dấu cộng sai vị trí, mã quốc gia bắt đầu bằng 0 và độ dài sai.
- Trả `null` cho dữ liệu không hợp lệ để lớp giao diện hiển thị lỗi trước khi gọi mạng.

Đây là kiểm tra cấu trúc theo hợp đồng E.164 của project (8–15 chữ số), không xác nhận số thuê bao có tồn tại. Backend vẫn phải kiểm tra lại đầu vào.

## Tệp trong đợt này

| Tệp | Thay đổi |
|---|---|
| `app/src/main/java/com/utechat/ui/contacts/ContactsValidator.java` | Thêm hàm chuẩn hóa thuần Java, có chú thích tiếng Việt |
| `app/src/test/java/com/utechat/ui/contacts/ContactsValidatorTest.java` | Thêm 28 ca kiểm thử JUnit 4 |
| `docs/phase4/01-phone-validation.md` | Ghi phạm vi, bằng chứng kiểm tra và công việc còn lại |

## Kết quả kiểm tra

- Biên dịch chính hai tệp Java trên bằng JDK 21 với `javac --release 17 -encoding UTF-8`: đạt.
- Chạy JUnit 4.13.2 bằng `org.junit.runner.JUnitCore`: **OK (28 tests)**, không có ca thất bại.
- Kiểm tra bằng dữ liệu hợp lệ, số đã chuẩn hóa, biên 8 và 15 chữ số, ngoài biên 7 và 16 chữ số, `null`, chuỗi rỗng, dấu cộng sai, chữ, tab, xuống dòng và chữ số full-width.
- Kiểm tra khoảng trắng trong diff bằng `git diff --check`: đạt.

Có thể chạy lại bằng Gradle tại thư mục gốc khi môi trường build Android hoạt động:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.utechat.ui.contacts.ContactsValidatorTest" --no-daemon
```

Trong lần này JUnit được chạy trực tiếp vì môi trường trước đó gặp lỗi khởi động Gradle daemon. Lệnh Gradle ở trên chưa được xác nhận chạy thành công.

Lệnh kiểm tra trực tiếp đã dùng (các JAR đã có trên máy, không thêm dependency):

```powershell
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot/bin'
$junit = "$env:USERPROFILE/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar"
$hamcrest = "$env:USERPROFILE/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
$out = Join-Path $env:TEMP 'phase4-validator-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& "$jdk/javac.exe" --release 17 -encoding UTF-8 -cp "$junit;$hamcrest" -d $out app/src/main/java/com/utechat/ui/contacts/ContactsValidator.java app/src/test/java/com/utechat/ui/contacts/ContactsValidatorTest.java
if ($LASTEXITCODE -ne 0) { throw 'Compile failed' }
& "$jdk/java.exe" -cp "$out;$junit;$hamcrest" org.junit.runner.JUnitCore com.utechat.ui.contacts.ContactsValidatorTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
```

## Nhánh và cách bàn giao

- Nhánh bàn giao local: `phase4_NP`, từ snapshot `planning` tại `f607095fb9ed371efa5798adbaff2f1d5a63e643` đã chứa nền Phase 3. Dùng worktree riêng `TeamProject-phase4-review` để tránh mang theo code chưa kiểm chứng.
- Đã đọc và xác nhận remote `main` tại `aaf38445c5bcb219799825617d75cda45ade374a`; ban đầu dự định tạo nhánh remote từ `main` và đưa đúng ba tệp lên đó.
- Chỉ bàn giao ba tệp liệt kê ở trên; không đưa toàn bộ code Phase 4 chưa xác minh từ lần trước vào commit này.
- Bản local cũ ở `TeamProject` vẫn được giữ để tiếp tục tách và kiểm chứng từng phần.
- Chưa có màn tìm kiếm hoặc API được tích hợp trong đợt 01; tiện ích này sẽ được gọi từ phần tìm người ở một đợt sau.
- Chưa build APK, chưa chạy emulator và chưa kiểm thử toàn bộ ứng dụng.

## Tình trạng đưa lên GitHub

- Đã kiểm tra trước khi ghi: chưa có nhánh remote `phase4_NP`.
- GitHub connector đọc được repository nhưng từ chối tạo Git tree và tạo nhánh bằng HTTP 403: `Resource not accessible by integration`.
- Git trực tiếp cũng chưa kết nối được: `Could not resolve host: github.com`.
- Chưa tạo được nhánh remote và chưa push. Đã chuẩn bị commit local chỉ chứa ba tệp của đợt 01.
- Cần cấp quyền ghi nội dung repository cho kết nối GitHub hoặc khôi phục kết nối Git trực tiếp để bàn giao tiếp. Không cần viết lại tính năng.

## Điểm dừng

Đợt 01 hoàn thành ở mức tiện ích đã kiểm thử. Dừng tại đây để người dùng xem tiến độ; chưa triển khai thêm tính năng tiếp theo trong đợt này.
