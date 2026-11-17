# Phase 4 — Báo cáo tiến độ: chuẩn hóa SĐT và giới hạn tra cứu

Ngày thực hiện: 17/11/2026. Đây là file báo cáo chung duy nhất, được cập nhật sau từng đợt nhỏ.

**Nơi làm việc hiện tại:** `D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject`, nhánh `phase4_NP`.
Từ lần thống nhất worktree dưới đây chỉ tiếp tục làm việc trong thư mục này. Các đoạn mô tả dùng worktree review ở đợt 01/02 là lịch sử.

## Đợt 01 — Chuẩn hóa SĐT: đã kiểm thử

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

## Nhánh và cách bàn giao đợt 01 (lịch sử)

- Nhánh bàn giao local: `phase4_NP`, từ snapshot `planning` tại `f607095fb9ed371efa5798adbaff2f1d5a63e643` đã chứa nền Phase 3. Dùng worktree riêng `TeamProject-phase4-review` để tránh mang theo code chưa kiểm chứng.
- Đã đọc và xác nhận remote `main` tại `aaf38445c5bcb219799825617d75cda45ade374a`; ban đầu dự định tạo nhánh remote từ `main` và đưa đúng ba tệp lên đó.
- Chỉ bàn giao ba tệp liệt kê ở trên; không đưa toàn bộ code Phase 4 chưa xác minh từ lần trước vào commit này.
- Bản local cũ ở `TeamProject` vẫn được giữ để tiếp tục tách và kiểm chứng từng phần.
- Chưa có màn tìm kiếm hoặc API được tích hợp trong đợt 01; tiện ích này sẽ được gọi từ phần tìm người ở một đợt sau.
- Chưa build APK, chưa chạy emulator và chưa kiểm thử toàn bộ ứng dụng.

## Lịch sử kết nối GitHub ở đợt 01

- Đã kiểm tra trước khi ghi: chưa có nhánh remote `phase4_NP`.
- GitHub connector đọc được repository nhưng từ chối tạo Git tree và tạo nhánh bằng HTTP 403: `Resource not accessible by integration`.
- Git trực tiếp cũng chưa kết nối được: `Could not resolve host: github.com`.
- Chưa tạo được nhánh remote và chưa push. Đã chuẩn bị commit local chỉ chứa ba tệp của đợt 01.
- Cần cấp quyền ghi nội dung repository cho kết nối GitHub hoặc khôi phục kết nối Git trực tiếp để bàn giao tiếp. Không cần viết lại tính năng.

## Điểm dừng đợt 01

Đợt 01 hoàn thành ở mức tiện ích đã kiểm thử. Dừng tại đây để người dùng xem tiến độ; chưa triển khai thêm tính năng tiếp theo trong đợt này.

## Đợt 02 — Giới hạn tần suất tra cứu: đã kiểm thử

### Đã thêm

| Tệp | Nội dung |
|---|---|
| `backend/src/main/java/com/utechat/backend/config/TimeConfig.java` | Cung cấp Clock UTC cho Spring, thay được bằng đồng hồ giả trong test |
| `backend/src/main/java/com/utechat/backend/service/LookupRateLimiter.java` | Tối đa 10 lượt được ghi nhận trong cửa sổ trượt 60 giây cho mỗi UUID người dùng |
| `backend/src/test/java/com/utechat/backend/contacts/LookupRateLimiterTest.java` | 8 kiểm thử JUnit 5, gồm Spring wiring và yêu cầu đồng thời |

`checkAndRecord(userId)` cho phép 10 lượt đầu; lượt thứ 11 ném `ApiException` với HTTP 429, mã `RATE_LIMITED` và `details.retryAfterSeconds` làm tròn lên. Lượt bị từ chối không được ghi thêm nên không kéo dài thời gian chờ. Các lượt hết hạn và bucket rỗng được dọn khi có request mới.

Kiểm tra và ghi lượt được đồng bộ dưới cùng một khóa để yêu cầu đồng thời không vượt giới hạn. Dùng lại `ApiException` và dependency sẵn có; không thêm thư viện hoặc thay cấu hình build. Ngưỡng 10 lượt và 60 giây cố định theo yêu cầu V1.

### Kết quả kiểm tra thực tế

| Ca kiểm thử | Kết quả |
|---|---|
| Cho phép 10 lượt, từ chối lượt 11 với đúng HTTP, mã lỗi và thời gian chờ | Đạt |
| Tài khoản khác vẫn có đủ lượt riêng | Đạt |
| Giải phóng lượt đúng mốc 60.000 ms | Đạt |
| Cửa sổ trượt giữ nguyên các lượt chưa hết hạn | Đạt |
| Thời gian chờ có phần lẻ được làm tròn lên | Đạt |
| Gọi tiếp khi bị giới hạn không kéo dài thời gian chờ | Đạt |
| 20 luồng cùng tài khoản có đúng 10 lượt được chấp nhận | Đạt |
| Spring tạo được service singleton với Clock UTC | Đạt |

- Biên dịch Java với `javac --release 17 -encoding UTF-8`: đạt.
- Chạy trực tiếp test thực tế `LookupRateLimiterTest` bằng JUnit Platform Launcher 1.10.3 và Jupiter 5.10.3: **8 tests successful, 0 failed**.
- Spring context test sử dụng Spring Framework 6.1.12 đã có trong cache. Đây không phải full application context của Spring Boot 3.3.5.
- Chạy lại 28 ca `ContactsValidatorTest` của đợt 01: **OK (28 tests)**.
- Tổng cộng **36 ca đạt** ở hai phần đã tách riêng. Không suy ra toàn bộ ứng dụng đã pass.
- Runner JUnit tạm và output biên dịch nằm ngoài source, không thêm vào commit.

Lệnh chuẩn để chạy lại test backend khi Maven có đầy đủ dependency, từ thư mục `backend`:

```powershell
.\mvnw.cmd test "-Dtest=LookupRateLimiterTest"
```

Lệnh Maven chưa được xác nhận chạy thành công do giới hạn môi trường tải dependency ở phiên này. Kết quả đạt ở trên là từ chạy JUnit trực tiếp với các JAR đã có sẵn.

### Giới hạn và điểm dừng đợt 02

- Chưa nối bộ giới hạn vào API `/api/users/lookup`: đợt này hoàn thiện thành phần giới hạn và kiểm thử riêng, chưa có endpoint mới trên nhánh bàn giao.
- Bộ đếm ở bộ nhớ một instance backend và reset khi khởi động lại. Khi chạy nhiều instance phải dùng kho bộ đếm chung.
- Khóa đồng bộ và bước dọn toàn bộ bucket phù hợp phạm vi V1 nhỏ; chưa đo hiệu năng tải lớn.
- Chưa chạy bộ test backend đầy đủ, build APK, emulator hoặc kiểm thử Android với backend thật.
- Mọi nội dung đợt 02 được ghi nối tiếp vào file này; không tạo báo cáo `.md` khác.
- Chỉ đưa phần đợt 01 và 02 đã kiểm thử lên nhánh bàn giao. Phần còn lại ở `TeamProject` chưa được đưa vào.
- Dừng sau khi bàn giao đợt 02 để người dùng xem tiến độ; không tự chuyển sang API tìm người trong cùng lượt.

### Kết quả công bố sau khi kết nối lại GitHub

- Kiểm tra lại nhánh remote: chưa có `phase4_NP`.
- Tạo Git tree chứa đúng sáu tệp của đợt 01 và 02 vẫn bị từ chối: HTTP 403, `Resource not accessible by integration`.
- Tạo nhánh `phase4_NP` từ snapshot `planning` cũng bị từ chối với cùng lỗi 403.
- Git trực tiếp vẫn báo `Could not resolve host: github.com`.
- Vì vậy, đợt 02 được lưu bằng commit local trên nhánh `phase4_NP`; chưa có nhánh remote và chưa push thành công.
- Cần khôi phục quyền ghi repository của kết nối GitHub hoặc đường Git trực tiếp trước khi công bố. Tất cả kết quả kiểm thử ở trên vẫn được giữ, không cần viết lại tính năng.

## Thống nhất thư mục và kiểm tra lại — 17/11/2026

### Kiểm tra và bảo toàn trước khi chuyển nhánh

- `TeamProject` ban đầu ở `feature/contacts`, HEAD `f607095fb9ed371efa5798adbaff2f1d5a63e643`, có 17 tệp tracked thay đổi và 31 tệp untracked.
- `TeamProject-phase4-review` ban đầu sạch, đang giữ nhánh `phase4_NP` tại `ada152dd6e1b42d028d37c240967ef36b8b9faeb`.
- Xác nhận `b6f1b09` là tổ tiên của `ada152d` bằng `git merge-base --is-ancestor`, exit code 0. Không cherry-pick lại hai commit.
- Đã dùng `git stash push --include-untracked` để lưu đủ 48 tệp trước khi chuyển nhánh. Bản dự phòng được giữ nguyên, không pop/drop:
  - Stash: `5492dd88b08d56f4de575e7023be7b1b8cb45d64`.
  - Nhãn: `phase4 consolidation: preserve unfinished work from feature/contacts`.
- Cache build bị ignore vẫn giữ tại chỗ, không xóa.

### Chuyển nhánh và khôi phục bản nháp

- Chuyển worktree review sang detached HEAD tại `ada152d` để giải phóng nhánh; không xóa thư mục hay nhánh nào.
- Chuyển `TeamProject` sang `phase4_NP` tại `ada152d`.
- Khôi phục 17 tệp tracked và 25 tệp untracked chưa trùng với các tệp đã commit trên `phase4_NP`.
- Sáu đường dẫn đã có trên nhánh được giữ theo phiên bản đã commit. So sánh SHA-256 với trước khi chuyển cho kết quả 45/48 tệp giữ nguyên từng byte.
- Ba đường dẫn khác biệt là `TimeConfig.java`, `LookupRateLimiter.java`, `LookupRateLimiterTest.java`: giữ phiên bản mới đã kiểm thử trong working tree, đồng thời xác nhận SHA-256 của từng bản cũ trong stash trùng bản gốc trước chuyển.
- 42 tệp bản nháp đang ở trạng thái chưa commit trong `TeamProject`; đây là phần việc chưa được nghiệm thu. Không đưa chúng vào commit cập nhật báo cáo hoặc lần push này.
- Không tạo thêm project, worktree hoặc file báo cáo. Thư mục review chỉ được giữ lại tại detached HEAD cũ.

### Kiểm thử thực tế từ TeamProject

| Kiểm tra | Kết quả |
|---|---|
| Biên dịch `ContactsValidator` và test bằng `javac --release 17` | Đạt |
| `ContactsValidatorTest` bằng JUnit 4.13.2 | **OK (28 tests)** |
| Biên dịch `ApiException`, `TimeConfig`, `LookupRateLimiter` và test bằng `javac --release 17` | Đạt sau khi tái tạo runner tạm |
| `LookupRateLimiterTest` bằng JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3 | **8 tests successful, 0 failed** |
| `git diff --check` trên các thay đổi hiện có | Đạt |

Lần gọi test backend đầu tiên không khởi chạy được vì file runner trong thư mục TEMP của phiên trước không còn tồn tại (`error: file not found: .../phase4-rate-limit-tests/ContactsRateLimitTestRunner.java`). Đã tái tạo runner tạm ngoài source, biên dịch lại chính mã nguồn trong `TeamProject` và chạy đủ 8 test thành công. Không sửa logic tính năng trong đợt thống nhất này.

Spring wiring test vẫn dùng các JAR Spring Framework 6.1.12 trong cache. Kết quả 36 test chỉ áp dụng hai thành phần đã nghiệm thu riêng; không phải kết quả build Gradle/Maven đầy đủ, APK, emulator hoặc nghiệm thu các bản nháp.

### Lần push bằng Git CLI HTTPS

Chạy từ `TeamProject`, remote `origin` là `https://github.com/Project-Mobile-TEAM/Project_Mobile.git`:

```powershell
git push --set-upstream origin phase4_NP
```

Exit code 1. Nguyên văn lỗi:

```text
fatal: unable to access 'https://github.com/Project-Mobile-TEAM/Project_Mobile.git/': Could not resolve host: github.com
```

Lỗi xảy ra ở bước phân giải tên miền, chưa cho phép kết luận về quyền ghi. GitHub connector được dùng để đọc danh sách nhánh và trả về `branches: []` cho `phase4_NP` tại thời điểm kiểm tra. Không dùng trạng thái kết nối để suy đoán quyền, không force push.

Link nhánh dự kiến: https://github.com/Project-Mobile-TEAM/Project_Mobile/tree/phase4_NP — chưa được xác nhận tồn tại trên remote.

### Điểm dừng

Đã thống nhất working tree, giữ đủ bản nháp và bản dự phòng, chạy lại 36 kiểm thử liên quan. Commit tiếp theo chỉ cập nhật file báo cáo này trên `phase4_NP`; hai commit tính năng `b6f1b09` và `ada152d` được giữ trong lịch sử. Chưa triển khai tính năng tiếp theo.
