# Phase 4 — Báo cáo tiến độ: chuẩn hóa SĐT và giới hạn tra cứu

Ngày thực hiện: 17/11/2026. Đây là file báo cáo chung duy nhất, được cập nhật sau từng đợt nhỏ.

Cập nhật mới nhất: 19/11/2026 - đợt 25, đối chiếu kết quả Windows **126 JVM + 39 instrumentation**, không failure/error/skipped; tên testcase khớp source, source khớp SHA-256 trước lần chạy. **12 và 13 đã nghiệm thu; 01–14 hoàn thành theo checklist**. Commit đúng hai mục 12/13, script và báo cáo sau kiểm thử; giữ các bản nháp 15–20 và không push. Giới hạn backend/hosted/UI được ghi rõ ở đợt 25.

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

## Checklist các hạng mục còn lại của Phase 4

Hai thành phần đã kiểm thử riêng là chuẩn hóa SĐT (28 test) và giới hạn tra cứu (8 test). **Mục 01–02 đã nghiệm thu tại đợt 13; API backend 03–04 tại đợt 15; mục 05–06 tại đợt 17; mục 07–08 tại đợt 19 bằng bộ Windows 215 test; mục 09–10 tại đợt 21 bằng bộ Windows 251 test cùng 58 test app lồng; mục 11/14 tại đợt 23 bằng bộ Windows 83 JVM + 27 instrumentation; mục 12/13 tại đợt 25 bằng bộ Windows 126 JVM + 39 instrumentation; mục 15/16 tại đợt 27 bằng bộ Windows 157 JVM + 51 instrumentation.** Luồng tìm người trên Android chưa hoàn chỉnh; chuẩn hóa ô nhập và gọi API từ màn hình còn thuộc mục 17. Các mục 17–20 vẫn chưa được nghiệm thu toàn mục, dù một số đã có code nháp trong `TeamProject`. Bằng chứng nghiệm thu mới nhất tại đợt 27; lịch sử chuẩn bị và chẩn đoán 15/16 ở đợt 26.

Có 20 hạng mục để theo dõi, không mặc định tương ứng đúng 20 lượt thực hiện. Từ đợt 10, mỗi lượt chọn tối đa hai hạng mục cấp chính 01–20 theo thứ tự phụ thuộc; các phần con không được tính thành hạng mục cấp chính. Trước khi viết, đọc lại bản nháp liên quan để sửa và tái sử dụng phần phù hợp. Chỉ đánh dấu `[x]` khi đã có bằng chứng đạt toàn bộ điều kiện của mục đó; nếu bị chặn thì ghi nguyên nhân và phần còn thiếu.

### Backend

- [x] **01. Migration `is_friend` và database chạy thử:** kiểm tra tạo database mới và nâng cấp database cũ; phân biệt bạn bè với chặn; xử lý rõ dữ liệu chặn cũ không đủ thông tin; cập nhật bộ nạp schema chạy thử cùng migration mới. Không sửa migration đã áp dụng.
  - Đợt 13: xác nhận 6 test migration, 3 test Flyway và 1 smoke test đều đạt từ XML/log Windows; database mới/baseline/nâng cấp, backfill bảo thủ, giữ cờ đã biết, default/NOT NULL, rollback/retry và readiness đã kiểm trên PostgreSQL 16.16. Giữ nguyên checksum V4 và ghi rõ giới hạn dữ liệu chặn legacy.
- [x] **02. DTO, lỗi API, chính sách quan hệ, khóa cặp và sự kiện:** chốt JSON/mã lỗi; tái sử dụng JWT Phase 3; thống nhất khóa cặp trước khi khóa dòng; bổ sung `SyncEventWriter` và hợp đồng `CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED`; cập nhật tài liệu hợp đồng tương ứng.
  - Đợt 13: xác nhận 12 test nền PostgreSQL và 8 test JWT đều đạt, cùng các test DTO/lỗi/helper hồi quy. Đã kiểm chờ/nhả advisory lock hai kết nối, khóa cặp trước khóa dòng, JSONB, transaction/rollback, FK/CHECK và cấu hình JWT Phase 3; hợp đồng kỹ thuật ở docs/API_CONTRACT.md. Nghiệm thu nền tảng, không thay cho nghiệm thu caller/API mục 03–09.
  - [x] **02a. Lỗi thiếu query bắt buộc:** thiếu `phone` trả `400 PHONE_INVALID`; thiếu tham số khác trả `400 VALIDATION`; 4 test Spring MVC đạt, đã chạy lại trong bộ Windows 73 test.
  - [x] **02b. DTO JSON danh bạ và lời mời:** chốt năm record, kiểm đúng tên trường, boolean, ID/chiều lời mời và hồ sơ tối thiểu không chứa SĐT; 9 test Jackson đạt trong bộ Windows. DTO Android có kết quả riêng ở 10a; endpoint và chính sách trả dữ liệu thực tế vẫn thuộc các mục API còn lại.
  - [x] **02c. Helper ghi sự kiện trong transaction:** MANDATORY, commit/rollback cùng caller và không nuốt lỗi JSON/JDBC; đã kiểm bổ sung JSONB/schema, hai người nhận, FK/CHECK và lỗi serialize trên PostgreSQL 16.16 ở đợt 13. Sự kiện của từng API nghiệp vụ còn phải được kiểm ở 03–09.
  - [x] **02d. Truy vấn chính sách quan hệ:** chủ thể chặn, chặn hai chiều, đủ hai dòng bạn bè không chặn đã kiểm; đợt 13 bổ sung chờ/nhả advisory lock khi commit/rollback bằng hai kết nối PostgreSQL thật. Chưa nghiệm thu các API nghiệp vụ quan hệ.
- [x] **03. API tìm người bằng SĐT:** server kiểm E.164 và danh tính từ JWT, nối `LookupRateLimiter`; xác minh `400 PHONE_INVALID`, `400 SELF`, `404 NOT_FOUND` cho các trường hợp cần che hồ sơ và `429 RATE_LIMITED` kèm thời gian chờ; trả đúng trạng thái quan hệ và ID/chiều lời mời. Chuẩn hóa ô nhập phía Android được nối tại mục 17.
  - Đợt 15: đối chiếu đủ 22 testcase API đạt từ XML/log Windows mới, dùng JWT/filter thật và PostgreSQL 16.16; kiểm privacy/block hai chiều, exact match, trạng thái/ID/chiều pending, giới hạn theo actor và không ghi dữ liệu. Nghiệm thu backend, chưa xác nhận giao diện Android.
- [x] **04. Gửi lời mời kết bạn:** cấm tự gửi, gửi khi có block, gửi khi đã là bạn; xử lý trùng cùng chiều và ngược chiều bằng `409 REQUEST_ALREADY_PENDING`; hai yêu cầu đồng thời chỉ tạo một lời mời chờ; ghi sự kiện cho đúng hai bên trong cùng transaction.
  - Đợt 15: xác nhận 20 testcase API PostgreSQL và 2 testcase validation body đạt trong bộ 117 mới; hai ca đồng thời cùng/ngược chiều, JSONB cho đúng hai người nhận và CHECK gây lỗi sự kiện thứ hai/rollback/retry đều đạt. Không tự accept lời mời ngược chiều.
- [x] **05. Xem lời mời đã nhận và đã gửi:** chỉ trả lời mời liên quan tài khoản hiện tại, lọc chiều/trạng thái đúng; không tiết lộ SĐT trong hồ sơ tối thiểu của lời mời; không cho người thứ ba đọc dữ liệu trái quyền.
  - Đợt 17: đối chiếu đủ 17 testcase API/JWT/PostgreSQL đạt trong bộ Windows 159 test. Đã kiểm chủ danh sách từ JWT, chiều/trạng thái, cách ly người thứ ba, hồ sơ tối thiểu, dữ liệu inactive/privacy, filter sai, thứ tự ổn định và API đọc không ghi dữ liệu.
- [x] **06. Chấp nhận lời mời:** chỉ người nhận được thực hiện, kiểm tra block; cập nhật lời mời, tạo hai dòng bạn bè và sự kiện nguyên tử; chấp nhận lặp không tạo trùng; chấp nhận lại lời mời cũ sau unfriend không tạo lại bạn bè; kiểm thử accept đồng thời với block.
  - Đợt 17: xác nhận 25 testcase API/JWT/PostgreSQL đạt, gồm JSONB/event, đồng thời, CHECK/rollback/retry và luồng tích hợp 03–06. Hai ca accept/block kiểm với fixture transaction SQL dùng khóa cặp; đây là nghiệm thu hành vi accept, chưa nghiệm thu HTTP/sự kiện của API block mục 09. Giữ yêu cầu kiểm tích hợp với API block thật khi làm 09.
  - Đợt 21: đóng yêu cầu bổ sung accept/block bằng testcase `actualAcceptAndBlockHaveConsistentFinalState` đạt trong ContactBlockApiTest; cả hai thao tác đi qua endpoint bằng MockMvc/JWT với PostgreSQL thật (không thay API block bằng fixture SQL).
- [x] **07. Từ chối và hủy lời mời:** chỉ người nhận được reject, chỉ người gửi được cancel; kiểm chuyển trạng thái hợp lệ, thao tác lặp và ghi sự kiện; accept sau reject/cancel phải bị từ chối nhất quán.
  - Đợt 19: đối chiếu đủ 27 testcase HTTP/JWT/PostgreSQL đạt trong bộ Windows 215; vai trò/trạng thái, lặp, JSONB hai bên, CHECK/rollback/retry, tranh accept/reject/cancel và luồng gửi lại đều đạt.
- [x] **08. Danh sách bạn bè và hủy kết bạn:** trả đúng bạn bè của tài khoản; unfriend cập nhật cả hai chiều, bảo toàn dòng chặn và hạ `is_friend` trên dòng chặn còn lại; ghi sự kiện cho trạng thái thực sự thay đổi, không tạo trùng khi thao tác lặp.
  - Đợt 19: xác nhận đủ 29 testcase HTTP/JWT/PostgreSQL đạt; danh sách/DTO/quyền, block/asymmetry, thao tác lặp, rollback và unfriend đồng thời/lịch sử ACCEPTED đều đạt. Race block vẫn dùng fixture SQL; phải kiểm lại với HTTP block thật ở 09.
  - Đợt 21: đóng yêu cầu bổ sung unfriend/block bằng testcase `actualUnfriendAndBlockCannotLoseBlockingDecision` đạt trong ContactBlockApiTest; kiểm hai endpoint bằng MockMvc/JWT/PostgreSQL, bảo toàn quyết định chặn và không lộ phone.
- [x] **09. Chặn, danh sách chặn và bỏ chặn:** lưu quyết định chặn một chiều nhưng cản tương tác hai chiều; hủy lời mời chờ khi chặn; kiểm chặn người lạ, chặn bạn, hai bạn cùng chặn rồi bỏ chặn và unfriend trong lúc chặn; bỏ chặn không khôi phục quan hệ đã hủy.
  - Đợt 20: đưa ba endpoint còn lại và block/unblock từ nháp vào phạm vi commit; thêm 34 test HTTP/JWT/PostgreSQL, gồm race với API accept/send/unfriend thật. Đã biên dịch, chưa chạy PostgreSQL mới do môi trường agent; giữ `[ ]`.
  - Đợt 21: đối chiếu 34/34 testcase và log PostgreSQL 16.16 đạt, không bỏ qua; quyền/privacy, pending/event, CHECK/rollback/retry, mutual block/unblock và race endpoint accept/send/unfriend đều đạt. Bằng chứng ở bảng và liên kết cuối báo cáo; cách gọi endpoint của suite là MockMvc, không phải TCP tới Tomcat.

### Dữ liệu Android

**Phụ thuộc bắt buộc:** thiết kế và kiểm chứng cơ chế phiên, executor và phản hồi cũ của mục 14 trước khi ghi cache trong mục 12–13. Fake Repository và Injection được mở rộng cùng các phần sử dụng chúng.

- [x] **10. Retrofit, DTO Android và ánh xạ lỗi:** khai báo đủ 11 endpoint, dùng client/Bearer hiện có; ánh xạ đúng JSON backend, `isContact`, trạng thái quan hệ, ID/chiều lời mời, `retryAfterSeconds`; có kiểm thử tương thích hợp đồng và lỗi mạng.
  - Đợt 20: giữ DTO/Retrofit/mapper đã có, thêm NETWORK/INVALID_RESPONSE và 10 test transport. 48 regression app cùng 4 ca mới không cần socket đạt; 6 ca TCP và gate Retrofit → Tomcat/JWT/PostgreSQL còn chờ Windows. Cổng -AndroidContract bắt buộc chạy đủ 58 test app và 11 endpoint thật, chưa chốt toàn mục.
  - Đợt 21: hai gate client đạt, marker 58/58 JUnit4 không ignored; gồm đủ 10 test transport (6 TCP + 4 typed/guard). Gate dùng source app thật gọi đủ 11 endpoint qua Retrofit/Gson → Tomcat/JWT/PostgreSQL, kiểm lỗi 401/404/409/429 và mất kết nối. Nghiệm thu DTO/interface/mapper và hợp đồng JVM; Bearer trong gate do interceptor fixture gắn, chưa kiểm AuthInterceptor/Keystore trên thiết bị hoặc Repository/cache/UI.
  - [x] **10a. DTO Android và JSON Gson:** đọc đúng lookup, lời mời, danh sách contact và details của lỗi 409/429; body gửi chỉ gồm addresseeId; 10 test Gson đạt. Phạm vi DTO; kiểm hợp đồng Retrofit được ghi riêng ở 10b.
  - [x] **10b. Interface Retrofit cho 11 endpoint:** kiểm method/path/query/body, chuyển JSON sang DTO/List, 201/204, giữ lỗi HTTP 409/429 và truyền IOException; 16 test dùng Retrofit/OkHttp/Gson thật với interceptor giả lập đạt. Chưa kiểm backend/JWT; bộ ánh xạ HTTP được ghi riêng ở 10c.
  - [x] **10c. Bộ ánh xạ lỗi HTTP độc lập:** đọc code/details sang ContactsResult, giữ HTTP_<status> khi thiếu code hoặc body sai, không ép metadata sai kiểu, đóng errorBody; 22 test mới đạt. Chưa nối vào Repository, chưa hoàn thành xử lý lỗi mạng/phiên hoặc thông báo UI.
- [x] **11. Room DAO và cập nhật cache bằng transaction:** đối soát để xóa dữ liệu hết hiệu lực, giữ hồ sơ và quyền riêng tư khi DTO thiếu trường; lưu dòng chặn và hồ sơ tối thiểu nguyên tử; giữ schema v1 theo kế hoạch; kiểm truy vấn và rollback bằng Room in-memory thật.
  - Đợt 22: tái sử dụng DAO/JOIN nháp, thêm ContactsCache.replace và tăng ContactsRoomTest từ 2 lên 10 ca. Giữ schema v1, merge không sửa input và không nuốt xung đột UNIQUE phone. Mới có bằng chứng biên dịch; chưa chạy Room thật, giữ `[ ]`.
  - Đợt 23: đủ 10/10 ContactsRoomTest cùng 3/3 RoomFoundationTest trên emulator-5554, không skipped; xác nhận đối soát, merge/privacy, transaction rollback/UNIQUE, JOIN/count và schema v1. Generated Room UserDao_Impl dùng INSERT/UPDATE OR ABORT và transaction. Bằng chứng XML ở cuối báo cáo.
- [x] **12. Repository cho tìm kiếm và lời mời:** nối REST với Room, bảo vệ callback theo phiên và độ mới; cập nhật lời mời sau thao tác; accept không tự tạo bạn từ lịch sử `ACCEPTED` mà đối soát danh sách server; giữ các chữ ký cũ còn được sử dụng.
  - Đợt 24: giữ chữ ký nháp, dùng ContactErrorMapper đã nghiệm thu, kiểm DTO/actor/trạng thái trước ghi, lưu pending đã được ACK và đối soát ba danh sách; accept chỉ xóa pending, không tự tạo friend. Có test callback cũ tại NETWORK/DISK/main và lookup mới nhất. Chưa có kết quả Windows của bộ Repository, giữ `[ ]`.
  - Đợt 25: đủ 43/43 Repository JVM và 12/12 Repository Room/Retrofit/TCP Android đạt trong cùng bộ Windows; luồng send/cancel qua socket thật đối soát Room, accept lịch sử không tạo friend, guard trước NETWORK/DISK/main và lookup mới nhất đều đạt. Chi tiết XML và giới hạn fixture ở cuối báo cáo.
- [x] **13. Repository cho bạn bè và chặn:** cập nhật cache đúng sau unfriend/block/unblock; phản hồi tải lại cũ không ghi đè thao tác mới; phân biệt thao tác đã lưu nhưng refresh thất bại với kết quả ghi chưa xác định do mất phản hồi; không suy đoán quan hệ bạn bè.
  - Đợt 24: patch mutation đã xác nhận trên DISK FIFO chung với clear phiên; snapshot cũ bị loại bởi revision, lỗi một phần giữ snapshot cũ, LiveData cũ bị che khi đổi phiên. `isSuccess + refreshRequired` khác lỗi `NETWORK/INVALID_RESPONSE + refreshRequired`; không tự retry. 43 test Repository JVM đạt trực tiếp; 12 test Room/Retrofit/TCP vẫn chờ Android, giữ `[ ]`.
  - Đợt 25: kiểm lại bằng Room thật cho snapshot lỗi/UNIQUE rollback, block–unfriend–unblock, mất ACK không retry, đổi phiên khi chờ DISK, che dữ liệu trước cleanup, snapshot cũ không ghi đè block mới và patch lỗi nhưng ACK vẫn được giữ. Tất cả ca đều đạt, không skipped; FIFO NETWORK/DISK dùng chung với phiên đã được giữ trong Injection.
- [x] **14. Cô lập phiên, offline, Fake Repository và Injection:** tái sử dụng Auth/SessionManager; kiểm cả ID tài khoản và phiên, gồm đăng xuất rồi đăng nhập lại cùng tài khoản; dọn Room và bộ nhớ tạm, loại callback cũ; mất mạng giữ cache và phiên; Fake phản ánh đúng nghiệp vụ, kiểm thử cũ vẫn đạt; dùng chung executor ghi cache với dọn phiên.
  - Đợt 22: khóa actor/generation không chứa JWT, chặn callback/ghi hồ sơ/401 trễ kể cả cùng actor và token, dọn cache qua DISK chung; Fake có phiên thực sự và dọn LiveData cũ. 25 test Auth/Fake cùng 48 hồi quy client đạt trực tiếp; 14 test SessionManager/AuthInterceptor/Keystore và 3 Room foundation còn chờ thiết bị. Chưa nghiệm thu toàn mục; không dùng kết quả này để nghiệm thu Repository 12–13.
  - Đợt 23: 14/14 SessionManagerTest trên Android đạt (Keystore, guard/concurrency, cùng actor/token đăng nhập lại, 401 trễ, lỗi vận chuyển); 11 Auth + 6 Fake hồi quy + 8 Fake phiên đều đạt trong bộ JVM 83. Cơ chế nền 14 đã kiểm trước tích hợp ghi cache 12–13; không nghiệm thu các callback/epoch nghiệp vụ trong RealContactsRepository nháp.

### Giao diện và nghiệm thu

- [x] **15. `ContactsViewModel`:** quản lý tải dữ liệu, 5 trạng thái quan hệ, lỗi/thông báo một lần và trạng thái thao tác; xử lý `409`/`429`; giữ ô nhập, bộ lọc và tab qua xoay màn hình; không áp dụng kết quả của phiên cũ.
- [x] **16. Màn hình danh sách bạn bè:** hiển thị/lọc tên, kéo làm mới, hủy kết bạn/chặn có xác nhận; badge chỉ đếm lời mời nhận đến còn `PENDING`; nối các màn con qua `nav_contacts.xml`; giữ tiêu đề và ID tương thích điều hướng hiện tại.
- [ ] **17. Màn hình tìm người:** nối `ContactsValidator` vào ô nhập và gọi backend thật; hiện nút hợp lệ cho `STRANGER`, `OUTGOING_PENDING`, `INCOMING_PENDING`, `FRIEND`, `BLOCKED`; xử lý trạng thái tải, lỗi, thử lại và thời gian chờ; ẩn nút Nhắn tin trong Phase 4.
- [ ] **18. Màn hình lời mời:** hai tab Đã nhận/Đã gửi; đủ accept/reject/cancel và chặn theo quyền; danh sách và badge cập nhật đúng sau thao tác; giữ tab khi xoay màn hình.
- [ ] **19. Màn hình danh sách chặn:** hiển thị dữ liệu online/cache offline, bỏ chặn có xác nhận và đối soát đúng; có empty/loading/error state. Với cả mục 16–19: dùng ViewBinding đúng vòng đời, `ListAdapter + DiffUtil`, Material DayNight và chuỗi Anh–Việt.
- [ ] **20. Nghiệm thu toàn luồng:** chạy Android với backend và PostgreSQL thật, tắt Fake; dùng ít nhất ba tài khoản thử A/B/C để kiểm cả người thứ ba; kiểm quyền, đồng thời, request lặp, offline giữa thao tác, đổi tài khoản/đổi phiên, xoay màn hình, sáng tối và Anh–Việt; chạy bộ backend test, Android unit test, Room test và Lint cần thiết; ghi đúng kết quả và giới hạn còn lại.

### Quy tắc áp dụng ở mỗi đợt

- Kiểm thử ngay từng phần: PostgreSQL thật/nhúng cho nghiệp vụ backend, Room thật cho DAO, unit test cho Repository và ViewModel. Mục 20 là nghiệm thu tổng thể, không phải lúc bắt đầu viết test.
- Tích hợp một luồng thật ngay khi backend và client đủ thành phần để kiểm tra, không chờ tất cả màn hình hoàn tất.
- Mọi thay đổi quan hệ phải ghi `sync_events` trong cùng transaction; WebSocket và `/api/sync` thuộc Phase 5, gọi thoại thuộc Phase 6.
- Từ đợt 10: chọn hai mục cấp chính chưa hoàn thành theo phụ thuộc, hoàn thành mục thứ nhất rồi tiếp tục mục thứ hai trong cùng lượt; tối đa hai mục, không triển khai mục thứ ba. Nghiệm thu toàn mục, không bàn giao chỉ vì xong một phần con. Nếu phụ thuộc chưa hoàn thành thì ưu tiên phụ thuộc vào một trong hai vị trí; nếu gặp trở ngại thực sự không giải quyết được thì ghi bằng chứng, không hạ tiêu chuẩn hoặc tính các phần con thành hai mục hoàn thành.
- Cập nhật duy nhất file báo cáo này, chỉ stage thay đổi thuộc hai mục đã chọn và báo cáo. Theo yêu cầu 19/11/2026: **chỉ commit sau khi hoàn thành và kiểm thử xong; tối đa hai hạng mục cấp chính đã kiểm thử cho một commit**, thay quy tắc ưu tiên một commit/mục trước đó. Author và Committer đều `MeanFen <nghiapp11102006@gmail.com>`, kiểm lại danh tính sau commit. Không sửa lịch sử, không tự push; người dùng tự kiểm tra và push bằng Terminal. Cuối lượt báo trạng thái thật, kiểm thử, khả năng bấm thử UI, commit và trở ngại rồi dừng. Các quy tắc một phần nhỏ/lượt và các lần push ở phần trước là lịch sử.
- Chỉ làm việc trong `D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject`; bảo toàn các bản nháp chưa commit và bản dự phòng hiện có.
- Từ đợt 17, cung cấp lệnh test bằng `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` với đúng switch của bộ test; cấu hình JAVA_HOME và thêm bin vào Path trong tiến trình Terminal. Không yêu cầu đổi ExecutionPolicy toàn máy. Các lệnh gọi trực tiếp trong phần lịch sử trước đó là hướng dẫn cũ.

Checklist này chỉ bổ sung kế hoạch theo dõi; việc thêm checklist không đánh dấu hoàn thành thêm tính năng hoặc tạo kết quả test mới.

## Sửa danh tính commit và đợt 03 — Lỗi thiếu tham số đầu vào

### Kiểm tra và sao lưu trước sửa lịch sử

- Chỉ làm việc trong `D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject`, nhánh `phase4_NP`; không tạo project hoặc worktree mới.
- Danh tính local thực tế trước thao tác đã là `MeanFen <nghiapp11102006@gmail.com>` nhưng Author/Committer trong ba commit cũ vẫn là `Codex <codex@openai.com>`.
- Đã kiểm tra `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL`, `GIT_AUTHOR_DATE`, `GIT_COMMITTER_NAME`, `GIT_COMMITTER_EMAIL`, `GIT_COMMITTER_DATE` và `EMAIL`: không có biến ghi đè trong môi trường được kiểm tra.
- Giữ lịch sử cũ tại ref `refs/backup/phase4-identity-before-50aa491`, trỏ đến `50aa491543dd1e3c705e57c4451f420b427b4958`.
- Lưu và giữ stash `df5bc5a7db4d5f24d8ddc243e576e6cf7752ca29` trước khi sửa, gồm 18 tệp tracked đang thay đổi và 25 tệp untracked; trong đó có checklist báo cáo chưa commit.
- Sau sửa đã apply lại stash và kiểm tra: 18 bản nháp tracked không khác snapshot; hash blob của 25 tệp untracked trùng bản dự phòng. Không drop stash, không sửa nhánh khác.

### Ba commit đã sửa danh tính

| Commit cũ | Commit mới | Nội dung giữ nguyên |
|---|---|---|
| `b6f1b0952dd4f2ffc073e4ed21a14e78df334898` | `ab100ace8126a23cc779223f08f51bfa6966548b` | Chuẩn hóa SĐT |
| `ada152dd6e1b42d028d37c240967ef36b8b9faeb` | `099807fb6e89d065d0e36d33c15141f189f9b30c` | Giới hạn tra cứu |
| `50aa491543dd1e3c705e57c4451f420b427b4958` | `fbefdd603b7cb582daf9033479c71b894f12465c` | Thống nhất thư mục và báo cáo kiểm tra |

- Cả Author và Committer của ba commit mới đều là `MeanFen <nghiapp11102006@gmail.com>`.
- So sánh từng cặp: Git tree SHA và byte thông điệp commit giống hệt; giữ nguyên timestamp, múi giờ và thứ tự commit. Parent SHA nối lại theo lịch sử mới.
- `git diff` giữa snapshot `50aa491` cũ và `fbefdd6` mới trả exit code 0: không có thay đổi nội dung do sửa danh tính.
- Commit nền `f607095` giữ nguyên SHA và danh tính. Các SHA cũ trong phần báo cáo lịch sử được giữ để đối chiếu; bảng trên là ánh xạ hiện tại.

### Đúng một phần nhỏ mới đã hoàn thành

Phạm vi là mục **02a: trả lỗi 400 khi thiếu query bắt buộc**, chưa triển khai endpoint hoặc phần nghiệp vụ khác.

| Tệp | Thay đổi |
|---|---|
| `backend/src/main/java/com/utechat/backend/exception/GlobalExceptionHandler.java` | Bắt `MissingServletRequestParameterException`: `phone` dùng `PHONE_INVALID`, tham số khác dùng `VALIDATION`, HTTP 400, giữ cấu trúc error hiện có |
| `backend/src/test/java/com/utechat/backend/contacts/MissingPhoneParameterTest.java` | Thêm 4 test Spring MVC qua MockMvc standalone với controller fixture chỉ nằm trong test |
| `docs/phase4/01-phone-validation.md` | Giữ checklist đã bổ sung, đánh dấu riêng 02a và ghi kết quả đợt này |

Mục 01 vẫn chưa nghiệm thu: chưa có PostgreSQL khả dụng trong môi trường hiện tại để chạy test migration. Không đánh dấu hoàn tất migration, DTO, khóa cặp hoặc API tìm người.

### Kiểm thử thực tế

- **Trước sửa:** chạy test mới cùng test rate limiter được 10/12 ca đạt. Hai ca thiếu `phone` và thiếu tham số khác tái hiện đúng lỗi: `expected: <400> but was: <500>`.
- **Sau sửa:** biên dịch Java `--release 17` và chạy lại bằng JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3: **12/12 ca đạt**, gồm 8 rate limiter và 4 test mới.
- Bốn test mới kiểm: thiếu phone → 400/PHONE_INVALID; thiếu tham số khác → 400/VALIDATION; có phone → 200 và giữ nguyên giá trị; ApiException cũ → giữ 429/RATE_LIMITED/details.
- Chạy lại `ContactsValidatorTest` bằng JUnit 4.13.2: **28/28 ca đạt**. Tổng **40 ca kiểm thử chọn lọc đạt**.
- Runner ban đầu trong TEMP không còn ở lần gọi tiếp theo; đã tạo lại runner trong `.task-cache/input-error-tests` đang được ignore rồi biên dịch/chạy lại thành công. Runner và class output không đưa vào commit.
- Test dùng Spring Framework 6.1.12, Jackson 2.17.2 và Servlet API 6.1.0 có sẵn trong cache; không thêm dependency vào project. Có cảnh báo SLF4J thiếu logger provider ở runner riêng.
- Chưa chạy full Maven/Gradle build, JWT integration, PostgreSQL, APK hoặc emulator. Controller fixture không phải API tìm người thật; kết quả này không chứng minh các bản nháp còn lại đã hoạt động.

Lệnh chuẩn từ thư mục `backend` khi môi trường Maven đủ dependency:

```powershell
.\mvnw.cmd test "-Dtest=MissingPhoneParameterTest,LookupRateLimiterTest"
```

Lệnh Maven trên chỉ là hướng dẫn chạy lại; kết quả thực tế lần này đến từ JUnit trực tiếp với dependency cache.

### Remote, danh tính CLI và push

- Ref local `refs/remotes/origin/phase4_NP` đang lưu `50aa491`, nhưng đây chỉ là tracking ref cũ, không phải bằng chứng trạng thái server hiện tại.
- `git ls-remote --heads origin phase4_NP` không đọc được server vì lỗi DNS GitHub.
- Đã gọi GitHub CLI đã cài tại `C:/Program Files/GitHub CLI/gh.exe`. `gh auth status` thấy tài khoản active MeanFen nhưng kiểm tra server báo `Error: Get "https://api.github.com/": dial tcp: lookup api.github.com: no such host`. Vì request kiểm tra không kết nối được nên không kết luận token thiếu quyền hoặc hết hạn từ dòng trạng thái CLI.
- `gh api repos/Project-Mobile-TEAM/Project_Mobile/branches/phase4_NP --jq '.commit.sha'` trả nguyên văn:

```text
error connecting to api.github.com
check your internet connection or https://githubstatus.com
```

- GitHub connector đọc ref trả HTTP 404/Not Found trong lần này; không dùng lỗi đó để kết luận nhánh không tồn tại hoặc xác định quyền ghi.
- Đã thử push bình thường, không force, qua Git CLI HTTPS:

```powershell
git push -u origin phase4_NP
```

Nguyên văn lỗi, exit code 1:

```text
fatal: unable to access 'https://github.com/Project-Mobile-TEAM/Project_Mobile.git/': Could not resolve host: github.com
```

- Chưa push thành công và chưa xác minh được commit cuối trên server. Không dùng `--force-with-lease` khi chưa đọc được remote SHA mới nhất. Sau khi mạng hoạt động phải đọc lại remote, xác minh không có thay đổi mới của người khác rồi mới quyết định có cần lease hay không; không dùng `--force`.
- Link nhánh để người dùng kiểm tra: https://github.com/Project-Mobile-TEAM/Project_Mobile/tree/phase4_NP . Không coi link này là bằng chứng đã cập nhật lịch sử mới lên server.

### Điểm dừng sau đợt 03

Chỉ commit handler, test tương ứng và báo cáo này bằng danh tính MeanFen. Các bản nháp khác được bảo toàn ngoài commit. Dừng chờ người dùng yêu cầu tiếp; không tự chuyển sang phần khác của checklist.

## Đợt 04 — Chốt DTO JSON danh bạ và lời mời (02b)

### Phạm vi và tệp thay đổi

Chỉ hoàn thiện hợp đồng DTO backend của mục 02b. Đã đọc các chỗ sử dụng trong controller/service bản nháp và giữ nguyên tên record, constructor cùng accessor để không làm gãy lời gọi hiện có. Đưa DTO đã có trong bản nháp vào phạm vi được kiểm thử riêng, bổ sung chú thích tiếng Việt cho từng record; không thêm logic nghiệp vụ hoặc endpoint.

| Tệp | Thay đổi |
|---|---|
| `backend/src/main/java/com/utechat/backend/dto/ContactDtos.java` | Chốt `UserSummaryDto`, `UserLookupResponse`, `SendContactRequestBody`, `ContactRequestDto`, `ContactDto`; giữ `@JsonProperty("isContact")` và giải thích phạm vi dữ liệu |
| `backend/src/test/java/com/utechat/backend/contacts/ContactDtosTest.java` | 9 test trên DTO thật bằng Jackson, kiểm cả serialize và deserialize |
| `docs/phase4/01-phone-validation.md` | Đánh dấu riêng 02b, ghi bằng chứng và cập nhật quy tắc không tự push |

### Kiểm thử thực tế

| Test mới | Kết quả |
|---|---|
| Hồ sơ tối thiểu chỉ có `id`, `displayName`, `avatarUrl` | Đạt |
| Lookup giữ đúng tên camelCase, `isContact`, các boolean false và trường nullable | Đạt |
| Lookup bạn bè giữ `isContact=true`, không nhầm với lời mời chờ | Đạt |
| Lời mời nhận đến giữ ID và chiều `INCOMING` | Đạt |
| Lời mời gửi đi giữ ID và chiều `OUTGOING` | Đạt |
| Lời mời PENDING giữ hai hồ sơ tối thiểu, không có SĐT và respondedAt chưa có | Đạt |
| Lời mời đã phản hồi giữ nguyên chuỗi thời gian ISO-8601 | Đạt |
| Body gửi lời mời chỉ gồm `addresseeId` | Đạt |
| ContactDto hỗ trợ boolean blocked và phone nullable | Đạt |

- Biên dịch chính các tệp DTO và các thành phần đã nghiệm thu bằng JDK 21 với `javac --release 17 -encoding UTF-8`: đạt.
- Chạy `ContactDtosTest` (9), `LookupRateLimiterTest` (8) và `MissingPhoneParameterTest` (4) bằng JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3: **21 tests successful, 0 failed**.
- Chạy lại `ContactsValidatorTest` bằng JUnit 4.13.2: **OK (28 tests)**.
- Tổng **49 kiểm thử chọn lọc đạt**, gồm **9 mới và 40 hồi quy**. Không bỏ qua test trong các lần chạy này.
- Jackson 2.17.2 và Spring Framework 6.1.12 lấy từ cache hiện có; không thêm dependency hoặc đổi cấu hình build. Runner tạm nằm trong `.task-cache/input-error-tests`, không đưa vào commit. Cảnh báo SLF4J thiếu provider của runner không làm test thất bại.

Lệnh chuẩn từ `backend` khi Maven có đủ dependency:

```powershell
.\mvnw.cmd test "-Dtest=ContactDtosTest,LookupRateLimiterTest,MissingPhoneParameterTest"
```

Lệnh Maven trên là hướng dẫn chạy lại; kết quả lần này được kiểm chứng bằng JUnit trực tiếp với dependency cache.

### Giới hạn và điểm dừng

- Chỉ kiểm hợp đồng JSON trên DTO backend; chưa kiểm tương thích với Gson/DTO Android hoặc phản hồi HTTP từ API thật.
- Test không chứa hoặc chạy controller/service danh bạ bản nháp. Việc service được phép trả SĐT khi nào vẫn phải kiểm chứng ở các phần nghiệp vụ tương ứng.
- Record không tự xác thực UUID, trạng thái quan hệ hay quyền thao tác; trách nhiệm đó nằm ở các lớp biên/service sẽ hoàn thiện sau.
- Mục 02 tổng thể và mục 01 migration vẫn chưa hoàn thành; không đánh dấu các phần chưa kiểm chứng.
- Chưa chạy full Maven/Gradle build, PostgreSQL, APK hoặc emulator.
- Chỉ commit ba tệp liệt kê trên bằng danh tính MeanFen. Các bản nháp còn lại được giữ ngoài commit.
- Không gọi GitHub, fetch hoặc push trong đợt này. Dừng sau khi báo phần đã làm, kết quả test và mã commit để người dùng tự kiểm tra và push bằng Terminal.

## Đợt 05 — Helper ghi sync_events trong transaction (02c), 18/11/2026

### Phạm vi và tệp thay đổi

Chỉ đưa helper `SyncEventWriter` trong bản nháp vào phần được nghiệm thu riêng. Đã đối chiếu hai chỗ gọi trong `ContactsService` bản nháp và giữ nguyên constructor, chữ ký `write` và câu INSERT. Không mở rộng sang nghiệp vụ quan hệ, API hoặc đồng bộ realtime.

| Tệp | Thay đổi |
|---|---|
| `backend/src/main/java/com/utechat/backend/service/SyncEventWriter.java` | Ghi sự kiện bằng tham số JDBC và JSON Jackson; `@Transactional(MANDATORY)` để tham gia transaction hiện có; ném lỗi serialize thay vì bỏ qua sự kiện; bổ sung chú thích tiếng Việt |
| `backend/src/test/java/com/utechat/backend/contacts/SyncEventWriterTest.java` | 7 test dùng Spring proxy, DataSourceTransactionManager và JDBC thật trên H2 in-memory |
| `backend/pom.xml` | Thêm H2 ở scope test để chạy kiểm thử transaction; không thay datasource hoặc dependency runtime production |
| `docs/phase4/01-phone-validation.md` | Đánh dấu riêng 02c, ghi kết quả và giới hạn của test H2 |

Helper không tự tạo transaction độc lập. Khi caller rollback, các sự kiện cũng bị rollback; lỗi JSON hoặc JDBC được ném ra để không commit dữ liệu nghiệp vụ mà thiếu sự kiện.

### Kiểm thử thực tế

| Test mới | Kết quả |
|---|---|
| Gọi qua Spring proxy khi không có transaction bị từ chối và không ghi dòng nào | Đạt |
| Sự kiện và marker nghiệp vụ cùng commit; recipient, loại, entity, ID và JSON Unicode được ghi đúng | Đạt |
| Caller rollback xóa cả hai sự kiện đã ghi và marker nghiệp vụ | Đạt |
| Lỗi serialize JSON rollback thay đổi nghiệp vụ trước đó | Đạt |
| Caller bắt lỗi serialize nhưng transaction vẫn rollback-only, không commit được | Đạt |
| Lỗi ràng buộc JDBC không bị nuốt và rollback thay đổi nghiệp vụ | Đạt |
| Payload lời mời giữ hai hồ sơ tối thiểu, không có phone | Đạt |

- Biên dịch chính helper, test và các thành phần đã nghiệm thu bằng JDK 21, `javac --release 17 -encoding UTF-8`: đạt.
- JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3 chạy `SyncEventWriterTest` (7), `ContactDtosTest` (9), `LookupRateLimiterTest` (8), `MissingPhoneParameterTest` (4): **28 tests successful, 0 failed**.
- Chạy lại `ContactsValidatorTest` bằng JUnit 4.13.2: **OK (28 tests)**.
- Tổng **56 test chọn lọc đạt**, gồm **7 mới và 49 hồi quy**, không có test bị bỏ qua.
- Dùng H2 2.2.224, Spring Framework 6.1.12, Jackson 2.17.2 từ cache. Runner nằm trong `.task-cache/input-error-tests`, không đưa vào commit. Có cảnh báo SLF4J thiếu provider trong runner riêng; không làm test thất bại.

Lệnh chuẩn từ `backend` khi Maven đủ dependency:

```powershell
.\mvnw.cmd test "-Dtest=SyncEventWriterTest,ContactDtosTest,LookupRateLimiterTest,MissingPhoneParameterTest"
```

Lệnh Maven là hướng dẫn chạy lại, chưa được xác nhận chạy thành công trong đợt này. Bằng chứng thực tế là JUnit trực tiếp với dependency cache, Spring transaction proxy và H2 JDBC.

### Giới hạn và điểm dừng

- Fixture H2 dùng bảng `sync_events` tối thiểu và domain `JSONB AS CHARACTER VARYING` để kiểm nội dung chuỗi JSON và transaction; không thực thi migration V1/V4, CHECK/FK/RLS hoặc kiểu JSONB của PostgreSQL.
- Kết quả rollback/commit trên H2 không thay thế nghiệm thu trên PostgreSQL/Supabase. Chưa kiểm đồng thời, thứ tự sự kiện, cursor hoặc realtime.
- Caller phải lấy helper qua Spring để `@Transactional(MANDATORY)` có hiệu lực; tạo bằng `new` sẽ không đi qua proxy. Test đợt này dùng bean Spring thật.
- Chưa kiểm việc các thao tác send/accept/reject/cancel/block/unblock/unfriend thực sự ghi đủ sự kiện. Phần này thuộc các mục nghiệp vụ trong checklist.
- Mục 01 và mục 02 tổng thể vẫn chưa đánh dấu hoàn thành. Chưa chạy full Maven/Gradle build, PostgreSQL, APK hoặc emulator.
- Chỉ commit bốn tệp liệt kê trên với Author và Committer `MeanFen <nghiapp11102006@gmail.com>`; các bản nháp khác vẫn giữ ngoài commit.
- Không push, không sửa lịch sử hoặc chuyển sang phần tiếp theo trong đợt này. Dừng cho người dùng tự kiểm tra và push bằng Terminal.

## Đợt 06 — Chính sách chặn và quan hệ bạn bè (02d), 18/11/2026

### Phạm vi và tệp thay đổi

Chỉ nghiệm thu phần truy vấn chính sách quan hệ trong `ContactPolicyService` từ bản nháp. Đã kiểm tra các chỗ gọi trong `ContactsService` bản nháp và giữ tương thích các chữ ký hiện có; không commit controller/service nghiệp vụ hoặc migration của bản nháp.

| Tệp | Thay đổi |
|---|---|
| `backend/src/main/java/com/utechat/backend/service/ContactPolicyService.java` | Bổ sung `isBlockedBy`, chốt `isBlockedEitherWay`, chuyển `areAcceptedFriends` sang một câu SQL kiểm đủ hai dòng `is_friend=true, blocked=false`; giữ phần khóa cặp có sẵn và ghi rõ giới hạn kiểm chứng |
| `backend/src/test/java/com/utechat/backend/contacts/ContactPolicyServiceTest.java` | 8 test trên H2 JDBC, không giả lập hàm advisory lock PostgreSQL |
| `docs/phase4/01-phone-validation.md` | Đánh dấu riêng 02d, cập nhật kết quả và các phần chưa nghiệm thu |

`isBlockedBy(A,B)` chỉ xét dòng do A sở hữu. `isBlockedEitherWay(A,B)` xét cả hai chiều. `areAcceptedFriends(A,B)` chỉ trả true khi có đủ hai dòng bạn bè không chặn; dựa trên PK `(owner_id, contact_user_id)`, mỗi cặp khác nhau có tối đa hai dòng.

Bản nháp cũ kiểm chặn bằng một query rồi đếm bạn bè bằng query khác. Đợt này gộp điều kiện vào một query để không ghép hai kết quả từ trước và sau thay đổi chặn. Điều này không thay thế việc lấy khóa cặp và transaction ở thao tác ghi.

### Kiểm thử thực tế

| Test mới | Kết quả |
|---|---|
| Người lạ không có quan hệ bạn bè hoặc chặn | Đạt |
| Một dòng chặn có đúng một chủ sở hữu nhưng cản tương tác cả hai chiều | Đạt |
| Chặn liên quan tài khoản thứ ba không ảnh hưởng cặp bạn bè đang xét | Đạt |
| Chỉ có một dòng bạn bè thì chưa được coi là bạn bè hai chiều | Đạt |
| Hai dòng bạn bè không chặn được nhận diện đúng khi đổi thứ tự người dùng | Đạt |
| Chặn bạn bè giữ `is_friend` nhưng cấm tương tác bạn bè | Đạt |
| Hai người bạn cùng chặn nhau vẫn giữ cờ bạn bè nhưng chưa được tương tác | Đạt |
| Gọi `lockPair` ngoài transaction bị guard từ chối trước khi truy vấn JDBC | Đạt |

- Biên dịch Java với `javac --release 17 -encoding UTF-8`: đạt.
- Chạy JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3: **36 test backend đạt**, gồm 8 policy, 7 sync writer, 9 DTO, 8 rate limiter và 4 missing parameter; 0 thất bại, 0 bỏ qua.
- Sau khi gộp truy vấn kiểm tra bạn bè thành một câu SQL, đã biên dịch lại và chạy lại đủ 36 test backend: đạt.
- Chạy lại `ContactsValidatorTest` bằng JUnit 4.13.2: **OK (28 tests)**.
- Tổng **64 test chọn lọc đạt**, gồm **8 mới và 56 hồi quy**. Không suy ra toàn bộ ứng dụng đã pass.
- Dùng H2 2.2.224 và Spring Framework 6.1.12 từ cache cùng dependency đã có; không thêm dependency mới. Runner tạm ở `.task-cache/input-error-tests`, không commit. Runner riêng có cảnh báo SLF4J thiếu provider nhưng test đạt.

Lệnh chuẩn từ `backend` khi Maven đủ dependency:

```powershell
.\mvnw.cmd test "-Dtest=ContactPolicyServiceTest,SyncEventWriterTest,ContactDtosTest,LookupRateLimiterTest,MissingPhoneParameterTest"
```

Lệnh Maven trên là hướng dẫn chạy lại, chưa được xác nhận chạy thành công trong đợt này. Bằng chứng thực tế đến từ chạy JUnit trực tiếp với các JAR trong cache và H2 JDBC.

### Giới hạn và điểm dừng

- Test H2 tạo bảng `contacts` tối thiểu để chạy các predicate đọc; không chạy migration V1/V4, FK/RLS hoặc nghiệm thu schema PostgreSQL.
- `lockPair` vẫn giữ logic PostgreSQL từ bản nháp để tương thích caller: yêu cầu transaction và dùng khóa của cặp UUID chuẩn hóa. Test đợt này chỉ xác minh guard ngoài transaction, không gọi `pg_advisory_xact_lock`.
- Chưa kiểm chứng lấy/nhả advisory lock, tranh chấp đồng thời hoặc deadlock trên PostgreSQL; chưa đánh dấu hoàn thành phần khóa cặp của mục 02.
- Chưa chạy luồng block/unblock/unfriend thật; các test chỉ kiểm dữ liệu fixture và quyết định chính sách. Chưa kiểm trạng thái ACTIVE/DELETED hay phân quyền JWT ở API.
- Mục 01 và mục 02 tổng thể vẫn chưa hoàn thành. Chưa chạy full Maven/Gradle build, PostgreSQL, APK hoặc emulator.
- Chỉ commit ba tệp trên với Author và Committer `MeanFen <nghiapp11102006@gmail.com>`. Các bản nháp khác được bảo toàn ngoài commit.
- Không push, không sửa lịch sử, không tạo project/worktree hoặc báo cáo khác. Dừng để người dùng tự kiểm tra và push bằng Terminal.

## Đợt 07 — DTO Android đọc hợp đồng JSON (10a), 18/11/2026

### Phạm vi và tệp thay đổi

Đã kiểm tra lại các đường dẫn PostgreSQL phổ biến, dependency nhúng trong Maven cache và npm cache; chưa tìm được engine PostgreSQL dùng để nghiệm thu migration trong môi trường này. Vì vậy không đánh dấu mục 01 hoặc thử thay migration PostgreSQL bằng H2.

Đợt này chỉ chốt DTO Android cho JSON đã thống nhất ở backend. Đã đọc các chỗ dùng trong API, Repository, Fake và UI bản nháp; giữ nguyên tên class, field, constructor và kế thừa để không làm gãy lời gọi hiện có. Không triển khai thêm endpoint hoặc ghi Room.

| Tệp | Thay đổi |
|---|---|
| `app/src/main/java/com/utechat/data/remote/model/ContactDtos.java` | Đưa DTO Gson từ bản nháp vào phần được kiểm thử riêng; bổ sung chú thích về hồ sơ tối thiểu, phone nullable, thời gian và tên trường direction |
| `app/src/test/java/com/utechat/data/remote/model/ContactDtosTest.java` | 10 test dùng Gson thật với JSON fixture theo hợp đồng backend |
| `docs/phase4/01-phone-validation.md` | Đánh dấu riêng 10a, ghi kết quả và giới hạn, giữ mục 10 tổng thể chưa hoàn thành |

Không thêm dependency: Gson đã có qua Retrofit converter của Android. DTO vẫn độc lập với Entity Room và framework Android. `pendingRequestDirection` thuộc lookup; `error.details.direction` thuộc lỗi 409, không dùng lẫn hai tên trường.

### Kiểm thử thực tế

| Test mới | Kết quả |
|---|---|
| Lookup người lạ đọc boolean false và các trường pending null | Đạt |
| Lookup bạn bè đọc đúng isContact=true, avatar và trạng thái FRIEND | Đạt |
| Lookup lời mời nhận đến giữ đúng ID và chiều INCOMING | Đạt |
| Lookup lời mời gửi đi giữ đúng ID và chiều OUTGOING | Đạt |
| Lời mời pending đọc hai hồ sơ Unicode tối thiểu và respondedAt null | Đạt |
| Lời mời đã phản hồi giữ nguyên chuỗi thời gian ISO-8601 | Đạt |
| Danh sách contact đọc blocked boolean và phone/avatar nullable | Đạt |
| Body gửi lời mời chỉ có addresseeId | Đạt |
| Lỗi 429 giữ code và retryAfterSeconds | Đạt |
| Lỗi 409 giữ existingRequestId/direction và chấp nhận metadata bổ sung | Đạt |

- Biên dịch Java với `javac --release 17 -encoding UTF-8`: đạt.
- JUnit 4.13.2 với Gson 2.10.1 chạy `com.utechat.data.remote.model.ContactDtosTest` (10) và `ContactsValidatorTest` (28): **OK (38 tests)**.
- Biên dịch lại và chạy các test backend đã nghiệm thu bằng JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3: **36 tests successful, 0 failed, 0 skipped**.
- Tổng **74 test chọn lọc đạt**, gồm **10 mới và 64 hồi quy**.
- Hồi quy backend dùng Spring Framework 6.1.12, Jackson 2.17.2 và H2 2.2.224 từ cache như các đợt trước; không mở rộng phạm vi nghiệm thu PostgreSQL.
- Runner/output biên dịch trong `.task-cache`, không commit. Cảnh báo SLF4J thiếu provider chỉ xuất hiện ở runner backend riêng; các test đạt.

Lệnh chuẩn từ thư mục gốc khi môi trường Gradle đủ điều kiện:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.utechat.data.remote.model.ContactDtosTest" --tests "com.utechat.ui.contacts.ContactsValidatorTest" --no-daemon
```

Lệnh Gradle trên là hướng dẫn chạy lại, chưa được xác nhận thành công trong đợt này. Kết quả thực tế là JUnit trực tiếp với các JAR trong cache.

### Giới hạn và điểm dừng

- Test Android dùng JSON fixture theo tên trường đã chốt; chưa gọi trực tiếp backend hoặc kiểm end-to-end Jackson → HTTP/Retrofit → Gson.
- Đọc DTO lỗi không đồng nghĩa đã ánh xạ đầy đủ HTTP/lỗi mạng sang ContactsResult hoặc thông báo UI; phần đó vẫn ở các mục còn lại.
- Chưa validate UUID/trạng thái nghiệp vụ trong DTO, parse ISO-8601 sang millis, ghi Room hoặc xử lý callback đổi phiên.
- Kiểm trường hồ sơ tối thiểu chỉ áp dụng model DTO và fixture; chưa nghiệm thu chính sách trả SĐT của service thật.
- Mục 01, phần khóa cặp PostgreSQL của mục 02 và mục 10 tổng thể vẫn chưa hoàn thành. Chưa chạy full Maven/Gradle build, PostgreSQL, APK hoặc emulator.
- Chỉ commit ba tệp liệt kê trên với Author và Committer `MeanFen <nghiapp11102006@gmail.com>`; các bản nháp khác được bảo toàn ngoài commit.
- Không push hoặc triển khai phần tiếp theo. Dừng để người dùng tự kiểm tra và push bằng Terminal.

## Đợt 08 - Hợp đồng Retrofit cho 11 endpoint danh bạ (10b), 18/11/2026

### Phạm vi và tệp thay đổi

Chỉ hoàn thành interface REST của Android, tiếp nối DTO ở đợt 07. Đã đối chiếu đường dẫn và mã phản hồi với `docs/API_CONTRACT.md`, quy tắc URL/Bearer với `docs/DEPLOYMENT_SECURITY.md` và chữ ký controller bản nháp. Không đưa controller hoặc service bản nháp vào commit này.

| Tệp | Thay đổi |
|---|---|
| `app/src/main/java/com/utechat/data/remote/api/ContactApi.java` | Chốt 11 endpoint từ bản nháp, giữ chữ ký đang được gọi, dùng import và chú thích tiếng Việt; đường dẫn tương đối `api/...`, query phone được Retrofit mã hóa, JSON tạo lời mời chỉ có người nhận, thao tác 204 dùng `Call<Void>` |
| `app/src/test/java/com/utechat/data/remote/api/ContactApiTest.java` | 16 test JUnit chạy Retrofit, OkHttp và Gson thật; interceptor ghi request và trả fixture trước tầng socket |
| `docs/phase4/01-phone-validation.md` | Cập nhật checklist 10a/10b, kết quả chạy và giới hạn; mục 10 tổng thể vẫn chưa hoàn thành |

Không thêm dependency hoặc tạo client riêng trong mã sản phẩm. Interface được thiết kế để tạo từ `ApiClient` hiện có với URL cấu hình tập trung và interceptor Bearer. Test dùng Retrofit riêng không có token; không phải bằng chứng đã nghiệm thu tích hợp `ApiClient`/JWT.

`requests()` hiện lấy toàn bộ danh sách, không tự thêm bộ lọc status/type; chưa bổ sung giao diện gọi lọc trên server. ID trong accept/reject/cancel là ID lời mời; ID trong unfriend/block/unblock là ID tài khoản. Backend vẫn chịu trách nhiệm kiểm UUID, quyền và trạng thái nghiệp vụ.

### Kiểm thử thực tế

| Nhóm test mới | Số test | Kết quả |
|---|---:|---|
| 11 endpoint: method/path và kiểu phản hồi; lookup giữ dấu `+`; send chỉ gửi addresseeId và nhận 201; danh sách có kiểu; accept/reject; cancel/unfriend/block/unblock nhận 204 | 11 | Đạt |
| Path parameter mã hóa slash và khoảng trắng thành một segment | 1 | Đạt |
| HTTP 429 và 409 giữ nguyên status/thân lỗi, không đọc thành DTO thành công | 2 | Đạt |
| Danh sách JSON rỗng là kết quả thành công | 1 | Đạt |
| IOException giả lập truyền lên caller, không trả thành công | 1 | Đạt |

- Biên dịch bằng JDK 21 với `javac --release 17 -encoding UTF-8`: đạt. Đã đổi lời gọi tạo response body của fixture sang overload hiện hành rồi biên dịch/chạy lại.
- JUnit 4.13.2 chạy `ContactApiTest` (16), DTO Android (10) và chuẩn hóa SĐT (28): **OK (54 tests)**.
- Biên dịch lại các nguồn backend đã nghiệm thu và chạy JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3: **36 tests successful, 0 failed, 0 skipped** (8 policy, 7 sync writer, 9 DTO, 8 rate limiter, 4 missing parameter).
- Tổng **90 test chọn lọc đạt**, gồm **16 mới và 74 hồi quy**. Không suy ra toàn bộ ứng dụng đã pass.
- Dùng JAR có sẵn trong cache: Retrofit/converter-gson 2.11.0, OkHttp 4.12.0, Okio JVM 3.6.0, Kotlin stdlib 1.9.0 và Gson 2.10.1. Kotlin stdlib chỉ là dependency runtime của OkHttp/Okio; mã ứng dụng/test vẫn là Java. Không tải hoặc thêm dependency mới.
- Backend dùng Spring Framework 6.1.12, Jackson 2.17.2 và H2 2.2.224 như các đợt trước. Runner backend riêng có cảnh báo SLF4J thiếu provider; các test đạt.
- Runner/classpath/output tạm trong `.task-cache`, không commit. Trước khi chỉnh sửa đã lưu bản interface nháp và hash 38 tệp chưa commit; các tệp ngoài phạm vi được giữ nguyên.

Lệnh Gradle chuẩn để chạy lại từ thư mục gốc khi môi trường đủ điều kiện:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.utechat.data.remote.api.ContactApiTest" --tests "com.utechat.data.remote.model.ContactDtosTest" --tests "com.utechat.ui.contacts.ContactsValidatorTest" --no-daemon
```

Lệnh Gradle trên là hướng dẫn, không được chạy trong đợt này. Bằng chứng thực tế là biên dịch và chạy JUnit trực tiếp bằng JAR trong cache.

### Giới hạn và điểm dừng

- Interceptor trả fixture trước socket: chưa gọi backend, DNS, TLS hoặc HTTP server thật. Test prefix base URL chỉ kiểm cách Retrofit ghép đường dẫn, không xác minh cấu hình Release hay triển khai hosted.
- Không chạy AuthInterceptor, không xác minh gửi Bearer hoặc từ chối JWT thiếu/sai/hết hạn. Chưa kiểm phân quyền server, quyền riêng tư hoặc thao tác giữa các tài khoản thật.
- Giữ thân lỗi 409/429 và truyền IOException chưa phải ánh xạ sang `ContactsResult`/thông báo UI; phần ánh xạ và tích hợp Repository vẫn còn.
- Không ghi Room, sửa phiên, triển khai backend hoặc màn hình. Mục 01, khóa cặp PostgreSQL của mục 02 và mục 10 tổng thể vẫn chưa hoàn thành; chưa chạy full Maven/Gradle build, PostgreSQL, APK hoặc emulator.
- Chỉ commit ba tệp liệt kê trên với Author và Committer `MeanFen <nghiapp11102006@gmail.com>`; giữ nguyên các bản nháp ngoài phạm vi. Không sửa lịch sử, tạo project/worktree hoặc báo cáo khác.
- Không push. Dừng để người dùng tự kiểm tra và push bằng Terminal; chưa triển khai phần tiếp theo.

## Đợt 09 - Bộ ánh xạ lỗi HTTP danh bạ sang ContactsResult (10c), 18/11/2026

### Phạm vi và tệp thay đổi

Chỉ chốt bộ ánh xạ HTTP độc lập, tiếp nối DTO và interface Retrofit. Đã đọc các chỗ dùng ContactsResult ở Repository thật/giả, ViewModel, UI và test bản nháp; chưa có caller nào gọi constructor trực tiếp ngoài factory trong chính class. Bản parser nháp có thể gán code null khiến isSuccess trả true; bộ ánh xạ mới luôn giữ mã HTTP dự phòng nếu thiếu code hợp lệ.

| Tệp | Thay đổi |
|---|---|
| `app/src/main/java/com/utechat/data/repository/ContactsResult.java` | Đưa model kết quả từ bản nháp vào phần được kiểm thử; giữ tên field/factory, thêm chú thích tiếng Việt; constructor riêng, factory error từ chối code null/rỗng; vẫn cho phép thành công không có data |
| `app/src/main/java/com/utechat/data/repository/ContactErrorMapper.java` | Thêm fromHttp cho phản hồi không phải 2xx; đọc code và metadata độc lập, đóng body và giữ HTTP_<status> khi thiếu hợp đồng hoặc không đọc được body |
| `app/src/test/java/com/utechat/data/repository/ContactErrorMapperTest.java` | 22 test JUnit dùng Response Retrofit, parser JSON và stream body thật trong bộ nhớ |
| `docs/phase4/01-phone-validation.md` | Thêm checklist 10c, kết quả kiểm thử và giới hạn; giữ mục 10 tổng thể chưa hoàn thành |

Chỉ nhận code/ID/direction dạng chuỗi không rỗng; không tự ép số hoặc boolean thành chuỗi. retryAfterSeconds phải là số JSON nguyên không âm trong miền int; bỏ qua giá trị âm, thập phân, quá lớn hoặc sai kiểu bằng giá trị mặc định 0. Một metadata sai không làm mất code hay metadata hợp lệ khác. Chưa kiểm UUID hoặc diễn giải trạng thái nghiệp vụ trong bộ ánh xạ.

Không dùng message của server làm thông báo UI; không ghi log body, token hoặc thông tin gỡ lỗi. Bộ ánh xạ tiêu thụ errorBody một lần và đóng body; caller không đọc lại. Nếu đã nhận status HTTP nhưng đọc body gặp IOException thì vẫn giữ lỗi HTTP, không tự đổi thành NETWORK. Phản hồi thành công đưa nhầm vào mapper bị từ chối bằng IllegalArgumentException.

### Kiểm thử thực tế

| Nhóm test mới | Số test | Kết quả |
|---|---:|---|
| 429 giữ code/số giây chờ; 409 giữ ID và hai chiều lời mời | 3 | Đạt |
| Giữ code mới, bỏ qua message và trường bổ sung | 1 | Đạt |
| Code thiếu/null/rỗng/sai kiểu; envelope sai; body rỗng/HTML/JSON bị cắt vẫn là lỗi HTTP | 6 | Đạt |
| Details sai kiểu hoặc metadata không có code không tạo kết quả giả | 2 | Đạt |
| Số giây chờ ở biên int, sai kiểu/thập phân/tràn số và độc lập với metadata lời mời | 3 | Đạt |
| ID/chiều sai kiểu không làm mất trường còn hợp lệ | 1 | Đạt |
| Đóng body khi JSON hợp lệ, JSON lỗi hoặc đọc stream gặp IOException | 3 | Đạt |
| Guard phản hồi 2xx, guard factory lỗi và các kết quả độc lập/không có data | 3 | Đạt |

- Biên dịch nguồn Java bằng JDK 21 với `javac --release 17 -encoding UTF-8 -Xlint:deprecation`: đạt.
- JUnit 4.13.2 chạy mapper/result (22), interface Retrofit (16), DTO Android (10) và chuẩn hóa SĐT (28): **OK (76 tests)**.
- Biên dịch lại nguồn backend đã nghiệm thu và chạy JUnit Platform Launcher 1.10.3 / Jupiter 5.10.3: **36 tests successful, 0 failed, 0 skipped** (8 policy, 7 sync writer, 9 DTO, 8 rate limiter, 4 missing parameter).
- Tổng **112 test chọn lọc đạt**, gồm **22 mới và 90 hồi quy**. Không suy ra toàn bộ ứng dụng đã pass.
- Dùng lại JAR trong cache như đợt 08: Retrofit 2.11.0, OkHttp 4.12.0, Gson 2.10.1 cùng dependency hiện có; không thêm hoặc tải dependency. Backend vẫn dùng fixture H2, không thay cho PostgreSQL. Runner backend riêng có cảnh báo SLF4J thiếu provider; các test đạt.
- Trước khi sửa đã lưu bản ContactsResult nháp và hash 37 tệp chưa commit trong `.task-cache/contact-error-tests`; 36 tệp ngoài phạm vi được giữ nguyên. Runner/classpath/output tạm không commit.

Lệnh Gradle chuẩn để chạy lại khi môi trường đủ điều kiện:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.utechat.data.repository.ContactErrorMapperTest" --tests "com.utechat.data.remote.api.ContactApiTest" --tests "com.utechat.data.remote.model.ContactDtosTest" --tests "com.utechat.ui.contacts.ContactsValidatorTest" --no-daemon
```

Lệnh Gradle trên là hướng dẫn, không được chạy trong đợt này. Kết quả thực tế đến từ javac và JUnit trực tiếp với JAR trong cache.

### Giới hạn và điểm dừng

- Mapper/result đã được kiểm riêng nhưng chưa thay parser cũ trong RealContactsRepository bản nháp. Chưa nghiệm thu luồng Repository, callback/phiên, Room hoặc UI; không đưa cả bản Repository chưa hoàn thiện vào commit.
- Không gọi backend, HTTP server, DNS hoặc TLS thật; không xác minh JWT/Bearer, đăng xuất khi 401 hay quyền server. Chưa ánh xạ IOException trước khi có response, kết quả ghi chưa xác định hoặc refreshRequired trong luồng Repository.
- Metadata sai dùng mặc định 0/null, chưa tạo thời gian đếm ngược hoặc kiểm giá trị nghiệp vụ. Dùng JsonParser của Gson hiện có, chưa bật chế độ parser JSON nghiêm ngặt riêng.
- Mục 01, khóa cặp PostgreSQL của mục 02 và mục 10 tổng thể vẫn chưa hoàn thành. Chưa chạy full Maven/Gradle build, PostgreSQL, APK hoặc emulator.
- Chỉ commit bốn tệp nêu trên với Author và Committer `MeanFen <nghiapp11102006@gmail.com>`. Giữ nguyên các bản nháp khác; không sửa lịch sử, tạo project/worktree hay báo cáo khác.
- Không push. Dừng để người dùng tự kiểm tra và push bằng Terminal; chưa triển khai phần tiếp theo.

## Đợt 10 - Hai hạng mục cấp chính 01 và 02, 18/11/2026

### Phạm vi và trạng thái thực tế

Áp dụng quy tắc mới: tối đa hai mục cấp chính trong một lượt. Chọn 01 trước 02 vì schema is_friend là nền cho chính sách quan hệ, khóa cặp và ghi sự kiện. Không chọn thêm mục thứ ba và không tính các phần con 02a–02d hoặc 10a–10c thành mục cấp chính hoàn thành.

| Mục đã chọn | Trạng thái cuối đợt | Phần còn thiếu để nghiệm thu |
|---|---|---|
| 01. Migration và database chạy thử | **Chưa hoàn thành, bị chặn** | Sửa loader/test đang tách sai khối DO; kiểm database mới, nâng cấp, rollback khi dữ liệu chặn cũ mơ hồ, default/constraint và bộ nạp dev trên PostgreSQL thật/nhúng |
| 02. DTO, lỗi, chính sách, khóa cặp và sự kiện | **Chưa hoàn thành, bị chặn phần PostgreSQL** | Kiểm lock cặp hai chiều, chờ/nhả lock khi commit/rollback, thứ tự trước khóa dòng; kiểm JSONB và nguyên tử dữ liệu/sự kiện trên schema thật; chốt đủ hợp đồng sự kiện và kiểm tích hợp JWT Phase 3 |

**0/2 hạng mục cấp chính được nghiệm thu trong đợt này.** Hai mục cùng phụ thuộc PostgreSQL; các kết quả unit/H2 đã có không thay thế tiêu chuẩn đó. Không thay phiên bản Spring Boot để né dependency thiếu, không dùng H2 để nhận migration hoặc advisory lock đã đạt.

### Đã kiểm tra và tự xử lý môi trường

- Xác nhận thư mục TeamProject, nhánh phase4_NP, HEAD đầu đợt `4dc965a`; index rỗng. Lưu hash của 36 tệp chưa commit trong `.task-cache/phase4-main-01-02/dirty-before.json`; giữ nguyên nội dung tất cả các bản nháp.
- Đọc V1/V3/V4, ContactsMigrationTest, LocalDevServer, schema sync_events, pom.xml, ContactPolicyService, SyncEventWriter và các bản nháp/test liên quan. Chưa sửa migration đã áp dụng hoặc đưa API nghiệp vụ ngoài 01/02 vào commit.
- Không tìm thấy psql/initdb/pg_ctl trong PATH, không có engine Zonky nhúng trong Maven cache. Có driver PostgreSQL JDBC trong cache nhưng driver không phải database engine. Không thấy cấu hình database kiểm thử qua các tên biến môi trường PostgreSQL thông dụng.
- Kiểm hai Docker context default và desktop-linux: cả hai không kết nối được daemon. Thử docker desktop start, sau đó thử mở Docker Desktop.exe trực tiếp với cửa sổ ẩn; lệnh mở trả PID nhưng kiểm tra tiếp vẫn không thấy daemon hoạt động. Không sửa registry hoặc cấu hình hệ thống.
- WSL không cho liệt kê distro. Kiểm bind TCP loopback tạm bằng .NET thành công; không kết luận firewall hỏng hoặc mọi kết nối nội bộ đều bị chặn.
- Thử truy cập POM embedded-postgres 2.0.7 trên Maven Central để có thể tải engine/dependency: lỗi DNS. Chạy Maven offline bằng bản Maven 3.9.16 có sẵn cũng không qua bước đọc POM vì thiếu Spring Boot parent 3.3.5.

Bằng chứng lỗi thực tế:

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine; check if the path is correct and if the daemon is running: open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.

starting Docker Desktop: getting launcher path: cannot find registry key "SOFTWARE\\Docker Inc.\\Docker Desktop": The system cannot find the file specified.

Access is denied.
Error code: Wsl/E_ACCESSDENIED

No such host is known. (repo.maven.apache.org:443)
```

Lỗi parent POM nguyên văn trong lần Maven offline:

```text
Non-resolvable parent POM for com.utechat:backend:1.0.0: The following artifacts could not be resolved: org.springframework.boot:spring-boot-starter-parent:pom:3.3.5 (absent): Cannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact org.springframework.boot:spring-boot-starter-parent:pom:3.3.5 has not been downloaded from it before. and 'parent.relativePath' points at no local POM @ line 6, column 13
```

Lệnh Maven đã thực sự chạy từ backend, bằng executable Maven trong cache:

```powershell
mvn -o -Dmaven.repo.local=C:/Users/Phuocnghia_admin/.m2/repository "-Dtest=ContactsMigrationTest,ContactPolicyServiceTest,SyncEventWriterTest,ContactDtosTest,LookupRateLimiterTest,MissingPhoneParameterTest" test
```

Lệnh thoát mã 1 **trước khi chạy test**. Đây là thiếu dependency/build; lần tải POM trực tiếp lỗi DNS. Không liên quan đến kết nối hoặc quyền GitHub; không có thao tác push trong đợt này.

### Kiểm tra đã chạy và phát hiện trong bản nháp

- Biên dịch lại các nguồn backend đã nghiệm thu bằng javac --release 17 và chạy JUnit Platform Launcher: **36 test đạt, 0 failed, 0 skipped**, gồm policy 8, sync writer 7, DTO 9, rate limiter 8, missing parameter 4. Đây là kiểm thử hồi quy đã có, không có test PostgreSQL mới đạt. Runner có cảnh báo SLF4J thiếu provider như các đợt trước.
- Chạy probe Java với chính ScriptUtils của Spring JDBC 6.1.12 trong cache lên V4 bản nháp: **8 đoạn SQL**, `DO block split before END $$: true`. Đã tái hiện việc splitter dấu chấm phẩy mặc định cắt khối DO trước END. Đây là chẩn đoán bộ nạp SQL, **không phải chạy migration trên PostgreSQL**.
- LocalDevServer và ContactsMigrationTest bản nháp hiện dùng splitter mặc định; vì vậy assertThrows(RuntimeException.class) trong test mơ hồ không đủ để chứng minh lỗi do dữ liệu chặn: cũng có thể bắt nhầm lỗi loader/cú pháp. Cần sửa cả loader và assertion theo lỗi migration mong muốn khi có thể kiểm PostgreSQL, không chỉ thêm test đếm dòng.
- V4 chủ động dừng khi cả hai chiều đều chặn để tránh đoán nhầm bạn bè. Chưa kiểm được hành vi rollback hay nâng cấp thật; không xác nhận migration đã an toàn chỉ từ đọc SQL hoặc probe.
- Không sửa mã sản phẩm/SQL/test bản nháp khi chưa thể kiểm chứng thay đổi nền PostgreSQL. Probe và output tạm chỉ nằm trong .task-cache, không commit. Không chạy lại Android hoặc toàn bộ test ứng dụng vì đợt này chưa thay đổi mã Android.

### Tệp thay đổi, commit và điểm dừng

- Chỉ thay đổi `docs/phase4/01-phone-validation.md`: cập nhật quy tắc đợt mới, trạng thái 01/02, bằng chứng môi trường, phát hiện loader và kết quả thực tế. Không tạo file báo cáo khác.
- Commit đợt này chỉ ghi nhận báo cáo bị chặn, không phải commit hoàn thành 01 hoặc 02; Author và Committer là `MeanFen <nghiapp11102006@gmail.com>`. Không sửa lịch sử, không tạo project/worktree, không push.
- Chưa có chức năng hoặc thay đổi giao diện mới để bấm thử; không tuyên bố đã chạy app với backend thật hoặc Fake trong đợt này.
- Điều kiện để tiếp tục hai mục này: môi trường cho phép chạy một engine PostgreSQL thật/nhúng và có đủ dependency backend 3.3.5 (qua cache hợp lệ hoặc kết nối Maven Central). Sau đó phải sửa và kiểm đầy đủ 01 trước khi nghiệm thu phần PostgreSQL của 02.
- Dừng đợt này với cả hai ô chính vẫn `[ ]`; không triển khai mục thứ ba.

## Đợt 11 - Kiểm tra sau merge main, .env và nghiệm thu mục 01/02, 18/11/2026

### Trạng thái Git và bảo toàn

- Thư mục duy nhất: `D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject`, nhánh `phase4_NP`; HEAD đầu đợt `c5030e8` là merge origin/main. Không có xung đột hoặc thay đổi trong index.
- Xác nhận stash `c1f69ff` vẫn còn. Lưu hash 35 tệp chưa commit trong `.task-cache/phase4-postmerge-01-02/dirty-before.json`; kiểm lại không có tệp nào bị đổi hoặc mất. Giữ nguyên cả tệp chưa theo dõi `tash list`.
- V4 và LocalDevServer không có diff so với HEAD sau merge; không khôi phục bản cũ từ stash, không sửa lịch sử hoặc các migration đã có. `.env` được Git ignore, không tracked, không stage và không in giá trị bí mật.

### Kết quả đọc cấu hình và code mới

- `.env` có 18 biến; đủ nhóm DB/Flyway/runtime. Script có sẵn `backend/scripts/test-db-connection.ps1 -Quick` **đạt toàn bộ kiểm tra được yêu cầu**: cú pháp host, cổng Session 5432/Transaction 6543, JDBC TLS/prepareThreshold, HTTPS Supabase và các tệp schema. Quick chỉ kiểm cấu hình cục bộ, không chứng minh đăng nhập database hoặc migration thành công.
- Main đã thêm dependency Flyway, cấu hình tự migrate/baseline trong application.yml, FlywaySessionPoolerConfig, FlywayMigrationTest và cập nhật test-schema.mjs/verify-schema.sql. Đã đọc các phần này cùng V4, LocalDevServer và test nháp liên quan.
- **V4 từ main khác bản nháp cũ:** backfill mọi is_friend còn NULL bằng NOT blocked; các dòng blocked cũ thành block-only, không còn cơ chế RAISE cho hai chiều cùng chặn. V4 giữ giá trị is_friend đã có, đặt NOT NULL/default FALSE. Không được dùng kết luận lỗi splitter khối DO của bản nháp đợt 10 để mô tả V4 hiện tại.
- **LocalDevServer từ main đã bỏ nạp bằng ScriptUtils**, dựa vào Flyway. Chưa chạy được smoke test PostgreSQL/Flyway của đường khởi động này; chưa xác nhận việc chọn datasource/Flyway URL và đóng engine khi có cấu hình môi trường thực tế.
- **ContactsMigrationTest nháp chưa phù hợp V4 mới:** vẫn kỳ vọng mutual block làm migration ném RuntimeException và blocked friend được giữ is_friend=true. Đây là khác biệt từ đọc code; chưa chạy test PostgreSQL để xác nhận kết quả. Không tự sửa kỳ vọng để tuyên bố pass hoặc ghi đè V4 main bằng bản backup.
- Chính sách backfill cũ của V4 mới không tự chứng minh giữ được lịch sử bạn bè của một dòng đã blocked. Cần kiểm các fixture nâng cấp, ghi rõ trường hợp mất thông tin và đối soát nếu cần; không suy ra friendship từ ACCEPTED cũ hoặc tự chạy migration trên database dùng chung.

### Kiểm thử thực tế sau merge

| Kiểm tra đã chạy | Kết quả | Giới hạn |
|---|---|---|
| Script PowerShell test-db-connection.ps1 -Quick | Đạt | Chỉ cấu hình/tệp cục bộ |
| JDBC PostgreSQL 42.7.3 với FLYWAY_URL/user/password từ .env | Thất bại: SQLState 08001, UnknownHostException | Chưa đến xác thực hoặc truy vấn metadata |
| JDBC PostgreSQL 42.7.3 với SPRING_DATASOURCE_URL/user/password từ .env | Thất bại: SQLState 08001, UnknownHostException | Chưa đến xác thực hoặc truy vấn metadata |
| Maven offline với các test migration và backend liên quan | Thoát 1 trước khi chạy test | Thiếu Spring Boot parent 3.3.5 |
| Tải POM parent từ Maven Central | Lỗi DNS | Không tải được dependency |
| Kiểm Docker daemon | Không kết nối được named pipe | Không có PostgreSQL container để thay thế |
| Tìm PGlite theo đường dẫn tạm hướng dẫn trong repo; thử lấy dependency PGlite 0.3.7 từ npm registry | Không có package tại các vị trí đã kiểm; tải lỗi DNS | Không chạy được test-schema.mjs; không coi việc script tồn tại là kết quả test |
| Biên dịch lại/chạy 36 test backend bằng JUnit Platform | 36 successful, 0 failed, 0 skipped | 8 policy, 7 sync writer, 9 DTO, 8 rate limiter, 4 missing parameter; fixture H2 cho JDBC, không nghiệm thu PostgreSQL |
| Biên dịch lại/chạy các test Android JVM chọn lọc bằng JUnit 4 | OK (76 tests) | 22 mapper/result, 16 Retrofit, 10 DTO, 28 validator; không phải Gradle build hoặc emulator |

**112 test hồi quy chọn lọc đạt sau merge. Không có test PostgreSQL/Flyway mới đạt trong đợt này.** Dùng javac --release 17 và JAR trong cache như các đợt trước, không sửa phiên bản POM để thay parent bị thiếu. Runner backend có cảnh báo SLF4J thiếu provider; test đạt.

Probe JDBC tạm chỉ đọc `.env` trong bộ nhớ, giải quyết tham chiếu biến và đặt timeout, dự kiến chỉ truy vấn metadata trong transaction read-only rồi rollback; thực tế cả hai lần đều lỗi trước khi kết nối được. Không gọi Flyway migrate/repair/clean, không tạo schema hoặc sửa dữ liệu Supabase. Host/credential được che trong log; các tệp probe tạm không chứa giá trị từ `.env` và không commit.

Lệnh/kiểu lỗi tiêu biểu đã chạy từ gốc project (đường dẫn classpath rút gọn ở đây; runner thực tế dùng các JAR cache):

```powershell
.\backend\scripts\test-db-connection.ps1 -Quick
java -cp <probe-classes-and-postgresql-42.7.3.jar> PostgresConfigProbe .env FLYWAY
java -cp <probe-classes-and-postgresql-42.7.3.jar> PostgresConfigProbe .env DATASOURCE
docker version --format '{{json .Server}}'
```

```text
FLYWAY: JDBC failed; SQLState=08001; PSQLException
Cause: java.net.UnknownHostException: <redacted>
DATASOURCE: JDBC failed; SQLState=08001; PSQLException
Cause: java.net.UnknownHostException: <redacted>
Maven Central error: No such host is known. (repo.maven.apache.org:443)
PGlite dependency fetch error: No such host is known. (registry.npmjs.org:443)
```

Maven 3.9.16 từ cache đã chạy từ backend:

```powershell
mvn -o "-Dmaven.repo.local=C:/Users/Phuocnghia_admin/.m2/repository" "-Dtest=ContactsMigrationTest,ContactPolicyServiceTest,SyncEventWriterTest,ContactDtosTest,LookupRateLimiterTest,MissingPhoneParameterTest" test
```

Lỗi dừng trước test: `Non-resolvable parent POM ... spring-boot-starter-parent:pom:3.3.5 (absent): Cannot access central ... in offline mode`. Thiếu dependency và lỗi phân giải DNS là các bằng chứng riêng; không kết luận mật khẩu sai, thiếu quyền database hoặc database hỏng.

### Kết luận nghiệm thu và điểm dừng

- **01 chưa hoàn thành:** đã có schema/loader/Flyway mới từ main và Quick config đạt; chưa kiểm database mới/nâng cấp/default/rollback, chưa xử lý dứt điểm khác biệt test nháp với chính sách backfill, chưa chạy local dev server trên PostgreSQL.
- **02 chưa hoàn thành:** các test DTO/lỗi/helper hiện có vẫn đạt; chưa kiểm advisory lock hai chiều và commit/rollback/concurrency, JSONB với schema thật, hợp đồng sự kiện đầy đủ hoặc JWT tích hợp trên PostgreSQL. Không gộp các phần con thành nghiệm thu toàn mục.
- **0/2 hạng mục cấp chính được nghiệm thu.** Không triển khai mục thứ ba. Chưa có thay đổi UI để bấm thử; chưa chạy APK, emulator, full Maven/Gradle build hoặc test backend thật end-to-end.
- Chỉ cập nhật file báo cáo này, không thay mã sản phẩm, migration hoặc các test nháp chưa nghiệm thu. Commit báo cáo bằng Author và Committer `MeanFen <nghiapp11102006@gmail.com>`; không push, không xóa stash/backup, không tạo project/worktree.
- Điều kiện còn thiếu để tiếp tục: phiên thực thi phân giải/kết nối được host PostgreSQL trong .env hoặc có engine PostgreSQL nhúng cục bộ; đồng thời có đủ dependency backend để chạy test Flyway/JWT/transaction. Giá trị .env có mặt không khắc phục được lỗi DNS của phiên thực thi.

## Đợt 12 - Bộ nghiệm thu PostgreSQL 01–02 và lệnh chạy Windows, 18/11/2026

### Môi trường thực tế và phạm vi

- HEAD đầu đợt `2733376`, nhánh phase4_NP. Lưu hash 35 tệp nháp và sao lưu ContactsMigrationTest cũ trong `.task-cache/phase4-acceptance-01-02`; 34 tệp ngoài phạm vi được giữ nguyên. Stash c1f69ff không bị xóa; không đọc hoặc sửa giá trị .env, không push.
- Xác nhận bằng executable thực tế: **Maven 3.9.16, Oracle JDK 27 (27+35-2325)**. Đặt JAVA_HOME của tiến trình thử vào `C:\Program Files\Java\jdk-27`; javac vẫn dùng --release 17 theo project. Không đổi JDK/POM hoặc hạ dependency để né lỗi.
- Docker của phiên này trả **permission denied** trên named pipe, khác lần trước không có daemon; không suy ra Docker trên Terminal người dùng bị hỏng. Maven Central vẫn lỗi phân giải DNS trong phiên này, dù người dùng đã kiểm tra DNS ở máy Windows.
- Chỉ thực hiện phạm vi 01–02. Chưa nghiệm thu API 03–09, Room/Repository/UI hoặc tính năng tiếp theo.

### Đối chiếu V4 và xử lý khác biệt test

Giữ nguyên V4 đã merge để không thay checksum migration có thể đã áp dụng. Chốt đúng chính sách hiện có của V4: is_friend còn NULL được backfill bằng NOT blocked; dòng blocked cũ trở thành block-only, kể cả khi chiều ngược không chặn; hai chiều blocked không gây lỗi và không suy ra tình bạn từ ACCEPTED lịch sử. Giá trị is_friend đã có rõ ràng được giữ nguyên, kể cả TRUE khi đang blocked.

Đây là lựa chọn bảo thủ về dữ liệu cũ, **không phải phục hồi được mọi tình bạn trước V4**. Quan hệ lịch sử của dòng blocked chưa có cờ rõ ràng có thể mất thông tin; cần đối soát riêng nếu muốn khôi phục, không tự sửa dữ liệu dùng chung trong bộ test này. Test mới kiểm trực tiếp lựa chọn đó và việc giữ blocked, thay vì assertThrows(RuntimeException.class) không còn phù hợp. Rollback được kiểm bằng lỗi CHECK cố ý có SQLSTATE 23514 trong database cô lập, không giả định mutual block phải làm V4 lỗi.

### Tệp thay đổi

| Tệp | Phạm vi và thay đổi |
|---|---|
| `backend/scripts/test-phase4-foundation.ps1` | Chạy Maven/JDK đang chọn, tạo PostgreSQL 16 container riêng trên loopback/cổng ngẫu nhiên, chạy các suite 01–02; kiểm XML mới đủ số test, không failures/errors/skips; dọn đúng container và anonymous volume test trong finally |
| `backend/src/test/java/com/utechat/backend/support/PostgresFixture.java` | Database riêng theo UUID cho mỗi fixture; chỉ nhận URL admin 127.0.0.1/cổng rõ ràng, không credentials/query/fragment; không đọc .env; migrate bằng Flyway và chỉ dọn database do fixture tạo |
| `backend/src/test/java/com/utechat/backend/support/PostgresFixtureGuardTest.java` | Bảy test guard: thiếu URL, host ngoài máy, thiếu cổng, sai database admin, credentials trong URL, sai protocol và options có thể đổi đích kết nối |
| `backend/src/test/java/com/utechat/backend/contacts/ContactsMigrationTest.java` | Thay bản nháp cũ bằng sáu ca PostgreSQL/Flyway: database mới/default, backfill legacy, mutual block/ACCEPTED cũ, giữ cờ đã biết, rollback/retry, baseline V1 nạp tay |
| `backend/src/test/java/com/utechat/backend/config/FlywayMigrationTest.java` | Tái sử dụng ba test từ main, thay fixture nhúng bằng PostgreSQL do script cấp; giữ kiểm URL, baseline và lịch sử V2 seed không có trong classpath |
| `backend/src/test/java/com/utechat/backend/LocalDevServer.java` | Giữ cơ chế Flyway từ main, dùng chung databaseArguments để override cả runtime/Flyway; môi trường không được làm Flyway migrate nhầm database ngoài test |
| `backend/src/test/java/com/utechat/backend/LocalDevServerSmokeTest.java` | Khởi động backend/Flyway thật bằng bộ tham số dev trên database test, HTTP loopback/cổng ngẫu nhiên; kiểm schema V4 và readiness dùng đúng database |
| `backend/src/test/java/com/utechat/backend/contacts/ContactPostgresFoundationTest.java` | 12 ca: guard lock, chờ lock đảo chiều được quan sát ở pg_stat_activity, nhả khi commit/rollback, cặp độc lập, khóa dòng sau khóa cặp, policy UUID/boolean, JSONB, transaction hai người nhận, FK/CHECK và lỗi serialize |
| `backend/src/test/java/com/utechat/backend/contacts/ContactJwtPostgresTest.java` | Tám ca dùng nguyên SecurityConfig/JwtService/filter Phase 3 với PostgreSQL: actor đúng, thiếu/sai/hết hạn token, token tạm, thu hồi thiết bị, token_valid_after và tài khoản không ACTIVE; endpoint thử chỉ có ở profile test |
| `docs/API_CONTRACT.md` | Tài liệu kỹ thuật mục 02: DTO/lỗi, khóa cặp trước khóa dòng và ba loại sync_events, payload, người nhận, nguyên tử/idempotency; không phải báo cáo tiến độ mới |
| `docs/phase4/01-phone-validation.md` | Báo cáo tiến độ duy nhất: bằng chứng, hướng dẫn chạy và trạng thái nghiệm thu |

### Kiểm tra đã thực sự chạy tại phiên này

- javac của **JDK 27**, --release 17: biên dịch được fixture/guard, các test migration/Flyway/policy/JSONB/JWT và các nguồn phụ thuộc chọn lọc bằng JAR cache. JAR cache có Spring 6.1.12, Spring Boot 3.3.3, Flyway core 10.10.0; **không thay thế build Maven theo POM 3.3.5**. Lần thử biên dịch riêng bootstrap/LocalDevServer/Smoke dừng vì cache thiếu package dotenv-java và Zonky; Maven đầy đủ cũng chưa qua parent, nên chưa xác nhận các tệp đó build thành công.
- Đã sửa lỗi overload registerBean trong code test, biên dịch lại cho đạt. Đây là lỗi code/overload, không phải bằng chứng JDK 27 không tương thích. Các lần runner thiếu classpath đã được sửa rồi kiểm tra lại.
- JUnit Platform chạy bằng **JDK 27: 44 tests successful, 0 failed, 0 skipped** = 36 regression backend đã có + 7 guard mới + 1 test đổi URL Flyway từ main. JDBC của regression dùng H2 như trước; không gộp thành kết quả PostgreSQL. Runner có cảnh báo SLF4J thiếu provider như các đợt trước.
- PowerShell Parser kiểm script: **0 lỗi cú pháp**; self-check tách cổng Docker binding đạt. Đã chạy chính script với đường dẫn Maven cache: Maven in đúng Java 27, sau đó script dừng mã 1 ở Docker preflight, chưa tạo container/database. Không có kết quả chạy end-to-end thành công của script.
- Chạy riêng Maven offline với JDK 27 và danh sách test 01–02: dừng trước test vì thiếu parent Spring Boot 3.3.5. Không có thay đổi POM hoặc đổi JDK sau lỗi đó. Tải POM trực tiếp vẫn lỗi DNS.

Lỗi môi trường còn lại:

```text
permission denied while trying to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
No such host is known. (repo.maven.apache.org:443)
Non-resolvable parent POM ... spring-boot-starter-parent:pom:3.3.5 (absent): Cannot access central ... in offline mode
```

Không kết luận lỗi xác thực Docker registry, mật khẩu Supabase hoặc lỗi tương thích Java từ các thông báo trên. Chưa chạy PostgreSQL thành công trong phiên này.

### Lệnh PowerShell chạy trên máy Windows của người dùng

Script không đọc .env, không dùng Supabase, không chạy migration trên database dùng chung. Nó chọn **73 test**: **29 ca cần PostgreSQL** (6 migration, 2 Flyway database, 1 smoke, 12 nền quan hệ/sự kiện, 8 JWT) và **44 ca không cần PostgreSQL** đã nêu trên. Các con số này là phạm vi dự kiến, **không phải kết quả 73 test đã đạt**.

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
.\backend\scripts\test-phase4-foundation.ps1
$LASTEXITCODE
```

Nếu mvn chưa nằm trong PATH, truyền executable Maven 3.9.16 có sẵn trên máy:

```powershell
.\backend\scripts\test-phase4-foundation.ps1 -Maven 'C:\Users\Phuocnghia_admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
```

Giữ JDK 27 trước. Chỉ xem xét đổi JDK nếu có lỗi tương thích thực tế và ghi lại nguyên văn, không đổi vì warning hoặc lỗi DNS/quyền Docker. Nếu thất bại, giữ phần Maven error và summary; XML nằm trong `backend/target/surefire-reports`. Không cần gửi .env, mật khẩu hoặc toàn bộ XML chứa properties để báo kết quả.

### Điều kiện nghiệm thu còn thiếu

- **01 vẫn `[ ]`:** cần chạy và nhận kết quả migration/default/baseline/legacy/rollback và smoke PostgreSQL thật; xác nhận chính sách dữ liệu legacy mô tả ở trên. Việc test được viết hoặc subset được biên dịch không đủ để nghiệm thu.
- **02 vẫn `[ ]`:** cần kết quả quan sát chờ/nhả advisory lock hai kết nối, JSONB và commit/rollback/FK/CHECK, JWT/filter trên PostgreSQL thật. Hợp đồng kỹ thuật đã bổ sung; các API 03–09 vẫn phải được nghiệm thu riêng về caller, quyền và sự kiện thực tế.
- Bộ chạy phải thoát 0, có đủ XML mới của từng suite, failures/errors/skipped đều 0; script kiểm các điều kiện đó và không chấp nhận XML cũ. Chưa có bằng chứng này nên **0/2 mục cấp chính được nghiệm thu cuối đợt**.
- Commit lần này là bộ kiểm thử/handoff thuộc 01–02, không phải tuyên bố hoàn thành hai mục. Author/Committer `MeanFen <nghiapp11102006@gmail.com>`; chỉ stage các tệp liệt kê trên, giữ V4 và các nháp khác, không push hoặc chuyển sang mục khác.

## Đợt 13 - Chốt nghiệm thu mục 01–02 từ kết quả Windows, 18/11/2026

### Nguồn bằng chứng và cách đối chiếu

Người dùng đã chạy bộ test tại máy Windows, báo **BUILD SUCCESS, exit code 0**, JDK 27 và PostgreSQL 16.16 trong Docker. Đợt này chỉ đối chiếu artifact sẵn có và rà soát script, **không chạy lại Maven/Docker hoặc bộ test PostgreSQL** do hạn chế môi trường của agent.

Đã đọc đủ **11 file TEST-*.xml và 11 log tóm tắt *.txt** trong `backend/target/surefire-reports`, kiểm tên từng suite/testcase theo mã nguồn và script, tổng số test, số failure/error/skipped ở suite và node con. Kết quả các XML khớp log tóm tắt và thông tin người dùng cung cấp: **73 test, failures=0, errors=0, skipped=0**. Các tệp được tạo trong lần chạy khoảng **17:39–17:40 ngày 18/11/2026, UTC+7**; không lấy kết quả từ lần thử 17:36 trước đó để cộng thêm.

XML ghi java.version=27, java.vendor=Oracle Corporation, os.name=Windows 11, user.dir là backend của TeamProject. Log Flyway xác nhận **PostgreSQL 16.16** với các database `phase4_*` trên 127.0.0.1. Smoke log xác nhận apply V1/V3/V4 và Tomcat khởi động cổng ngẫu nhiên; testcase readiness thành công. BUILD SUCCESS/exit code 0 là kết quả Terminal do người dùng cung cấp, còn số test và trạng thái từng suite đã được agent kiểm độc lập từ XML/log.

| Suite | Test | Phạm vi bằng chứng |
|---|---:|---|
| ContactsMigrationTest | 6 | Database mới, default/NOT NULL, legacy, cờ đã biết, rollback/retry, baseline V1 |
| FlywayMigrationTest | 3 | Đổi URL, baseline và lịch sử V2 seed không nằm trong classpath; 2 ca dùng PostgreSQL |
| LocalDevServerSmokeTest | 1 | Backend/Flyway khởi động và readiness dùng đúng database test |
| ContactPostgresFoundationTest | 12 | Advisory lock, hai kết nối, policy PostgreSQL, JSONB, MANDATORY, commit/rollback/FK/CHECK/serialize |
| ContactJwtPostgresTest | 8 | SecurityConfig/filter/JWT Phase 3 với tài khoản và thiết bị trên PostgreSQL |
| PostgresFixtureGuardTest | 7 | Không nhận cấu hình ngoài fixture localhost; không rơi về .env |
| ContactPolicyServiceTest | 8 | Hồi quy predicate/guard trên H2 |
| SyncEventWriterTest | 7 | Hồi quy Spring proxy/transaction trên H2 |
| ContactDtosTest | 9 | Hợp đồng JSON và hồ sơ tối thiểu |
| LookupRateLimiterTest | 8 | Giới hạn/thời gian chờ/đồng thời |
| MissingPhoneParameterTest | 4 | HTTP/mã lỗi và details |
| **Tổng** | **73** | **29 ca cần PostgreSQL + 44 ca còn lại, không bỏ qua** |

### Đối chiếu điều kiện nghiệm thu

**01 — Đạt:** freshDatabaseMigratesWithoutDemoAndUsesRequiredDefault, manualV1DatabaseIsBaselinedAndUpgraded và các ca Flyway chứng minh tạo mới/nâng cấp/baseline. Ba ca legacy kiểm backfill theo V4 từ main, giữ blocked, không suy ra friendship từ ACCEPTED và bảo toàn is_friend đã biết. failedBackfillRollsBackAndCanBeRetriedWithoutRepair kiểm SQLSTATE 23514, trạng thái sau rollback và retry không repair; log thực tế ghi `Changes successfully rolled back` rồi apply V4 thành công. Smoke test xác nhận đường bootstrap dev/Flyway hoạt động với database cô lập. Không sửa SQL V4 hoặc migration đã áp dụng.

**02 — Đạt trong phạm vi nền tảng:** các test đảo chiều quan sát kết nối thứ hai thực sự chờ advisory lock tại pg_stat_activity, sau đó đi tiếp khi transaction trước commit hoặc rollback; cặp độc lập vẫn chạy. Khóa dòng được lấy sau khóa cặp trong các transaction kiểm thử. JSONB và các loại event đã qua schema PostgreSQL thật; commit hai người nhận, rollback caller, lỗi FK/CHECK/serialize và MANDATORY đều có testcase đạt. JWT test dùng chính SecurityConfig/JwtService/filter Phase 3, kiểm actor từ token, thiếu/sai/hết hạn/token tạm, thiết bị thu hồi/token_valid_after và tài khoản không ACTIVE. DTO/lỗi và hợp đồng kỹ thuật đã được đối chiếu cùng docs/API_CONTRACT.md.

Các test H2 chỉ giữ vai trò hồi quy; việc chốt phần PostgreSQL dựa trên **29 ca PostgreSQL thực tế**, không suy ra từ H2. Những dòng ERROR trong testcase backfill là lỗi cố ý để kiểm rollback, không phải failure của bộ test; XML của testcase đó không có node failure/error/skipped. Hai dumpstream chứa warning classpath Surefire, không phải test thất bại; không đổi JDK hoặc thêm cờ JVM chỉ để che warning khi kết quả đã đạt.

### Rà soát thay đổi script

- Giữ bản sửa đã chạy thành công của người dùng: **UTF-8 có BOM**, thay docker port/regex capture bằng **docker inspect → NetworkSettings.Ports.'5432/tcp' → HostIp=127.0.0.1 → HostPort**. Chỉ còn một dòng tạo jdbcUrl từ HostPort; không còn dòng cũ dùng `$Matches[1]` ghi đè kết quả.
- Không thay cơ chế container/database riêng, mật khẩu test qua môi trường, kiểm XML mới và kiểm failures/errors/skipped, hay dọn tài nguyên trong finally. Không dùng .env/Supabase.
- Agent chỉ chỉnh lại thụt lề một dòng inspect, không đổi hành vi bản đã chạy. Giữ BOM sau khi lưu. Kiểm PowerShell Parser: **0 lỗi cú pháp**; ba self-check thuần dữ liệu cho đoạn JSON inspect đạt (chọn đúng loopback dù có mapping khác, từ chối thiếu loopback, từ chối port không phải số). Không gọi Docker/Maven trong các self-check này và không tính chúng vào 73 test.

### Bảo toàn, cập nhật và điểm dừng

- Chỉ thay đổi `backend/scripts/test-phase4-foundation.ps1` và file báo cáo tiến độ này. Chỉ đánh dấu `[x]` hai mục cấp chính **01 và 02**; không đánh dấu hoặc triển khai mục tiếp theo. Giữ các đợt trước làm lịch sử: trạng thái bị chặn ở đợt 10–12 đã được thay bằng bằng chứng Windows của đợt 13.
- Giữ nguyên 34 tệp nháp khác, stash c1f69ff, .env và V4. Lưu hash artifact trước khi đọc; xác minh không sửa/xóa 24 tệp trong surefire-reports. Không commit XML, properties, log sinh ra hoặc credential; `backend/target` vẫn được ignore.
- Commit chốt nghiệm thu dùng Author và Committer `MeanFen <nghiapp11102006@gmail.com>`, không push hoặc sửa lịch sử.
- **Hai mục 01–02 đã nghiệm thu.** Giới hạn legacy vẫn giữ nguyên: blocked cũ thiếu cờ friendship được xử lý bảo thủ thành block-only, không cam kết phục hồi mọi tình bạn lịch sử. Đây không còn là điều kiện thiếu của bộ nghiệm thu đã chốt; nếu cần khôi phục dữ liệu thực tế phải đối soát riêng.
- Nghiệm thu này không xác nhận API 03–09, UI/Android, toàn bộ Phase 4 hoặc production/Supabase đã đạt. Chưa có thay đổi giao diện mới để bấm thử; kết quả HTTP đã kiểm là smoke readiness backend. Dừng chờ yêu cầu tiếp theo.

## Đợt 14 - Tra cứu SĐT và gửi lời mời: mục 03–04, 18/11/2026

### Chọn phạm vi và bảo toàn

Chọn đúng hai mục cấp chính **03 và 04**, tiếp nối 01–02 đã nghiệm thu tại commit 6faf6c8. Đọc và tái sử dụng code lookup/send cùng các helper DTO/query/event từ bản nháp ContactsService/ContactController, không viết lại các API khác. Stash c1f69ff và các stash trước đó được giữ nguyên, không tạo project/worktree hoặc tự push.

Hai tệp Java chung còn chứa nháp 05–09. Chỉ đưa phần lookup/send và helper cần thiết vào index/commit; các phương thức nháp còn lại giữ nguyên trong working tree, không được đưa vào lịch sử đợt này. Snapshot source đúng phạm vi đã được javac --release 17 biên dịch riêng trong .task-cache; runner unit ưu tiên nạp các class này để kiểm phần dự kiến commit. Sau commit, hai tệp có thể vẫn hiển thị M do các phần nháp 05–09, không phải xung đột Git. Không đánh dấu hoặc triển khai thêm mục cấp chính thứ ba.

Lưu hash 34 tệp chưa commit và bản đầy đủ hai tệp Java trong `.task-cache/phase4-03-04`; 32 tệp nháp khác không đổi. Đối chiếu phần source từ phương thức requests trở xuống trong ContactsService với bản trước đợt: nội dung nháp 05–09 giữ nguyên. .env và các migration không bị sửa, không dùng credential Supabase để chạy test.

### Nội dung thực hiện

**03:** GET /api/users/lookup dùng actor của JWT, kiểm E.164 ở server trước khi tính lượt; nối limiter 10 lượt/60 giây theo tài khoản, kể cả các lần tra cứu đúng cấu trúc nhưng không tìm thấy/tự tìm. Không tự chuyển số VN chưa chuẩn hóa; phần nối ContactsValidator với ô nhập vẫn thuộc mục 17. Sau khi biết ID đối phương, lookup lấy khóa cặp trong transaction rồi đọc lại hồ sơ/SĐT/quyền riêng tư và quan hệ; chặn hai chiều, discoverable=false, không ACTIVE hoặc không tồn tại cùng trả NOT_FOUND. DTO giữ đúng isContact, trạng thái friend/pending/stranger và ID/chiều pending; không ghi contact/request/event.

**04:** POST /api/contact-requests chỉ dùng addresseeId từ body, actor từ JWT. UUID thiếu/sai/rút gọn trả 400 VALIDATION, UUID đầy đủ viết hoa/thường được nhận. Tái sử dụng khóa cặp trước các thao tác ghi, chặn self/block/already friends; gửi trùng cùng/ngược chiều trả 409 cùng existingRequestId/direction, không tự accept. Lời mời mới và hai CONTACT_REQUEST_CREATED cùng transaction qua SyncEventWriter proxy; lỗi ghi bên thứ hai phải rollback cả lời mời lẫn sự kiện đầu. Quyền nhận tin/discoverable không thay thế block khi caller đã biết UUID; không gửi SĐT trong hồ sơ lời mời.

### Tệp thuộc phạm vi commit

| Tệp | Thay đổi |
|---|---|
| `backend/src/main/java/com/utechat/backend/service/ContactsService.java` | Chỉ đưa lookup/send và helper cần thiết từ nháp vào commit; bổ sung transaction/đọc lại hồ sơ và validation UUID body; các phương thức 05–09 chưa commit |
| `backend/src/main/java/com/utechat/backend/controller/ContactController.java` | Chỉ đưa GET lookup và POST gửi lời mời vào commit, principal JWT, phone query rõ tên và body validation |
| `backend/src/test/java/com/utechat/backend/contacts/ContactApiSupport.java` | Fixture PostgreSQL riêng, SecurityConfig/JwtService/filter thật, TransactionManager và SyncEventWriter Spring proxy; không bypass chữ ký bằng authentication() |
| `backend/src/test/java/com/utechat/backend/contacts/ContactLookupApiTest.java` | 22 test: JWT, E.164/biên độ dài, SELF, exact match, privacy/block/status, JSON, friend/incoming/outgoing, limiter/cách tính lượt, chống giả actor và không ghi dữ liệu |
| `backend/src/test/java/com/utechat/backend/contacts/ContactSendApiTest.java` | 20 test: JWT/body/self/inactive/block/friends, payload/sự kiện, duplicate, hai HTTP request đồng thời cùng/ngược chiều và lỗi sự kiện bên thứ hai/rollback/retry |
| `backend/src/test/java/com/utechat/backend/contacts/ContactInputValidationTest.java` | Hai test unit validation UUID trong body: canonical hoa/thường; thiếu/sai/rút gọn trả 400 VALIDATION |
| `backend/scripts/test-phase4-foundation.ps1` | Giữ default 73 test, thêm -ContactsApi để chọn 117; dùng một bảng expected cho selector và kiểm XML; sao lưu báo cáo cũ trước khi Surefire ghi báo cáo mới |
| `docs/API_CONTRACT.md` | Làm rõ E.164/cách tính lượt và che hồ sơ, UUID body, người gửi JWT, duplicate và nguyên tử lời mời/sự kiện |
| `docs/phase4/01-phone-validation.md` | Báo cáo tiến độ duy nhất và trạng thái chờ nghiệm thu 03/04 |

### Kiểm thử thực tế

- Dùng **Maven 3.9.16 và JDK 27**, --release 17 đúng POM hiện tại. `mvn -o -B -ntp -f backend/pom.xml test-compile` **BUILD SUCCESS**, đã biên dịch cả code/test mới; không thay JDK hoặc phiên bản dependency.
- Maven chạy 44 test regression chọn lọc: **44 test, 0 failures/errors/skipped, BUILD SUCCESS**. Sau khi thêm validation body, JUnit Platform chạy trên snapshot class đúng phần dự kiến commit: **46 test thành công, 0 failed/skipped** = 44 regression + 2 validation mới. Đây là 46 test phân biệt, không cộng hai lần thành 90.
- 46 test không cần PostgreSQL gồm H2/policy, transaction helper, DTO, limiter, HTTP lỗi tham số, guard và đổi URL. **Chưa có kết quả 42 test API trên PostgreSQL**, không suy ra endpoint/concurrency/rollback thực tế đã đạt chỉ từ compile hoặc H2.
- Script PowerShell: Parser 0 lỗi cú pháp; self-check default chọn 11 suite/73 test, -ContactsApi chọn 14 suite/117 test. Giữ UTF-8 BOM và cách lấy port bằng docker inspect đã nghiệm thu. Đoạn sao lưu báo cáo được kiểm bằng copy/hash 25 artifact hiện có, không gọi Docker hoặc Maven.
- Đã chạy `test-phase4-foundation.ps1 -ContactsApi` với Maven cache/JDK 27: dừng mã 1 ở Docker preflight, không tạo container. Lỗi thực tế là không tìm thấy named pipe trong phiên agent, không kết luận Docker ở Terminal người dùng bị hỏng.
- Thử tận dụng Zonky/native PostgreSQL vừa có trong cache, không dùng .env; engine không khởi tạo được vì initdb.exe không tạo được tiến trình (error=623). Chưa chạy được SQL/test API trong lần thử này, không coi artifact engine có mặt là bằng chứng đã chạy PostgreSQL.
- Maven có warning varargs ở HealthControllerTest sẵn có; không sửa tệp ngoài phạm vi. Không có bằng chứng JDK 27 không tương thích và không đổi JDK.

Lỗi môi trường tiêu biểu:

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine; check if the path is correct and if the daemon is running: open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.
Cannot run program "...\bin\initdb.exe": CreateProcess failed, error=623
```

### Sự cố báo cáo sinh ra và cách xử lý

Trong lần Maven chạy 44 regression, agent truyền `-Dsurefire.reportsDirectory=...` để định ghi báo cáo riêng nhưng Surefire 3.2.5 không có binding user property này trong cấu hình hiện tại. Maven đã ghi đè **7 cặp *.txt/TEST-*.xml** tại thư mục báo cáo mặc định, trong đó suite Flyway của lần chạy này chỉ có test đổi URL. Đây là lỗi thao tác của agent, không phải failure của test hoặc thay đổi mã nguồn/stash.

Không khôi phục giả các XML cũ: chúng không còn byte gốc ở working directory. Lịch sử nghiệm thu 73 test tại đợt 13 vẫn giữ nguyên trong báo cáo và commit 6faf6c8; các artifact không bị ghi đè giữ nguyên. Đã sao lưu toàn bộ 25 artifact hiện có vào .task-cache và kiểm thêm bản backup trong backend/target/phase4-report-backups. Những lần sau dùng test-compile và runner JUnit riêng cho kiểm tra cục bộ, không dùng lại property không có tác dụng. Script Windows đã bổ sung backup trước Maven test; cần lấy **kết quả mới đầy đủ của bộ 117**, không cộng các XML khác lần chạy hoặc xem bộ artifact hiện tại là bộ 73 nguyên bản.

### Lệnh kiểm thử trên Windows

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
.\backend\scripts\test-phase4-foundation.ps1 -ContactsApi
$LASTEXITCODE
```

Nếu mvn chưa có trong PATH, truyền `-Maven` như đợt trước bằng đường dẫn Maven 3.9.16 trên máy. Script dùng PostgreSQL Docker riêng và không đọc .env; chọn **117 test dự kiến** = 73 nền đã nghiệm thu + 22 lookup + 20 send + 2 validation. Trong đó 71 ca cần PostgreSQL, 46 ca còn lại. Đây là số test cần chạy, **không phải tuyên bố 117 test đã đạt**. XML mới phải đủ các suite, failures/errors/skipped đều 0; backup lần cũ nằm dưới backend/target/phase4-report-backups.

### Trạng thái bàn giao và điểm dừng

- **03:** code và test đã chuẩn bị/build được, nhưng còn chờ test PostgreSQL của endpoint, privacy/trạng thái, giới hạn và JWT; giữ `[ ]`.
- **04:** code và test đã chuẩn bị/build được, validation unit đạt; còn chờ HTTP/PostgreSQL, duplicate/concurrency và atomicity sự kiện; giữ `[ ]`.
- Chưa có thay đổi UI để bấm thử với backend; nối ô tìm người vẫn thuộc mục 17. Không triển khai hoặc đưa nháp API 05–09 vào commit. Các kiểm thử 01–02 trong script chỉ là hồi quy, không phải chọn thêm mục cấp chính.
- Chỉ commit đúng code 03–04/test/harness và tài liệu liên quan bằng Author và Committer `MeanFen <nghiapp11102006@gmail.com>`. Không push, không sửa lịch sử, không xóa stash/backup. Dừng để người dùng chạy bộ Windows và cung cấp kết quả nghiệm thu; chưa chuyển sang mục khác.

## Đợt 15 - Chốt nghiệm thu mục 03–04 từ kết quả Windows, 18/11/2026

### Nguồn bằng chứng và kết quả đối chiếu

Người dùng chạy `backend/scripts/test-phase4-foundation.ps1 -ContactsApi` tại Windows và báo **BUILD SUCCESS; 117 test, failures=0, errors=0, skipped=0; script xác nhận đủ suite đạt**. Đợt này chỉ đọc artifact mới và rà soát code/script; **không chạy lại Maven, Docker hoặc các test**. BUILD SUCCESS và kết quả kiểm tra cuối script là thông tin từ Terminal người dùng; số test và trạng thái từng testcase được agent kiểm độc lập từ XML/log.

Đã đọc **14 file TEST-*.xml và 14 log tóm tắt *.txt** trong `backend/target/surefire-reports`, thuộc lần chạy khoảng **19:35:30–19:36:01 ngày 18/11/2026, UTC+7**. Từng suite có đúng số testcase dự kiến, không trùng tên testcase; tên testcase khớp mã nguồn hiện tại. Tổng XML là **117**, không có node failure/error/skipped; summary của cả 14 log tóm tắt khớp XML. Không cộng kết quả 46 test của đợt 14 hoặc artifact cũ vào tổng này.

XML ghi **java.version=27, java.vendor=Oracle Corporation, os.name=Windows 11**, user.dir đúng thư mục backend của TeamProject. Cả **22/22 ca lookup và 20/20 ca send** đều có log Flyway xác nhận **PostgreSQL 16.16**, database `phase4_*` riêng trên loopback, áp dụng ba migration thành công. Bộ mới có đủ 73 test nền đã nghiệm thu và 44 test bổ sung; phân loại **71 ca cần PostgreSQL, 46 ca còn lại**, không dùng H2 để thay bằng chứng PostgreSQL.

| Suite | Test | Kết quả |
|---|---:|---|
| ContactLookupApiTest | 22 | Đạt; API lookup qua MockMvc/JWT/filter và PostgreSQL thật |
| ContactSendApiTest | 20 | Đạt; API send, đồng thời, JSONB và rollback trên PostgreSQL thật |
| ContactInputValidationTest | 2 | Đạt; UUID đầy đủ hoa/thường và đầu vào body sai |
| ContactsMigrationTest | 6 | Đạt; hồi quy migration/backfill/default/rollback |
| FlywayMigrationTest | 3 | Đạt; đủ cả hai ca database và ca URL |
| LocalDevServerSmokeTest | 1 | Đạt; bootstrap/Flyway/readiness với backend khởi động thật |
| ContactPostgresFoundationTest | 12 | Đạt; khóa cặp, JSONB và transaction nền |
| ContactJwtPostgresTest | 8 | Đạt; JWT/thiết bị/thu hồi và trạng thái tài khoản |
| PostgresFixtureGuardTest | 7 | Đạt; giới hạn cấu hình database test |
| ContactPolicyServiceTest | 8 | Đạt; hồi quy policy trên H2 |
| SyncEventWriterTest | 7 | Đạt; hồi quy helper/proxy trên H2 |
| ContactDtosTest | 9 | Đạt; hợp đồng JSON |
| LookupRateLimiterTest | 8 | Đạt; cửa sổ thời gian, retry và đồng thời |
| MissingPhoneParameterTest | 4 | Đạt; lỗi tham số bắt buộc |
| **Tổng** | **117** | **0 failures, 0 errors, 0 skipped** |

### Đối chiếu điều kiện nghiệm thu

**03 — Đạt:** thiếu/sai JWT bị từ chối; thiếu/sai E.164 trả PHONE_INVALID, tự tìm trả SELF, số không tồn tại hoặc chỉ khớp prefix không trả hồ sơ. Các test `privateProfileIsHiddenLikeUnknownPhone`, `lockedAndDeletedTargetsAreHidden`, `blockByCallerHidesTarget`, `blockByTargetHidesTarget` đạt, xác nhận NOT_FOUND che các trường hợp tương ứng. DTO giữ đúng tám trường công khai, không lộ SĐT; friend/incoming/outgoing/stranger cùng pendingRequestId/direction có testcase đạt. Lần tra cứu hợp lệ thứ 11 trả 429 RATE_LIMITED với retryAfterSeconds=60; ngân sách tách theo JWT actor, đầu vào sai cấu trúc không tính lượt, tra cứu hợp lệ không tìm thấy vẫn tính lượt. Query không thay actor; lookup không ghi quan hệ, lời mời hoặc sự kiện. Việc hết cửa sổ chờ dựa thêm vào suite LookupRateLimiterTest hồi quy trong cùng lần chạy.

**04 — Đạt:** actor lấy từ JWT, body/query không giả được requester; UUID thiếu/sai/rút gọn và JSON/body lỗi trả VALIDATION, UUID đầy đủ viết hoa được nhận. Self, target không tồn tại/không ACTIVE, block mỗi chiều và already friends đều bị từ chối mà không ghi lời mời/sự kiện. Tạo mới trả 201/PENDING và đúng DTO tối thiểu; đúng hai CONTACT_REQUEST_CREATED cho hai người nhận, JSONB khớp response và không chứa SĐT. Duplicate cùng/ngược chiều trả 409 cùng existingRequestId/direction; không tự accept.

Hai testcase `concurrentSameDirectionProducesOnePendingRequest` và `concurrentOppositeDirectionsProduceOnePendingRequest` đều đạt: mỗi ca gửi hai request MockMvc có Bearer qua filter thật bằng hai thread, nhận một 201 và một 409, chỉ còn một PENDING và hai sự kiện. `secondRecipientEventFailureRollsBackAndRetryIsClean` đạt với CHECK thật từ PostgreSQL gây lỗi sự kiện người nhận thứ hai: API trả 500 INTERNAL, lời mời và sự kiện thứ nhất cùng rollback; gỡ constraint rồi retry tạo đúng một lời mời/hai sự kiện. Dòng lỗi `test_reject_second_recipient` trong log là lỗi cố ý của testcase này, không phải test failure. Advisory lock chờ/nhả hai kết nối đã có thêm các test nền hồi quy trong cùng bộ.

### Rà soát code, script và bảo toàn

- Không cần sửa code nghiệp vụ hoặc script sau khi đối chiếu. Logic lookup/send và helper trong ContactsService ở working tree trùng phần đã commit tại **40df1d8**; hai handler lookup/send trong ContactController cũng trùng. Phần chênh của hai tệp này vẫn là nháp 05–09 ngoài phạm vi; không stage các tệp Java đầy đủ và không nghiệm thu các API nháp đó.
- Kiểm lại transaction, thứ tự lấy khóa cặp, SyncEventWriter được Spring quản lý/proxy, JWT Phase 3 và hợp đồng trong docs/API_CONTRACT.md; không phát hiện khác biệt cần chỉnh trong phạm vi 03–04. Test API dùng MockMvc và cấu hình MVC/security thật với PostgreSQL thật, không phải request TCP tới server hosted; smoke test riêng mới khởi động Tomcat. Không suy ra đã kiểm production/HTTPS hoặc toàn bộ Android từ các kết quả này.
- Script không có thay đổi chưa commit so với 40df1d8; giữ BOM UTF-8, docker inspect lấy cổng loopback, một nguồn expected suite, kiểm artifact mới và không bỏ qua test, backup trước Surefire, dọn container của riêng lượt chạy. Kiểm tĩnh PowerShell Parser **0 lỗi cú pháp**, BOM vẫn đúng; không gọi script để chạy lại test. Bản backup của lần chạy Windows có **25 artifact cũ** dưới backend/target/phase4-report-backups/utechat-phase4-7f703946fd0b43efb7e44db2c0b2d0aa. Không xóa backup hoặc cố khôi phục giả XML của đợt 14; bộ 117 mới hiện đã đầy đủ để đối chiếu độc lập.
- Dumpstream mới lúc 19:35:30 chỉ có warning classpath Surefire giống đợt trước, không có failure/error/skipped trong XML; không đổi JDK hoặc thêm cờ JVM để che warning.
- Chỉ sửa **docs/phase4/01-phone-validation.md**: cập nhật phần mở đầu checklist, đánh dấu `[x]` 03/04 và thêm bằng chứng đợt này. Giữ lịch sử trạng thái bị chặn của đợt 14; trạng thái đó đã được thay bằng kết quả Windows mới ở đợt 15. Không commit log/XML, credential hoặc tệp cache; không đọc/in giá trị .env.
- Đối chiếu SHA-256 trước/sau để bảo toàn **34 tệp nháp chưa commit** và toàn bộ artifact surefire-reports. Giữ nguyên ba stash, gồm backup c1f69ff; không tạo worktree/project, sửa lịch sử hoặc push. Commit chốt nghiệm thu chỉ chứa báo cáo, Author và Committer `MeanFen <nghiapp11102006@gmail.com>`.

### Trạng thái và giới hạn còn lại

**Hai mục backend 03–04 đã nghiệm thu**, không còn điều kiện PostgreSQL bị thiếu trong phạm vi checklist này. Mục 01–02 tiếp tục đạt hồi quy. Bộ giới hạn tra cứu vẫn lưu trong bộ nhớ một backend, chưa cam kết chia sẻ quota giữa nhiều instance hoặc qua lần khởi động lại.

Chưa có nghiệm thu giao diện mới: chưa nối chuẩn hóa ô nhập/tìm người/gửi lời mời từ Android vào backend; không xác nhận có thể bấm thử luồng hoàn chỉnh trên ứng dụng. Các mục 05–20 vẫn giữ nguyên trạng thái, không triển khai mục tiếp theo. Dừng để người dùng kiểm tra và tự push bằng Terminal.

## Đợt 16 - Danh sách và chấp nhận lời mời: mục 05–06, 18/11/2026

### Phạm vi và nội dung triển khai

Chọn đúng hai mục **05 và 06** sau khi 01–04 đã nghiệm thu; làm việc tại TeamProject, nhánh phase4_NP, nền dda27da. Đọc lại báo cáo, hợp đồng và bản nháp; tái sử dụng requests, truy vấn request/mapRequest, thứ tự khóa và upsert/event từ respond. Không đưa API reject/cancel/friends/block/unblock/unfriend của 07–09 vào commit.

**05:** GET /api/contact-requests lấy chủ danh sách từ JWT. Giữ mặc định nháp: status thiếu/blank là PENDING, type thiếu/blank lấy cả hai chiều; lọc đúng incoming/outgoing và bốn trạng thái cho phép, giá trị khác trả VALIDATION. Query có bind parameter, chỉ trả lời mời actor tham gia và cả hai tài khoản ACTIVE, hồ sơ tối thiểu không SĐT. Thêm ID giảm dần làm khóa phụ khi createdAt trùng nhau; trả mảng rỗng và không ghi dữ liệu/sự kiện.

**06:** tách accept thành phương thức riêng từ nháp respond để không kéo API 07 vào commit. Chỉ addressee được accept; lấy khóa cặp rồi khóa dòng và đọc lại trạng thái, kiểm block hai chiều. PENDING chuyển ACCEPTED/respondedAt, upsert hai dòng bạn bè theo thứ tự UUID ổn định, ghi CONTACT_UPDATED cho chiều thực sự đổi và CONTACT_REQUEST_UPDATED cho hai người nhận trong cùng transaction. Giữ createdAt của contact cũ; lỗi ghi phải rollback toàn bộ. Lặp ACCEPTED trả DTO lịch sử, không ghi thêm, không phục hồi bạn bè sau unfriend hoặc đụng lời mời mới hơn. Helper payload contact phục vụ riêng accept, không phụ thuộc API danh sách bạn bè mục 08.

| Tệp thuộc phạm vi commit | Nội dung |
|---|---|
| `backend/src/main/java/com/utechat/backend/service/ContactsService.java` | Đưa requests từ nháp vào commit, bổ sung accept và helper sự kiện cần thiết; giữ nháp respond/contacts/block/unblock/unfriend ngoài index |
| `backend/src/main/java/com/utechat/backend/controller/ContactController.java` | Thêm GET danh sách và POST accept, đặt rõ tên tham số và dùng actor JWT; chỉ commit bốn handler thuộc 03–06 |
| `backend/src/test/java/com/utechat/backend/contacts/ContactRequestListApiTest.java` | 17 test: JWT, actor, chiều/trạng thái, quyền người thứ ba, JSON tối thiểu, privacy/status, filter sai, thứ tự và không ghi dữ liệu |
| `backend/src/test/java/com/utechat/backend/contacts/ContactAcceptApiTest.java` | 25 test: JWT/quyền/block/status, JSONB/event, lặp/lịch sử, upsert, rollback/retry, đồng thời và luồng tích hợp |
| `backend/scripts/test-phase4-foundation.ps1` | Thêm -RequestsApi chọn 159 test/16 suite; giữ default 73 và -ContactsApi 117, backup và kiểm XML mới |
| `docs/API_CONTRACT.md` | Làm rõ filter/danh tính, respondedAt của list, accept/lặp, payload và transaction |
| `docs/phase4/01-phone-validation.md` | Báo cáo duy nhất; cập nhật checklist theo đúng trạng thái chờ PostgreSQL |

### Kiểm thử thực tế và phần còn thiếu

- **JDK 27, Maven 3.9.16:** chạy `mvn -o -B -ntp -f backend/pom.xml test-compile` bằng mvn.cmd trong Maven wrapper cache, **BUILD SUCCESS, exit code 0**. Biên dịch 29 source chính và 23 source test với release 17, gồm 42 test API mới. Warning varargs HealthControllerTest sẵn có giữ nguyên ngoài phạm vi. Không đổi JDK hoặc dependency.
- Snapshot source đúng phần dự kiến commit của ContactsService/ContactController đã được **javac --release 17 -parameters biên dịch riêng thành công**, không dựa vào nháp API 07–09 để build hai tệp này.
- JUnit Platform discovery nhận đúng **42 testcase mới** trên classpath ưu tiên snapshot; chỉ discover, không execute hoặc khởi tạo database, không tính là test đã đạt.
- Runner JUnit riêng, ưu tiên class snapshot trên classpath, chạy **46 test regression: 46 thành công, 0 thất bại/skipped/aborted**, exit code 0. Không ghi đè surefire-reports. Đây là bộ helper/DTO/limiter/lỗi tham số/guard/URL đã có; **không phải kết quả của 42 test API mới**. Lần chuẩn bị runner ban đầu thiếu JUnit Platform Launcher trên classpath; đã dùng JAR 1.10.5 có sẵn trong cache, biên dịch lại và chạy đạt, không sửa POM/dependency project.
- Kiểm tĩnh script: PowerShell Parser **0 lỗi**, vẫn có BOM UTF-8; self-check selector đối chiếu số testcase trong source: default **11 suite/73**, -ContactsApi **14/117**, -RequestsApi **16/159**. Chưa gọi script để chạy bộ 159 trong phiên agent.
- Kiểm tra môi trường thực tế bằng `docker version --format '{{.Server.Version}}'`: **exit code 1**, lỗi nguyên văn dưới đây. Đây là quyền truy cập named pipe Docker của phiên agent; không phải bằng chứng Docker hỏng ở Terminal người dùng, lỗi mật khẩu database, DNS Maven hoặc lỗi JDK. Không dùng H2 thay PostgreSQL, không thử kết nối .env/Supabase.

```text
permission denied while trying to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

**42 test mới chưa được thực thi trên PostgreSQL.** Các ca đã viết/biên dịch nhưng còn phải chạy gồm: lọc và cách ly danh sách; accept đúng quyền; payload JSONB cho hai bên; hai accept đồng thời chỉ chuyển trạng thái/ghi sự kiện một lần; lỗi CHECK ở dòng contact thứ hai hoặc sự kiện lời mời người nhận thứ hai rollback cả respondedAt, quan hệ và sự kiện, sau đó retry sạch; ACCEPTED lịch sử sau unfriend không tạo lại bạn; luồng HTTP gửi → xem hai chiều → accept → đọc lại → lookup FRIEND.

Hai ca block đồng thời của mục 06 dùng **fixture SQL trong transaction lấy chính ContactPolicyService.lockPair**, không gọi API block nháp 09: một ca quan sát accept thực sự chờ advisory lock trong pg_stat_activity rồi kiểm lại block sau commit; một ca cho accept/block chạy hai thread và kiểm không bỏ qua block/không còn pending. Fixture chỉ tạo trạng thái đối kháng phục vụ accept, không kiểm sự kiện hoặc HTTP của API block. Khi thực hiện 09 cần kiểm lại tích hợp accept với API block thật; không suy ra 09 đã đạt từ fixture này.

### Lệnh kiểm thử trên Windows

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\backend\scripts\test-phase4-foundation.ps1" -RequestsApi
$LASTEXITCODE
```

Nếu mvn chưa nằm trong PATH:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\backend\scripts\test-phase4-foundation.ps1" -RequestsApi -Maven 'C:\Users\Phuocnghia_admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
```

Script tạo PostgreSQL Docker/database cô lập, backup artifact cũ trước Surefire và chọn **159 test dự kiến = 117 hồi quy + 17 list + 25 accept**; **113 ca cần PostgreSQL + 46 ca còn lại**. Cần BUILD SUCCESS, script exit 0 và đủ 16 XML mới, failures/errors/skipped đều 0; không cộng kết quả nhiều lần chạy. **159 là phạm vi cần chạy, chưa phải kết quả đã đạt.** Không phải chạy lại riêng bộ -ContactsApi; -RequestsApi đã bao gồm toàn bộ 01–04.

### Bảo toàn và điểm dừng

- Giữ nguyên stash c1f69ff cùng hai stash trước đó. Lưu snapshot/hash 34 tệp nháp và artifact trước thao tác trong .task-cache/phase4-05-06; 32 tệp nháp ngoài ContactsService/ContactController giữ nguyên. Đối chiếu phần từ respond trở xuống với bản trước đợt: byte nội dung nháp 07–09 không đổi; chỉ handler accept của nháp chuyển sang phương thức accept mới thuộc 06.
- Chỉ đưa snapshot đúng phạm vi 03–06 của hai tệp Java chung vào index; các API 07–09 vẫn ở working tree, nên sau commit hai tệp có thể tiếp tục hiển thị M. Không stage toàn bộ các bản nháp, không tạo project/worktree, không sửa migration hoặc lịch sử commit.
- Giữ nguyên **32 artifact** hiện có trong surefire-reports, gồm bộ Windows 117 đã nghiệm thu; không sửa/xóa hoặc commit chúng. Không đọc/in giá trị .env, không xóa stash/backup. Chỉ commit code/test/script/hợp đồng thuộc 05–06 cùng báo cáo, với Author và Committer `MeanFen <nghiapp11102006@gmail.com>`; không push.
- **05 và 06 vẫn `[ ]`** vì chưa có kết quả PostgreSQL mới; không chốt nghiệm thu từ code, test-compile hoặc 46 regression. Mục 01–04 tiếp tục giữ nghiệm thu trước đó. Chưa có UI mới để bấm thử và chưa nghiệm thu Android/hosted. Dừng để người dùng chạy bộ Windows và cung cấp kết quả, không triển khai mục thứ ba.

## Đợt 17 - Chốt nghiệm thu mục 05–06 từ kết quả Windows, 18/11/2026

### Bằng chứng và kết quả đối chiếu

Người dùng chạy **-RequestsApi** tại Windows, báo **BUILD SUCCESS; 159 test, failures=0, errors=0, skipped=0; script xác nhận các suite PASS**, gồm 17 test list và 25 test accept. Đợt này chỉ đọc artifact, rà soát code/script và cập nhật báo cáo; **không chạy lại Maven, Docker hoặc các test**. BUILD SUCCESS và thông báo PASS cuối script là kết quả Terminal do người dùng cung cấp; agent đối chiếu độc lập số test/trạng thái từ XML và log hiện có.

Đã kiểm **16 XML TEST-*.xml và 16 log tóm tắt *.txt**, tạo trong khoảng **20:05:20–20:05:58 ngày 18/11/2026, UTC+7**; dumpstream của cùng lần chạy bắt đầu lúc 20:05:19. Tên/số testcase từng suite khớp mã nguồn và bảng expected của -RequestsApi; không trùng tên, không có node failure/error/skipped. Summary của cả 16 log tóm tắt khớp XML. Tổng **159 test đạt**, không cộng lại 46 regression cục bộ hoặc lấy XML của lần 117 trước để cộng vào.

XML xác nhận **JDK 27 (Oracle), Windows 11**, user.dir đúng thư mục backend của TeamProject. Log của **17/17 test danh sách và 25/25 test accept** đều xác nhận **PostgreSQL 16.16**, database phase4_* cô lập trên loopback và ba migration áp dụng thành công. Tổng bộ gồm **113 test cần PostgreSQL và 46 test còn lại**; kết quả H2 chỉ là hồi quy helper, không dùng để nghiệm thu SQL nghiệp vụ.

| Phần | Test | Kết quả đã đối chiếu |
|---|---:|---|
| ContactRequestListApiTest | 17 | Đạt toàn bộ test danh sách mục 05 |
| ContactAcceptApiTest | 25 | Đạt toàn bộ test accept mục 06 |
| 14 suite hồi quy 01–04 | 117 | Đạt; đủ bộ trước, kể cả migration, khóa cặp, JSONB, JWT, lookup và send |
| **Tổng 16 suite** | **159** | **0 failures, 0 errors, 0 skipped** |

### Đối chiếu checklist

**05 — Đạt:** các ca default/incoming/outgoing và kết hợp status/type xác nhận chỉ lấy lời mời của tài khoản JWT, đúng trạng thái/chiều. Người thứ ba không đọc được lời mời của A/B, query userId/requesterId/addresseeId không thay danh tính. JSON có đúng sáu trường lời mời, hai hồ sơ chỉ gồm id/displayName/avatarUrl, không có SĐT. Đã kiểm danh sách rỗng là mảng, inactive bị loại, privacy không che lời mời đã tham gia, filter sai/chuỗi SQL trả VALIDATION, thứ tự createdAt/ID ổn định và đọc lặp không ghi contact/request/event.

**06 — Đạt trong phạm vi API accept:** các ca JWT, người gửi/người thứ ba/giả actor, ID sai/không tồn tại, tài khoản inactive, block hai chiều và trạng thái REJECTED/CANCELED đều đạt. Accept hợp lệ tạo hai dòng bạn bè không chặn, respondedAt hợp lệ, hai CONTACT_REQUEST_UPDATED và hai CONTACT_UPDATED với JSONB đúng chủ sở hữu/payload. Dòng contact đã có giữ createdAt; chiều vốn không đổi không sinh CONTACT_UPDATED thừa. Lặp ACCEPTED không đổi timestamp, quan hệ hoặc sự kiện; ACCEPTED lịch sử sau unfriend không tạo lại bạn và không tiêu thụ lời mời mới hơn.

Các testcase `twoConcurrentAcceptsHaveOneStateTransitionAndFourEvents`, `secondFriendRowFailureRollsBackEverythingAndRetryWorks`, `secondRequestEventFailureRollsBackFriendsAndContactEvents` đều đạt. Hai accept đồng thời trả cùng kết quả và chỉ ghi một lần; CHECK lỗi ở dòng quan hệ thứ hai hoặc sự kiện lời mời thứ hai đưa lời mời về PENDING/respondedAt null, không để lại contact/event từ lần accept lỗi; gỡ constraint rồi retry sạch. Log chứa lỗi cố ý test_accept_second_row/test_accept_last_event tương ứng; XML của các ca này không có failure/error/skipped.

Hai ca `committedBlockIsRecheckedAfterAcceptanceWaitsForPairLock` và `concurrentAcceptanceAndBlockCannotBypassBlockOrLeavePending` đạt: một ca quan sát accept thực sự chờ advisory lock tại PostgreSQL rồi kiểm block sau khi transaction kia commit; ca còn lại kiểm hai thao tác đồng thời không vượt block hoặc để pending sót. **Phía block là fixture transaction SQL tuân cùng khóa cặp**, không phải HTTP của API block 09. Bằng chứng này nghiệm thu cách accept xử lý thay đổi block đồng thời; không xác nhận triển khai/HTTP/sự kiện của 09. Khi làm 09 vẫn phải kiểm lại tích hợp với API block thật.

`sendListAcceptAndLookupFormAnIntegratedBackendFlow` đạt với Bearer/JWT/filter thật, MockMvc và PostgreSQL: gửi → đọc lời mời hai chiều → accept → pending rỗng/ACCEPTED xuất hiện → lookup FRIEND cho cả hai. Đây là tích hợp backend tại lớp MVC, chưa phải bấm Android hoặc kiểm qua mạng/HTTPS hosted.

### Rà soát, bảo toàn và điểm dừng

- Không cần sửa code nghiệp vụ, test hoặc script sau đối chiếu. Phần service 01–06 đang chạy trùng nội dung commit **15d6359**; bốn handler tương ứng cũng trùng. Các chênh lệch còn lại trong ContactsService/ContactController thuộc nháp 07–09, không stage/commit hoặc nghiệm thu chúng. Đối chiếu transaction, khóa cặp trước khóa dòng, SyncEventWriter proxy và hợp đồng API không thấy khác biệt cần chỉnh trong 05–06.
- Script vẫn đúng bản đã commit, **UTF-8 BOM**, Parser **0 lỗi cú pháp**; chỉ kiểm tĩnh, không thực thi lại. Bản backup Windows `backend/target/phase4-report-backups/utechat-phase4-53027f0fe65147649fe882f06ad532fe` có **32 artifact của lần trước**; SHA-256 khớp snapshot trước đợt 16. Dumpstream mới chỉ có warning classpath Surefire như trước; không hạ JDK hoặc thêm cờ JVM để che warning.
- Cập nhật duy nhất **docs/phase4/01-phone-validation.md**: phần mở đầu/checklist đánh dấu `[x]` 05/06, thêm bằng chứng đợt 17; giữ ghi nhận bị chặn đợt 16 làm lịch sử. Chuẩn hóa hai lệnh -RequestsApi trong phần hướng dẫn đợt 16 theo yêu cầu PowerShell 5.1: thêm JAVA_HOME/bin vào Path và gọi `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` với đúng switch; thêm quy tắc áp dụng từ nay. Bypass chỉ cho tiến trình con, không thay ExecutionPolicy toàn máy. Không yêu cầu chạy lại bộ 159 đã đạt.
- Đối chiếu SHA-256 bảo toàn **34 tệp nháp chưa commit** và **37 artifact surefire-reports hiện tại**, không commit XML/log/properties/credential. Giữ nguyên ba stash, gồm c1f69ff, và mọi backup; không đọc/in giá trị .env, không tạo project/worktree hoặc sửa lịch sử.
- **05–06 đã nghiệm thu; 01–04 tiếp tục đạt hồi quy.** Chưa nghiệm thu API 07–09, Android, giao diện hoặc production. Không còn điều kiện PostgreSQL bị thiếu của 05–06; tích hợp với HTTP block thật vẫn thuộc nghiệm thu 09. Chỉ commit báo cáo bằng Author và Committer `MeanFen <nghiapp11102006@gmail.com>`, không push. Dừng, chưa triển khai mục tiếp theo.

## Đợt 18 - Từ chối/hủy lời mời và bạn bè/unfriend: mục 07–08, 18/11/2026

### Phạm vi và thay đổi

Chọn đúng **07 và 08**, tiếp nối 01–06 đã nghiệm thu tại 9d1a928, chỉ làm việc trong TeamProject trên phase4_NP. Đọc và tái sử dụng respond, contacts, contactEvent, unfriend của bản nháp; không tạo project/worktree hoặc mở rộng API 09.

**07:** đưa hai handler reject/cancel và phần respond tương ứng vào phạm vi commit. Chỉ addressee được reject, requester được cancel; kiểm vai trò trước khóa cặp, sau đó khóa dòng/đọc lại trạng thái. PENDING chuyển REJECTED/CANCELED và respondedAt, ghi đúng hai CONTACT_REQUEST_UPDATED nguyên tử; không sửa contacts. Thao tác cùng trạng thái lặp không đổi respondedAt/event; chuyển từ trạng thái đóng khác trả INVALID_STATE, accept sau reject/cancel cũng vậy. Reject trả DTO 200, cancel trả 204 rỗng. Giữ chữ ký respond có sẵn: nhánh ACCEPTED chuyển về accept đã nghiệm thu, loại bản sao logic tạo bạn cũ; trạng thái hành động khác bị từ chối trước JDBC.

**08:** danh sách bạn bè dựa trên owner từ JWT, is_friend=true/blocked=false chiều mình và target ACTIVE, DTO contact đúng hợp đồng. Thêm ID làm khóa phụ khi trùng tên. Unfriend lấy khóa cặp, xử lý hai chiều theo thứ tự UUID thống nhất với accept: xóa dòng không chặn, với dòng chặn chỉ hạ is_friend, giữ blocked/createdAt. CONTACT_UPDATED phản ánh đúng trạng thái sau thao tác cho từng chủ dòng thực sự đổi; block-only không lộ SĐT và không sinh event khi không đổi. Lặp/stranger không ghi thừa, không hủy pending hoặc phục hồi tình bạn từ ACCEPTED lịch sử. Shared query có nhánh đọc dòng chặn để dựng payload unfriend; chưa đưa GET blocked/block/unblock của 09 vào controller đã commit.

| Tệp thuộc phạm vi commit | Nội dung |
|---|---|
| `backend/src/main/java/com/utechat/backend/service/ContactsService.java` | Promote respond/contacts/contactEvent/unfriend từ nháp, hợp nhất accept, thứ tự/điều kiện cập nhật và sự kiện; giữ block/unblock ngoài index |
| `backend/src/main/java/com/utechat/backend/controller/ContactController.java` | Thêm reject/cancel/friends/unfriend; tổng tám handler 03–08 trong commit, ba handler 09 vẫn là nháp |
| `backend/src/test/java/com/utechat/backend/contacts/ContactRequestActionsApiTest.java` | 27 test JWT/quyền/trạng thái, payload, rollback/retry, đồng thời và luồng gửi/reject/gửi lại/cancel |
| `backend/src/test/java/com/utechat/backend/contacts/ContactFriendsApiTest.java` | 29 test lọc/DTO/actor/privacy, unfriend hai chiều/block/lặp, JSONB, rollback, đồng thời và luồng tích hợp |
| `backend/scripts/test-phase4-foundation.ps1` | Thêm -FriendsApi cho 215 test/18 suite, bao gồm toàn bộ 01–06; giữ các switch 73/117/159 cũ |
| `docs/API_CONTRACT.md` | Quyền, trạng thái/lặp, response, giới hạn danh sách theo chiều chủ, unfriend/event/rollback |
| `docs/phase4/01-phone-validation.md` | Báo cáo duy nhất và checklist chờ PostgreSQL của 07–08 |

### Kiểm thử thực tế

- Dùng **JDK 27, Maven 3.9.16**, chạy `mvn -o -B -ntp -f backend/pom.xml test-compile` qua mvn.cmd trong wrapper cache: **BUILD SUCCESS, exit code 0**, 29 source chính và 25 source test, release 17. Không đổi JDK/dependency; warning varargs của HealthControllerTest sẵn có giữ nguyên ngoài phạm vi.
- Snapshot ContactsService/ContactController đúng phạm vi index **biên dịch riêng thành công bằng javac --release 17 -parameters**, không cần nháp API 09 để compile. JUnit Platform discovery nhận đúng **56 test mới**, không execute hoặc kết nối PostgreSQL; discovery không được tính là test đã đạt.
- Runner JUnit riêng ưu tiên class snapshot chạy **46 regression đạt, 0 failed/skipped/aborted**, exit code 0. Đây là helper/DTO/limiter/lỗi tham số/guard/URL đã có, gồm các test H2; không phải kết quả 56 test nghiệp vụ mới và không dùng để nghiệm thu PostgreSQL.
- Script kiểm tĩnh: Parser **0 lỗi**, **UTF-8 BOM** giữ nguyên; selector đối chiếu số testcase theo source cho default **11 suite/73**, -ContactsApi **14/117**, -RequestsApi **16/159**, -FriendsApi **18/215** đều đúng. Không thực thi script -FriendsApi trong phiên này.
- Kiểm tra môi trường bằng `docker version --format '{{.Server.Version}}'` trả **exit code 1**, lỗi thực tế dưới đây. Đây là từ chối truy cập named pipe trong phiên agent, không kết luận Docker ở Terminal người dùng hỏng hoặc lỗi database/JDK/DNS. Không dùng .env/Supabase và không thay PostgreSQL bằng H2.

```text
permission denied while trying to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

**Chưa chạy 56 test PostgreSQL mới.** Những điều kiện còn phải kiểm khi chạy Windows gồm: reject/cancel đúng vai trò và thao tác lặp, accept sau đóng lời mời, payload JSONB và hai người nhận; lỗi CHECK sự kiện thứ hai rollback respondedAt/trạng thái rồi retry; hai reject/cancel lặp và accept/reject/cancel tranh cùng cặp chỉ chuyển trạng thái một lần. Mục 08 còn cần kết quả danh sách/quyền/DTO thật, bảo toàn block và createdAt, event theo từng chiều thay đổi, lỗi CHECK khi hạ cờ ở dòng thứ hai hoặc khi ghi sự kiện thứ hai rollback toàn bộ, unfriend cùng/ngược chiều đồng thời và race với ACCEPTED lịch sử.

Có test tích hợp gửi → reject → gửi lại → cancel → đọc lại hai danh sách; và gửi → accept → danh sách bạn hai bên → unfriend → lookup STRANGER → accept lịch sử không phục hồi → lời mời mới/accept mới tạo lại bạn. Các luồng này hiện mới biên dịch, **chưa tuyên bố đã chạy thành công**. Một test unfriend/block đồng thời dùng transaction SQL fixture tuân khóa cặp để kiểm bảo toàn block-only; không gọi/nghiệm thu HTTP/sự kiện API block 09. Khi làm 09 cần kiểm lại với API block thật, tương tự giới hạn của mục 06.

### Lệnh Windows để kiểm chứng

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\backend\scripts\test-phase4-foundation.ps1" -FriendsApi
$LASTEXITCODE
```

Nếu mvn chưa nằm trong PATH, giữ cấu hình JAVA_HOME/Path trên và chạy:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\backend\scripts\test-phase4-foundation.ps1" -FriendsApi -Maven 'C:\Users\Phuocnghia_admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
$LASTEXITCODE
```

Bypass chỉ cho tiến trình chạy script, không đổi chính sách toàn máy. Bộ **215 test dự kiến = 159 hồi quy + 27 actions + 29 friends**, gồm **169 ca cần PostgreSQL và 46 ca còn lại**; đây chưa phải kết quả đã đạt. Chỉ cần chạy -FriendsApi vì đã bao gồm 01–06. Cần BUILD SUCCESS/exit 0, đủ 18 XML mới và failures/errors/skipped đều 0; không cộng số từ các lần chạy khác nhau. Script vẫn tạo Docker/database cô lập và backup artifact trước Surefire.

### Bảo toàn và điểm dừng

- Snapshot/hash trước thay đổi nằm trong .task-cache/phase4-07-08. Giữ **32 tệp nháp ngoài hai tệp Java chung**, ba stash gồm c1f69ff, mọi backup và **37 artifact surefire-reports** của bộ Windows 159 đã nghiệm thu. Không đọc/in giá trị .env, không sửa migration, không ghi đè hoặc commit XML/log/credential.
- So sánh phần block/unblock của service trước/sau: nội dung nháp giữ nguyên; ba handler 09 không bị sửa và không đưa vào index. Chỉ stage snapshot có tám endpoint 03–08 cùng helper cần thiết, hai test mới, script, hợp đồng và báo cáo. Hai tệp Java có thể vẫn hiển thị M sau commit vì giữ nháp 09 trong working tree.
- **07–08 vẫn `[ ]`, chưa nghiệm thu toàn mục** do thiếu kết quả PostgreSQL mới; không hạ tiêu chuẩn bằng code/build/46 regression. Mục 01–06 giữ nghiệm thu trước đó. Chưa có UI mới để bấm thử; chưa nghiệm thu Android/hosted và chưa triển khai 09.
- Commit phần chuẩn bị 07–08 bằng Author và Committer `MeanFen <nghiapp11102006@gmail.com>`, không sửa lịch sử hoặc push. Dừng để người dùng chạy bộ Windows và cung cấp kết quả; không làm hạng mục thứ ba.

## Đợt 19 - Nghiệm thu 07–08 từ kết quả Windows, 18/11/2026

Người dùng cung cấp thông báo **PASS tổng 215 test, các suite PASS, $LASTEXITCODE=0** của -FriendsApi. Đối chiếu độc lập đủ **18 XML và 18 log tóm tắt** mới tại surefire-reports, khoảng **21:04:37–21:05:31 UTC+7**: tổng 215, failures/errors/skipped đều 0, không có node lỗi hoặc bỏ qua, tên/số testcase khớp source, không trùng; từng TXT khớp XML. Cả 18 suite ghi JDK 27; log của **27/27 actions và 29/29 friends** xác nhận **PostgreSQL 16.16** và database phase4_* riêng trên loopback. Không chạy lại Maven/Docker/test để đối chiếu, không cộng 46 regression cục bộ vào tổng.

| Phạm vi | Test | Bằng chứng |
|---|---:|---|
| ContactRequestActionsApiTest | 27 | Đúng quyền reject/cancel; trạng thái và lặp; JSONB hai bên; lỗi sự kiện thứ hai rollback/retry; tranh accept/reject/cancel; luồng gửi lại |
| ContactFriendsApiTest | 29 | Chủ danh sách/DTO/privacy; unfriend hai chiều và bảo toàn chặn/createdAt; event đúng chiều; CHECK/rollback/retry; đồng thời, lịch sử ACCEPTED và luồng kết bạn lại |
| 16 suite hồi quy 01–06 | 159 | Đủ bộ trước, cùng lần chạy và đều đạt |
| **Tổng** | **215** | **169 ca cần PostgreSQL + 46 ca còn lại, không bỏ qua** |

**07–08 đạt điều kiện nghiệm thu trong phạm vi API của hai mục.** Hai lỗi CHECK của actions/friends là điều kiện thử có chủ đích để kiểm rollback, không phải test failure. Hai chiều unfriend chỉ ghi event khi trạng thái thực sự đổi; block-only còn lại mất cờ friend và không lộ phone. Accept lịch sử không phục hồi quan hệ sau unfriend. Kiểm đồng thời với block tại 08 dùng fixture transaction SQL cùng khóa cặp, chưa xác nhận API block 09; yêu cầu kiểm lại tích hợp HTTP thật được giữ nguyên cho đợt 09. Bộ này kiểm MVC/JWT/SQL thật, chưa nghiệm thu UI Android hoặc production/HTTPS.

Không thấy khác biệt cần sửa trong code 07–08 của a052452: service/controller ngoài phần block nháp trùng commit, source test/harness/hợp đồng không có thay đổi chưa commit. Dumpstream mới lúc 21:04:35 chỉ có warning classpath Surefire như trước. Cập nhật checklist 07/08 `[x]`, giữ lịch sử bị chặn của đợt 18. Chỉ commit báo cáo bằng Author/Committer MeanFen, không push; giữ nguyên stash, các tệp nháp và artifact. Theo yêu cầu mới, sau nghiệm thu này tiếp tục chọn tối đa hai mục cấp chính tiếp theo, không coi việc đóng bằng chứng 07–08 là triển khai lại tính năng.

## Đợt 20 - Chặn và hoàn thiện hợp đồng client: mục 09–10, 18/11/2026

### Phạm vi và nội dung

Sau khi chốt bằng chứng 07–08 tại **e491da8**, chọn đúng hai mục mới **09 và 10**. Làm việc duy nhất trong TeamProject, phase4_NP; giữ stash/backup và các nháp Android ngoài phạm vi. Không triển khai DAO, Repository, cache, phiên hoặc giao diện 11–20.

**09:** tái sử dụng block/unblock và ba handler cuối của bản nháp. Block thuộc chiều JWT actor, giữ friend/createdAt đã có, người lạ thành block-only; hủy pending hai chiều và ghi sự kiện nguyên tử. Bỏ chặn chỉ hạ blocked trên friend hoặc xóa block-only; không phục hồi lời mời/tình bạn lịch sử. Danh sách blocked chỉ thuộc chủ JWT/target ACTIVE, phone chỉ có khi is_friend=true. Giữ khóa cặp trước ghi/khóa dòng, lặp không phát event thừa. Thêm **34 test**, có rollback sự kiện thứ hai hoặc contact event, retry, mutual block/unblock, unfriend và ba race **API HTTP thật** accept/block, send/block, unfriend/block để bổ sung tích hợp đã hẹn ở 06/08. Chưa coi các test mới là đã đạt khi chưa chạy PostgreSQL.

**10:** đọc lại và giữ nguyên DTO, 11 endpoint Retrofit và bộ fromHttp đã kiểm. Bổ sung fromFailure: IOException vận chuyển/TLS trả NETWORK; lỗi parse/cấu trúc, gồm MalformedJsonException, trả INVALID_RESPONSE. Không lộ exception message, không xóa phiên hoặc sửa cache. ApiClient/AuthInterceptor Bearer hiện có được giữ nguyên; code Injection nháp đã dùng ApiClient.getRetrofit().create(ContactApi.class), nhưng không đưa Injection/Repository vào commit đợt này. Thêm 10 test transport bằng TCP loopback/Retrofit thật và lỗi typed. Các kiểm tra session/Keystore, phản hồi cũ và cache vẫn thuộc 14/12–13.

Thêm gate **AndroidContactContractTest**: một ca biên dịch/chạy đúng bốn suite app (58 test JUnit4); ca còn lại biên dịch chính source DTO/ContactApi/mapper, gọi đủ 11 endpoint bằng Retrofit/Gson qua **Tomcat, JWT và PostgreSQL thật**, đọc lỗi 401/404/409/429 và mất kết nối sau khi đóng server. Không sao chép DTO sang backend; kiểm CodeSource để không nhận nhầm class app cũ. Token trong gate là token test do JwtService backend ký và gắn header bởi interceptor fixture; đây không phải kiểm Android Keystore hoặc thực thi AuthInterceptor trên thiết bị. Gate không cần build các bản nháp Room/UI, không bật Fake Repository, không thay nghiệm thu APK/hosted.

### Tệp thuộc phạm vi commit

| Tệp | Nội dung |
|---|---|
| `backend/src/main/java/com/utechat/backend/service/ContactsService.java` | Đưa block/unblock từ nháp vào commit cùng chú thích nghiệp vụ; backend không còn phương thức nháp ngoài commit |
| `backend/src/main/java/com/utechat/backend/controller/ContactController.java` | Đủ 11 endpoint; rõ tên PathVariable và actor/quyền cho ba handler cuối |
| `backend/src/test/java/com/utechat/backend/contacts/ContactBlockApiTest.java` | 34 test HTTP/JWT/PostgreSQL, quyền/privacy, mutation/event, rollback và race API thật |
| `app/src/main/java/com/utechat/data/repository/ContactErrorMapper.java` | Ánh xạ failure vận chuyển/conversion, không đổi HTTP mapper cũ hoặc phiên/cache |
| `app/src/test/java/com/utechat/data/remote/api/ContactTransportTest.java` | 10 ca transport, gồm 6 TCP và 4 ca typed/guard không cần socket |
| `backend/src/test/java/com/utechat/backend/support/AndroidContactClient.java` | Compiler/classloader dùng source app thật, Retrofit/Gson và bridge tới mapper; dọn client/loader, class tạm nằm ở target |
| `backend/src/test/java/com/utechat/backend/AndroidContactContractTest.java` | Hai gate: đủ 58 test app không bỏ qua; luồng đủ 11 endpoint qua HTTP/JWT/PostgreSQL thật |
| `backend/pom.xml` | Năm dependency chỉ test, cùng phiên bản app: Retrofit/converter 2.11.0, Gson 2.10.1 (pin bản app để BOM backend không đổi JSON), OkHttp 4.12.0, JUnit4 4.13.2; không đóng vào JAR production |
| `backend/scripts/test-phase4-foundation.ps1` | -BlocksApi chọn 249/19 suite; -AndroidContract chọn 251/20 suite, bao gồm 01–09 và gate client; giữ các bộ 73/117/159/215 |
| `docs/API_CONTRACT.md` | Quy tắc block/unblock và lỗi transport Android; phân biệt trách nhiệm mapper với session/cache |
| `docs/phase4/01-phone-validation.md` | Báo cáo tiến độ duy nhất và trạng thái chờ nghiệm thu 09/10 |

### Kiểm thử thực tế và trở ngại

- **JDK 27, release 17** xuyên suốt. Trước khi thêm dependency test cho gate client, Maven 3.9.16 chạy `mvn -o -B -ntp -f backend/pom.xml test-compile` **BUILD SUCCESS/exit 0**, biên dịch 29 source chính và 26 test source, gồm 34 test block. Không thay JDK hoặc phiên bản thư viện app; warning HealthControllerTest sẵn có giữ nguyên.
- Khi thêm dependency test cho gate client, Maven test-compile **chưa thành công trong phiên agent**: repo người dùng không cho ghi dependency mới. Đã thử repo ghi được trong .task-cache, đọc cache Maven cũ và sao chép artifact/POM Gradle thực tế sang layout Maven, không sửa cache toàn máy; vẫn thiếu Okio 3.6.0, Kotlin stdlib-common/jdk8 1.9.25 và Hamcrest core 2.2 theo dependency management của Spring Boot. Thử HTTPS Maven Central xác nhận DNS phiên agent không phân giải được. POM cuối pin thêm Gson 2.10.1 đúng bản app, không đổi thư viện app; bản Maven build cuối vẫn chờ Windows. Không tạo POM/JAR giả để ép build xanh.
- **Javac với thư viện thật đã cache:** helper/gate backend và test block cuối biên dịch được; probe chạy chính compiler/loader của gate đã biên dịch đủ source app và bốn suite 58 test, kiểm class mới thành công. Đây là bằng chứng biên dịch, không phải đã chạy 58 test hoặc thay kết quả Maven build cuối.
- JUnit trực tiếp chạy **46 regression backend đạt**, không skipped/failed/aborted. JUnit app phần không cần socket chạy **52 test đạt** = 48 regression DTO/Retrofit/HTTP mapper + 4 ca failure typed/guard mới. Không cộng hai số này vào 251 hoặc kết quả 215 trước đó.
- Lần thử đầy đủ app ban đầu: **58 test, 5 failure** vì HttpServer không tạo được loopback selector. Ca refused-connection ban đầu có thể nhận nhầm Permission denied là bằng chứng từ chối kết nối; đã siết assertion yêu cầu ConnectException thật, probe trả SocketException nên ca này cũng chưa đạt. Fixture cuối dùng java.net.ServerSocket để không phụ thuộc module JDK HttpServer trong test app, vẫn thực hiện TCP thật. **Lần chạy cuối: 58 test, 52 đạt và 6 failure, exit code 1** do kết nối loopback bị chặn; không ghi 58/58 đạt hoặc đổi sang interceptor giả/ignore để hạ tiêu chuẩn.
- Discovery nhận đủ **36 gate mới** (34 block + 2 client), không execute các ca PostgreSQL/TCP. Docker preflight bị từ chối named pipe; **34 test PostgreSQL block và hai gate client chưa chạy thành công**. Không dùng H2/Supabase/.env thay PostgreSQL và không sửa luật bỏ qua test để nghiệm thu.
- Script: Parser **0 lỗi**, BOM UTF-8 giữ nguyên; selector theo tên/số test source xác nhận 73/117/159/215/249/251 đúng, các suite cũ không bị bỏ khỏi bộ mới. Không gọi -AndroidContract để giả có kết quả khi preflight đã bị chặn. POM XML parse hợp lệ.

Lỗi môi trường quan sát được (không phải bằng chứng lỗi JDK hoặc quyền GitHub):

```text
permission denied while trying to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
java.io.IOException: Unable to establish loopback connection
Caused by: java.net.SocketException: Permission denied: connect
java.nio.file.AccessDeniedException: C:\Users\Phuocnghia_admin\.m2\repository\com\squareup\retrofit2
Could not find artifact com.squareup.okio:okio:jar:3.6.0 in existing-read-cache
No such host is known. (repo.maven.apache.org:443)
```

### Lệnh nghiệm thu tại Windows

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\backend\scripts\test-phase4-foundation.ps1" -AndroidContract
$LASTEXITCODE
```

Nếu mvn chưa ở PATH, thêm `-Maven 'C:\Users\Phuocnghia_admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'` vào lệnh powershell.exe trên. Dùng Maven cache/Internet bình thường ở Terminal Windows, không dùng settings repo thử của agent. Bypass chỉ cho tiến trình con, không đổi ExecutionPolicy toàn máy.

**Dự kiến 251 test Surefire = 215 hồi quy + 34 block + 2 gate client**, gồm 204 ca cần PostgreSQL và 47 ca còn lại. Một trong 47 ca đó chạy **58 test JUnit4 lồng** và bắt buộc runCount=58, failures=0, ignored=0; **không cộng 58 thành tổng 309**. Cần BUILD SUCCESS/exit 0, đủ 20 XML mới, failures/errors/skipped đều 0, cùng log `ANDROID_CONTRACT_JUNIT4 tests=58 failures=0 ignored=0`. -AndroidContract đã bao gồm các bộ cũ và -BlocksApi, không cần chạy từng bộ riêng. Đây là số dự kiến, **chưa phải kết quả đã đạt**.

### Trạng thái và bảo toàn

- **01–08 đã nghiệm thu; 09 và 10 vẫn `[ ]`**. 09 thiếu kết quả SQL/event/race API thật; 10 thiếu đủ sáu TCP và gate client/server thật cùng Maven build cuối. Chưa nghiệm thu session/Keystore, Room, UI, APK hoặc production; không bấm thử giao diện mới trong đợt này.
- Lưu hash 34 bản nháp và artifact tại .task-cache/phase4-09-10 trước thao tác. Hai tệp backend cuối được đưa toàn bộ phần còn lại thuộc 09 vào commit, nên không còn giữ nháp backend trong chúng; **32 tệp nháp khác không đổi**, gồm session, DAO, Repository, Injection, UI và ContactsApiTest cũ. Giữ nguyên ba stash gồm c1f69ff và mọi backup; không tạo project/worktree hoặc sửa lịch sử.
- Bảo toàn bằng SHA-256 bộ artifact Windows 215 hiện có, không ghi đè/xóa hoặc commit XML/log/credential. Các test trực tiếp chỉ ghi log vào cache; chưa chạy Surefire test mới. Không đọc/in giá trị .env. Chỉ stage 11 tệp trong bảng và commit bằng Author/Committer `MeanFen <nghiapp11102006@gmail.com>`, không push. Dừng để người dùng chạy bộ Windows, không triển khai hạng mục thứ ba.

## Đợt 21 - Nghiệm thu 09–10 từ kết quả Windows, 18/11/2026

### Đối chiếu lần chạy

Người dùng cung cấp console của `-AndroidContract`: **BUILD SUCCESS**, tổng **251 test, failures/errors/skipped đều 0**, hoàn tất **22:33:11 UTC+7**, `$LASTEXITCODE=0`. Đây là thông tin từ console người dùng gửi; không có bản lưu toàn bộ console Maven để đối chiếu độc lập từng dòng. Đã đọc trực tiếp **20 XML và 20 TXT** tại `backend/target/surefire-reports`, thời gian sửa **22:32:09–22:33:11 ngày 18/11/2026**, và đối chiếu với selector của [script](../../backend/scripts/test-phase4-foundation.ps1):

- Đủ đúng 20 suite; tổng 251 testcase. Từng suite có đúng số testcase dự kiến, tên testcase khớp source hiện tại, không trùng tên trong suite; không có node failure/error/skipped. Cả 20 TXT khớp số tests/failures/errors/skipped của XML tương ứng.
- Cả 20 XML ghi `java.version=27` và cùng giá trị `sun.java.command`, nhận diện lần Surefire `2026-11-18T22-31-58_106-jvmRun1`. Các log ứng dụng có PID 56276. Dùng kết quả của lần chạy này, không cộng artifact 215 cũ hoặc các lần JUnit trực tiếp ở đợt 20.
- Đếm theo log trong từng testcase: **204 testcase ghi database PostgreSQL 16.16 trên loopback với tên `phase4_*`**, 47 testcase còn lại không có dòng database đó. Một trong 47 ca chạy 58 test app lồng; không cộng thành 309.
- [XML gate client](../../backend/target/surefire-reports/TEST-com.utechat.backend.AndroidContactContractTest.xml) có marker **`ANDROID_CONTRACT_JUNIT4 tests=58 failures=0 ignored=0`**. Ca tích hợp ghi Tomcat khởi động cổng HTTP 59031 và database PostgreSQL 16.16. Cả hai testcase đều đạt.
- Dumpstream `2026-11-18T22-31-58_106.dumpstream` ghi cảnh báo Manifest-JAR/classpath khác root; không có failure/error/skipped trong bộ XML. Không thay cấu hình chỉ để xóa cảnh báo này.

| Phạm vi | Test Surefire | Bằng chứng và kết quả |
|---|---:|---|
| 18 suite hồi quy 01–08 | 215 | Đủ suite/testcase, toàn bộ đạt trong cùng lần chạy mới |
| ContactBlockApiTest | 34 | Quyền JWT, privacy, block/unblock, pending/event, CHECK/rollback/retry, mutual block/unblock, race endpoint accept/send/unfriend; toàn bộ đạt |
| AndroidContactContractTest | 2 | 58 test app lồng đạt và luồng client gọi đủ 11 endpoint, lỗi HTTP cùng mất kết nối; cả hai đạt |
| **Tổng** | **251** | **Failures=0, errors=0, skipped=0** |

### Phạm vi nghiệm thu và phần được đóng

**09 đạt tiêu chí trong checklist.** [Source ContactBlockApiTest](../../backend/src/test/java/com/utechat/backend/contacts/ContactBlockApiTest.java) và [XML 34 test](../../backend/target/surefire-reports/TEST-com.utechat.backend.contacts.ContactBlockApiTest.xml) xác nhận quyền/danh sách theo actor, block một chiều cản tương tác hai chiều, hủy pending hai chiều, giữ friend/createdAt, không phục hồi quan hệ đã hủy, privacy phone, lặp không phát event thừa và rollback/retry khi sự kiện thất bại. Các lỗi CHECK có chủ đích trong source kiểm rollback; các testcase tương ứng đều đạt.

Đóng phần kiểm tích hợp còn hẹn ở 06/08 bằng ba testcase đạt `actualAcceptAndBlockHaveConsistentFinalState`, `actualSendAndBlockCannotLeavePending`, `actualUnfriendAndBlockCannotLoseBlockingDecision`. Chính xác về phương thức kiểm: suite này dùng **MockMvc qua controller/security filter/JWT và transaction PostgreSQL thật**, như [ContactApiSupport](../../backend/src/test/java/com/utechat/backend/contacts/ContactApiSupport.java); các thao tác block trong race đi qua endpoint, không dùng fixture SQL thay API block. Các cụm “HTTP thật” tại đợt 20 mô tả việc gọi endpoint nghiệp vụ; không được hiểu rằng ba race đã chạy qua socket/Tomcat hoặc hosted server.

**10 đạt tiêu chí hợp đồng client thuần Java trong checklist.** [Source gate](../../backend/src/test/java/com/utechat/backend/AndroidContactContractTest.java), [helper compiler/classloader](../../backend/src/test/java/com/utechat/backend/support/AndroidContactClient.java) và XML gate xác nhận biên dịch source app hiện tại, kiểm CodeSource, chạy bốn suite **10 DTO + 16 Retrofit + 22 HTTP mapper + 10 transport = 58**, không ignored. [ContactTransportTest](../../app/src/test/java/com/utechat/data/remote/api/ContactTransportTest.java) có sáu ca TCP: đọc DTO, ConnectException thật, 401, 429/details, HTML 503 và JSON sai cấu trúc; bốn ca còn lại kiểm lỗi typed/guard, gồm SSLHandshakeException được tạo trong test, không phải handshake TLS tới server thật.

Gate tích hợp riêng dùng Retrofit/Gson và DTO/mapper app gọi đủ **11 endpoint qua TCP tới Tomcat**, backend ký JWT test, dùng PostgreSQL thật; kiểm 401/404/409/429, ID/chiều pending, `retryAfterSeconds`, phone theo quyền và NETWORK sau khi đóng server. Bearer được interceptor fixture gắn vào header. Source [ApiClient](../../app/src/main/java/com/utechat/data/remote/api/ApiClient.java) dùng AuthInterceptor hiện có; gate không chạy AuthInterceptor/Keystore trên Android. Repository/cache/phiên, Room, UI, APK và hosted HTTPS vẫn ngoài nghiệm thu 09–10.

### Cập nhật và điểm dừng

- Cập nhật dòng tiến độ mới nhất, tổng quan, checklist **09/10 `[x]`** và ghi đóng yêu cầu tích hợp ở 06/08. Giữ nội dung đợt 20 làm lịch sử trở ngại trước khi có kết quả Windows; các dòng chờ tại đợt đó không phải trạng thái hiện tại.
- Các tệp source/test/script/POM/hợp đồng của đợt 20 không có diff so với HEAD **4538132** tại lúc đối chiếu. Phần còn thiếu được đóng bằng kết quả Windows mới; đợt này chỉ sửa báo cáo, không cần sửa code hoặc chạy lại test.
- Snapshot SHA-256 trước/sau đối chiếu dùng để kiểm bảo toàn 32 tệp đang sửa/chưa được theo dõi và artifact Surefire/backup. Bản backup mới nhất trước lần chạy Windows có 18 XML, tổng 215 test. Không đưa XML/log/credential vào source hoặc commit báo cáo.
- Dừng sau nghiệm thu **09–10**; các mục **11–20 giữ `[ ]`**. Không triển khai hạng mục tiếp theo, không chạy Maven/Gradle/Docker/test trong lượt đối chiếu này. Lượt đối chiếu ban đầu để báo cáo ở working tree; theo yêu cầu bàn giao tiếp theo, đưa riêng báo cáo mới nhất vào commit bằng Author/Committer `MeanFen <nghiapp11102006@gmail.com>`. Code/test 09–10 đã nằm trong **4538132**, không có diff còn thiếu thuộc phạm vi này; giữ 32 tệp nháp ngoài phạm vi và không push.

## Đợt 22 - Room và nền tảng cô lập phiên: mục 11/14, 19/11/2026

### Phạm vi, bản nháp và quy tắc commit

Chọn đúng **11 và 14**, vì checklist yêu cầu thiết kế/kiểm chứng 14 trước khi Repository 12–13 ghi cache. Làm trong TeamProject, nhánh phase4_NP, HEAD **a071559**; lúc bắt đầu Git không báo ahead/behind so với tracking ref local (không fetch). Đã sao lưu 32 tệp sửa/chưa theo dõi cùng SHA-256 và danh sách ba stash vào `.task-cache/phase4-11-14/20261119-080721/manifest.json`. Đây là cache chẩn đoán trong repo hiện có, không phải project/worktree mới. Không đọc `.env`.

Theo yêu cầu mới, chỉ tạo commit khi các mục được đưa vào đã hoàn thành và kiểm thử, tối đa hai mục cấp chính/commit. **Chưa stage hoặc commit đợt 22** vì thiếu kiểm thử Room/Keystore thực thi trên Android. Giữ nguyên HEAD và ba stash; không push, không triển khai nghiệp vụ Repository 12–13 hoặc UI 15–20.

### Nội dung đã sửa và nguồn kiểm chứng

| Phạm vi | Tệp/nội dung |
|---|---|
| 11 — DAO/JOIN tái sử dụng | `ContactDao.java`, `ContactRequestWithUser.java`: đọc bạn/chặn, pending/JOIN hồ sơ, đếm chiều nhận và xóa theo quan hệ từ bản nháp |
| 11 — merge và snapshot | `UserDao.java`, `ContactsCache.java`: giữ phone/tên/avatar thiếu và quyền riêng tư đã biết, không sửa entity input; insert/update có ABORT thay vì nuốt UNIQUE phone; thay tập contact/request trong transaction Room, giữ user_cache cho lịch sử chat |
| 11 — test Room | `ContactsRoomTest.java`: 10 ca SQLite/Room in-memory, gồm đối soát tập rỗng, blocked + hồ sơ nguyên tử, rollback hồ sơ/quan hệ/lời mời, UNIQUE phone, JOIN/status/chiều đếm, bảo toàn dòng chặn và schema v1 |
| 14 — phiên | `SessionManager.java`, `CurrentUserIdProvider.java`: khóa phiên gồm generation/actor, không chứa JWT; lưu token/actor nguyên tử, phân biệt cùng actor/token đăng nhập lại, guard bao trọn thao tác với cùng khóa logout |
| 14 — callback và cache Auth | `RealAuthRepository.java`, `RealAuthRepositoryTest.java`: dọn Room qua DISK dùng chung, dọn current/profile, loại phản hồi login/delete/profile/failure cũ và kiểm lại phiên khi tác vụ ghi thật sự chạy; 11 test |
| 14 — 401 trễ | `AuthInterceptor.java`, `SessionEvents.java`, `MainActivity.java`: 401 chỉ xóa đúng version/token; sự kiện chờ main không đăng xuất phiên mới; thay đổi Activity chỉ xử lý sự kiện phiên hiện có |
| 14 — Fake/Injection | `FakeAuthRepository.java`, `FakeContactsRepository.java`, `Injection.java`: Fake có generation, không fallback Alice khi đăng xuất; dọn LiveData cũ, chặn truy cập danh sách actor khác, giữ nghiệp vụ block/friend/pending và che phone block-only; không xếp lồng hai lần việc dọn cache |
| 14 — test mới | `FakeContactsSessionTest.java`: 8 ca; `SessionManagerTest.java`: tăng từ 2 lên 14 ca Keystore/guard/concurrency/401/offline, response fixture đi qua AuthInterceptor thật |
| Bộ chạy Windows | `scripts/test-phase4-contacts-foundation.ps1`: chọn một thiết bị đã có, sao lưu output cũ, chạy 83 JVM và 27 instrumentation, yêu cầu XML mới đúng số testcase, không trùng và không failure/error/skipped; không tự commit |

Source chính: [ContactsCache](../../app/src/main/java/com/utechat/data/local/ContactsCache.java), [UserDao](../../app/src/main/java/com/utechat/data/local/dao/UserDao.java), [SessionManager](../../app/src/main/java/com/utechat/data/session/SessionManager.java), [RealAuthRepository](../../app/src/main/java/com/utechat/data/repository/real/RealAuthRepository.java), [script Windows](../../scripts/test-phase4-contacts-foundation.ps1). Không có diff trong `AppDatabase.java` hoặc `app/schemas`: giữ schema v1, không sửa migration.

**Tách phần nháp khi commit sau nghiệm thu:** working tree Injection vẫn giữ wiring RealContactsRepository của nháp 12–13. Không đưa hunk wiring này, CONTACT_NETWORK, ContactsRepository mở rộng hoặc RealContactsRepository nháp vào commit 11/14. Đã tạo bản source kiểm phạm vi tại `.task-cache/phase4-11-14/scope-source`: dùng RealContactsRepository và ContactsRepository từ HEAD, Injection chỉ có thay đổi 14 (Auth/DISK/Fake/provider). Bản này biên dịch và chạy 73 test đạt, cho thấy code nền 11/14 được kiểm riêng với interface/constructor đã commit, không cần đưa nghiệp vụ nháp 12–13 vào. Các phương thức Fake mở rộng không bắt buộc interface cũ phải khai báo chúng. Chưa ghi bản phạm vi này vào index.

### Kiểm thử thực tế

- `javac` **JDK 27, --release 17**, thư viện thật đã cache theo phiên bản app: biên dịch source tầng dữ liệu, Injection, các test JVM; thêm probe biên dịch ba suite instrumentation thành công. Dùng `-proc:none`: đây **không phải** bằng chứng Room annotation processor, build APK/Gradle hoặc thực thi Android/Keystore. MainActivity chưa được xác nhận bằng full Android build.
- JUnit trực tiếp bộ Auth/Fake **25/25 đạt** = 11 RealAuthRepository + 6 FakeRepositories hồi quy + 8 FakeContactsSession. Sau rà lại phạm vi, chạy **73/73 đạt, exit 0** = 25 trên + 10 DTO + 16 Retrofit + 22 HTTP mapper. Log cuối ở `.task-cache/phase4-11-14/scope-unit-73.log`; không cộng 25 thành 98. Dùng ArchTaskExecutor/LiveData, Mockito 5.14.2 và ByteBuddy 1.15.4 thật; Android mockable JAR do AGP đã tạo chỉ phục vụ JVM, không thay Room/Keystore thật. Có warning ByteBuddy/Unsafe trên JDK 27, không có test failure.
- Chưa chạy lại 10 transport trong agent vì không thay source transport và các kết nối loopback trước đó đã bị chặn; Windows gate vẫn bắt buộc chạy đủ chúng, không bỏ sáu TCP. Không cộng bộ 251 backend của đợt 21 vào kết quả 73.
- Script Windows: Parser **0 lỗi**, UTF-8 BOM; **8 self-check đạt** cho bộ đọc XML (nhận fixture tốt, từ chối skipped, thiếu test, sai class, XML cũ, trùng tên, summary không khớp và node failure). Chỉ chạy hàm xác thực với XML fixture trong cache, chưa gọi toàn script hoặc giả có kết quả thiết bị.

### Trở ngại có bằng chứng và lệnh Windows

Wrapper Gradle bị chặn ghi `.gradle/wrapper/.../gradle-9.6.0-bin.zip.lck` trong thư mục người dùng, báo `Access is denied`. Đã dùng bản Gradle 9.6.0 có sẵn và cache ghi được trong `.task-cache/gradle` để gọi `:app:compileDebugAndroidTestJavaWithJavac --offline --no-daemon`; lệnh kết thúc exit 1 với **Could not connect to the Gradle daemon**. Daemon log ở `.task-cache/gradle/daemon/9.6.0/daemon-64588.out.log`; launcher chọn JAVA_HOME JDK 27 nhưng daemon thực tế ghi JDK 25 theo cấu hình toolchain hiện có. Không đổi phiên bản project hoặc cấu hình toàn máy để ép build.

`adb devices -l` báo **failed to start daemon / cannot connect to daemon**. Vì vậy **10 ContactsRoomTest + 14 SessionManagerTest + 3 RoomFoundationTest chưa chạy trên Android**. Không thay Room bằng SQLite JDBC, mock DAO hoặc đánh dấu ignored để nghiệm thu. Không lặp lại Gradle/ADB khi cùng trở ngại chưa thay đổi.

Chạy tại Terminal Windows khi có đúng một điện thoại/emulator sẵn sàng, không có lượt test khác đang chạy:

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\test-phase4-contacts-foundation.ps1"
$LASTEXITCODE
```

Nếu có nhiều thiết bị, truyền `-DeviceSerial '<serial-thực-tế>'` vào lệnh script. Script không mở emulator mới và không đổi ExecutionPolicy toàn máy. Bộ dự kiến **83 JVM** = 73 trên + 10 transport, và **27 instrumentation** = 10 + 14 + 3. Cần cả hai lượt Gradle thành công, exit script 0, đủ XML mới và failures/errors/skipped=0. Kết quả nằm ở `app/build/test-results/testDebugUnitTest` và `app/build/outputs/androidTest-results/connected`; bản sao output cũ nằm ở cache riêng có UUID. Chờ đối chiếu XML/source Windows trước khi nghiệm thu/commit, không chỉ dựa vào dòng PASS.

### Trạng thái cuối lượt

**11 `[ ]`, 14 `[ ]`: code/test và lệnh nghiệm thu đã chuẩn bị, thiếu kết quả Android thật và build Gradle.** Kiểm cuối: HEAD và ba stash không đổi, index trống; 26/32 tệp nháp ban đầu giữ nguyên SHA-256, sáu tệp sửa thuộc 11/14 (UserDao, FakeContactsRepository, SessionManager, Injection, ContactsRoomTest, CurrentUserIdProvider) đã có bản sao trước sửa. Chưa stage/commit; chưa có danh tính commit mới để kiểm. Chưa có UI mới để hướng dẫn bấm thử; kiểm thử instrumentation dùng Room in-memory và token fixture, không dùng tài khoản/secret thật. Dừng chờ kết quả Windows, không làm mục thứ ba.

## Đợt 23 - Nghiệm thu và commit 11/14 từ kết quả Windows, 19/11/2026

### Đối chiếu artifact và source

Người dùng cung cấp console `BUILD SUCCESSFUL in 58s`, 71 task executed, PASS đủ ba suite thiết bị và dòng cuối script `PASS 83 JVM + 27 Android instrumentation; failures/errors/skipped=0`. Đoạn gửi dừng tại lệnh `$LASTEXITCODE`, **không có số trả về**, nên không ghi nhận mã thoát PowerShell là bằng chứng đã được cung cấp. Artifact Android riêng có `test-result-exit-code.txt` ghi **0**; không đồng nhất nó với `$LASTEXITCODE` của tiến trình PowerShell.

Đọc trực tiếp **7 XML JVM** tại `app/build/test-results/testDebugUnitTest`, thời gian sửa **09:19:23 UTC+7**, và **1 XML Android chứa 3 suite** tại `app/build/outputs/androidTest-results/connected/debug`, thời gian sửa **09:20:21 UTC+7**, ngày 19/11/2026. Đủ đúng các lớp và số test trong script; từng testcase khớp tên method source hiện tại, không trùng tên trong lớp, không node failure/error/skipped, các summary đều failures/errors/skipped=0.

| Bộ | Suite/testcase | Kết quả |
|---|---|---|
| JVM Auth/Fake | RealAuthRepositoryTest 11; FakeRepositoriesTest 6; FakeContactsSessionTest 8 | 25/25 đạt |
| JVM hợp đồng client | ContactDtosTest 10; ContactApiTest 16; ContactErrorMapperTest 22; ContactTransportTest 10 | 58/58 đạt, gồm đủ sáu TCP |
| **Tổng JVM** | **7 suite, 83 testcase** | **Không failure/error/skipped** |
| Android Room | ContactsRoomTest 10; RoomFoundationTest 3 | 13/13 đạt trên Room/SQLite thật |
| Android phiên | SessionManagerTest 14 | 14/14 đạt, gồm Android Keystore và AuthInterceptor |
| **Tổng Android** | **3 suite, 27 testcase** | **Không failure/error/skipped** |

Nguồn trực tiếp: [XML Auth](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.repository.RealAuthRepositoryTest.xml), [XML Fake phiên](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.repository.FakeContactsSessionTest.xml), [XML transport](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.remote.api.ContactTransportTest.xml), [XML Android](<../../app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10_Pro(AVD) - 17.xml>) và [mã thoát runner Android](../../app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt). Cả ba suite Android ghi property device=`emulator-5554`; XML có tên `TEST-Pixel_10_Pro(AVD) - 17.xml`. APK debug/test mới có thời gian sửa 09:19:53/09:19:51; Room sinh `UserDao_Impl.java` với INSERT OR ABORT, UPDATE OR ABORT và transaction cho merge. `AppDatabase.java` và schema JSON không có diff.

### Kết luận theo checklist và giới hạn

**11 và 14 đạt điều kiện nghiệm thu của hai mục.** 11 đã có test Room thực thi cho đối soát, bảo toàn hồ sơ/privacy, snapshot nguyên tử, rollback, UNIQUE phone, truy vấn JOIN/count và giữ schema v1. 14 có kết quả cho guard theo actor/generation, cùng actor/token đăng nhập lại, xếp dọn/ghi cache đúng thứ tự, loại callback và sự kiện 401 cũ, offline không xóa phiên, Fake và hồi quy Auth. Giữ đợt 22 làm lịch sử bị chặn; trạng thái hiện tại là đợt 23.

Phạm vi kiểm phiên bao gồm unit test hàng đợi Auth với DAO/clear-cache fixture và instrumentation SessionManager/Keystore/AuthInterceptor; Room có bộ instrumentation riêng. Các response fixture của AuthInterceptor không phải backend HTTPS thật. Chưa nghiệm thu nghiệp vụ Repository 12–13, UI 15–20, toàn luồng nhiều tài khoản, hoặc hosted/production. Không có UI mới để hướng dẫn bấm thử trong commit này.

### Phạm vi commit và bảo toàn nháp

- Đưa đúng **hai mục 11/14** vào một commit sau đối chiếu: 17 tệp Java nguồn/test, script `scripts/test-phase4-contacts-foundation.ps1` và báo cáo này (**19 tệp**). Danh sách đầy đủ được kiểm bằng `git diff --cached --name-only` trước commit; không dùng add toàn bộ, không đưa APK/XML/log/cache/.env vào commit.
- Riêng Injection dùng bản `.task-cache/phase4-11-14/scope-source/Injection.java` đã kiểm: chỉ Auth/DISK/Fake/provider; giữ constructor RealContactsRepository ba tham số ở HEAD, không đưa CONTACT_NETWORK/wiring nháp 12–13 vào index. Working tree Injection không bị thay nội dung. ContactsRepository/RealContactsRepository nháp, UI, resource và dependency UI vẫn ngoài commit.
- Kiểm lại assembly source phạm vi này trước commit bằng javac release 17 và JUnit: **73/73 đạt, exit 0**, log `.task-cache/phase4-11-14/acceptance-scope-73.log`. Đây là kiểm tương thích khi loại nháp 12–13 khỏi source commit; không cộng vào 83/27 và không thay bằng chứng Android Windows. Không chạy lại Gradle/ADB/instrumentation hoặc ghi đè artifact Windows trong lượt đối chiếu.
- Snapshot SHA-256 trước đối chiếu ghi nhận 131 tệp working tree/artifact; sau thao tác chỉ báo cáo được sửa nội dung, các tệp code/test và artifact giữ nguyên. Ba stash được so trước/sau; không reset/xóa/stash hoặc sửa lịch sử. Author và Committer của commit đều `MeanFen <nghiapp11102006@gmail.com>`, kiểm lại trên commit vừa tạo.
- Dừng sau 11/14; 12–13 và 15–20 giữ `[ ]`. Không triển khai mục thứ ba và không push.

## Đợt 24 - Repository REST/Room danh bạ: mục 12–13, 19/11/2026

### Phạm vi và bảo toàn

Theo yêu cầu tiếp tục tối đa hai mục chính, chọn **12 và 13** sau khi 11/14 đã nghiệm thu tại **6c0d31c**. Git lúc bắt đầu ở `phase4_NP`, không báo ahead/behind tracking ref local; không fetch. Có 25 tệp nháp sửa/chưa theo dõi, đã sao lưu nguyên tệp và SHA-256 vào `.task-cache/phase4-12-13/20261119-093223/manifest.json`, cùng HEAD và ba stash. Tiếp tục trong TeamProject hiện có; không tạo project/worktree, không sửa lịch sử, không đọc `.env` hoặc push.

Quy tắc commit giữ nguyên yêu cầu mới: chỉ commit sau khi người dùng chạy test và kết quả được đối chiếu, tối đa hai mục đã kiểm thử/commit. Đợt 24 **chưa stage/commit**; chỉ chuẩn bị code/test và lệnh Windows. Những đoạn chờ của đợt 22 là lịch sử; nghiệm thu 11/14 tại đợt 23 vẫn giữ nguyên.

### Tệp và hành vi trong hai mục

| Tệp | Nội dung |
|---|---|
| `app/src/main/java/com/utechat/data/repository/real/RealContactsRepository.java` | Tái sử dụng constructor và các route nháp; dùng HTTP/failure mapper chung, kiểm body/DTO/actor/pending trước transaction, snapshot đủ ba danh sách; NETWORK/DISK FIFO, revision và guard phiên tại NETWORK/DISK/main |
| `app/src/main/java/com/utechat/data/repository/ContactsRepository.java` | Giữ ba chữ ký cache cũ và các chữ ký mở rộng của nháp: getRequests/incomingCount/refresh/lookup/act/sessionKey/currentUserId |
| `app/src/main/java/com/utechat/data/repository/ContactsResult.java` | Ghi rõ semantics cờ refreshRequired cho ACK đã xác nhận so với lỗi chưa có ACK hợp lệ |
| `app/src/main/java/com/utechat/data/repository/CurrentUserIdProvider.java` | Thêm sessionChanges để luồng đọc cache loại dữ liệu phiên cũ |
| `app/src/main/java/com/utechat/data/session/SessionManager.java` | Phát khóa phiên không chứa JWT trên main, bỏ thông báo version cũ; tái sử dụng generation/clear/guard của 14 |
| `app/src/main/java/com/utechat/data/repository/fake/FakeAuthRepository.java` | Cung cấp sessionChanges cùng hợp đồng cho Fake, không đổi nghiệp vụ Auth |
| `app/src/main/java/com/utechat/di/Injection.java` | Giữ wiring nháp NETWORK riêng và DISK dùng chung với clear phiên, provider thật SessionManager; sẽ thuộc commit 12/13 sau nghiệm thu |
| `app/src/test/java/com/utechat/data/repository/RealContactsRepositoryTest.java` | Mở rộng 3 test nháp thành 43 ca, điều khiển NETWORK/DISK/main và DAO/API fixture |
| `app/src/androidTest/java/com/utechat/data/repository/ContactsRepositoryRoomTest.java` | 12 ca Room in-memory thật với Repository/Retrofit/Gson; một ca chạy TCP loopback send → đối soát → cancel → đối soát |
| `scripts/test-phase4-contacts-foundation.ps1` | Giữ bộ mặc định 83 JVM + 27 Android, thêm switch -Repository để chạy 126 JVM + 39 Android; backup output theo scope, kiểm XML mới |
| `docs/phase4/01-phone-validation.md` | Báo cáo tiến độ duy nhất, giữ 12/13 chưa nghiệm thu đến khi đối chiếu Windows |

Nguồn chính: [Repository](../../app/src/main/java/com/utechat/data/repository/real/RealContactsRepository.java), [43 test JVM](../../app/src/test/java/com/utechat/data/repository/RealContactsRepositoryTest.java), [12 test Android](../../app/src/androidTest/java/com/utechat/data/repository/ContactsRepositoryRoomTest.java). Thay đổi sessionChanges là phụ thuộc để các luồng cache của 12/13 không phát dữ liệu phiên cũ, không mở hạng mục chính thứ ba. DAO/schema/migration, backend, UI/resource và dependency UI không được triển khai thêm trong đợt này.

### Quy tắc dữ liệu và thứ tự

- Luồng bạn/chặn/lời mời/count/search cache gắn với một lần đăng nhập và actor; actor sai nhận rỗng. Đợi hàng rào DISK sau dọn phiên trước khi kích hoạt truy vấn; logout/đổi phiên làm luồng cũ phát rỗng và không được hoạt động lại khi đăng nhập cùng actor.
- Snapshot tải đủ friends/blocked/requests, kiểm trùng/actor/status/createdAt và cấu trúc trước khi xóa bất kỳ cache nào. Mọi xóa/merge/insert cùng transaction Room; UserDao của 11 giữ phone/hồ sơ thiếu và quyền riêng tư đã biết. Lỗi HTTP, vận chuyển hoặc DTO trước transaction giữ snapshot trước đó; lỗi SQLite rollback và trả CACHE_WRITE_FAILED.
- Lookup không ghi quan hệ, dùng mapper chung để giữ 409/429/details và phân biệt lỗi JSON với lỗi mạng. Chỉ lookup mới nhất và đúng phiên được phát trên main. Kiểm phiên giữa các HTTP trong snapshot, không tiếp tục đọc bằng tài khoản mới khi một request cũ đang chạy.
- Send chỉ lưu pending từ ACK 201 hợp lệ; reject/cancel/accept xóa pending đã xử lý. Accept lịch sử ACCEPTED không tự tạo friend: phải lấy danh sách server. Block giữ dòng chặn và xóa pending liên quan, unfriend giữ dòng bị chặn, unblock xóa dòng quan hệ chưa biết để chờ đối soát; không tự suy ra vẫn là bạn. Profile caller khác ID bị bỏ qua.
- Epoch đổi khi bắt đầu/kết thúc ghi và bắt đầu đọc mới; snapshot cũ không được ghi đè mutation mới. NETWORK và DISK trong Injection đều single-thread FIFO; local patch đã được ACK xếp trên DISK trước snapshot theo sau. Cờ cache được kiểm lại khi callback thật sự đến main, không chỉ khi I/O vừa xong.
- `isSuccess=true, refreshRequired=true`: server đã ACK hợp lệ, refresh/ghi cache sau đó chưa hoàn tất hoặc lỗi; không gửi lại mutation vì lỗi refresh. `isSuccess=false` với NETWORK/INVALID_RESPONSE và refreshRequired=true: chưa có ACK hợp lệ, có thể server đã commit; tải lại trước khi cân nhắc thử lại. HTTP từ chối mutation giữ lỗi/metadata và refreshRequired=false. Không tự retry hoặc tự clear phiên trong Repository; AuthInterceptor/SessionManager hiện có chịu trách nhiệm 401.

### Kiểm thử thực tế và giới hạn

- `javac` JDK 27, `--release 17`, thư viện thật đã cache: biên dịch tầng dữ liệu/Injection và các test JVM thành công. JUnit trực tiếp cuối cùng **116/116 đạt, exit 0** = **43 Repository + 25 Auth/Fake + 48 DTO/Retrofit/HTTP mapper**; log `.task-cache/phase4-12-13/unit-final.log`. Không cộng với 115 của vòng trước hoặc với 83/27 đợt 23. Vòng thử đầu có lỗi cấu hình Mockito trong helper test (stubbing lồng), đã sửa trước kết quả cuối. Warning ByteBuddy/Unsafe trên JDK 27 vẫn có, không phải test failure.
- Probe `javac -proc:none` biên dịch 12 test instrumentation mới cùng các suite thiết bị hồi quy thành công; log `.task-cache/phase4-12-13/device-compile.log`. Đây không phải build Gradle, Room annotation processor, chạy Room/Keystore hoặc thực thi TCP trên Android. Không gọi Gradle/ADB hoặc bộ thiết bị trong lượt này; bàn giao Windows theo yêu cầu người dùng.
- 43 ca JVM kiểm snapshot toàn phần/lỗi một phần/DTO hỏng/UNIQUE seam; lookup/metadata 409/429; cả bảy route mutation; ACK vs mất ACK/refresh lỗi, local patch lỗi rồi tự đối soát được; callback cũ tại NETWORK/DISK/main; luồng cache sai actor/đổi phiên và hàng rào clear; snapshot cũ không ghi đè block mới; không tạo friend từ accept lịch sử.
- 12 ca Android yêu cầu Room/SQLite thật: đối soát giữ profile/privacy, lỗi HTTP/DTO không phá cache, UNIQUE rollback; accept lịch sử; block–unfriend–unblock; đổi phiên khi DISK chờ; che LiveData trước cleanup; mất ACK không retry; refresh cũ vs block mới; trigger gây lỗi local patch rollback nhưng ACK vẫn đúng; một luồng send/cancel qua ServerSocket/TCP → Retrofit/Gson → Repository → Room.
- HTTP của các ca Android còn lại dùng response interceptor fixture; ca TCP dùng server fixture trên loopback. **Không phải backend PostgreSQL/hosted HTTPS thật**, không nghiệm thu production hoặc toàn luồng UI. Backend/hợp đồng HTTP thật có bằng chứng riêng ở 09–10; không cộng test backend vào số Repository. Fake/Keystore/Room nền vẫn được script chạy lại, chưa lấy artifact cũ để báo đạt đợt mới.
- Script PowerShell: Parser **0 lỗi**, UTF-8 BOM; kiểm tham số DeviceSerial/Repository và đếm source khớp **43 JVM + 12 Android** mới, selector mặc định **83+27**, selector Repository **126+39**. Tám self-check đọc XML đạt (nhận fixture tốt, từ chối skipped, thiếu ca, sai class, XML cũ, trùng tên, summary sai và node failure); log `.task-cache/phase4-12-13/gate-self-check.log`. Không chạy whole-script để giả có kết quả Windows.

### Lệnh Windows PowerShell 5.1 và điều kiện nghiệm thu

Dùng đúng script hiện có. `-Repository` bao gồm các suite hồi quy nên không cần chạy bộ mặc định riêng. `emulator-5554` là serial người dùng đã cung cấp ở đợt trước; script kiểm lại trạng thái device trước khi chạy, không mở emulator mới. Giữ thiết bị sẵn sàng và không chạy trùng lượt test khác:

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\test-phase4-contacts-foundation.ps1" -Repository -DeviceSerial 'emulator-5554'
$LASTEXITCODE
```

Nếu serial đã thay đổi, dùng output `adb devices` để chọn dòng device và thay đúng giá trị `-DeviceSerial`; không đoán serial. Script tự sao lưu output cũ vào cache phase4-12-13 riêng có UUID trước Gradle, không đổi ExecutionPolicy toàn máy. Cần **126 JVM (8 suite) + 39 instrumentation (4 suite)**, cả hai Gradle BUILD SUCCESSFUL, script exit 0, đủ XML mới, số/tên testcase khớp source và failures/errors/skipped đều 0. XML ở `app/build/test-results/testDebugUnitTest` và `app/build/outputs/androidTest-results/connected`; gửi cả kết quả cuối và giá trị số sau `$LASTEXITCODE` để đối chiếu trước commit.

### Điểm dừng

**12 `[ ]`, 13 `[ ]`**, chờ Windows/Android thật; 01–11 và 14 giữ nghiệm thu cũ. Kiểm cuối: HEAD và ba stash không đổi, index trống; 23/25 bản nháp đầu lượt giữ nguyên SHA-256, chỉ RealContactsRepository và test của nó được sửa trong tập nháp đó, bản cũ còn ở backup. Chưa stage/commit nên chưa có danh tính commit mới để kiểm; sau khi test và đối chiếu đạt mới đưa đúng hai mục và báo cáo vào một commit, Author/Committer MeanFen. UI hiện có vẫn là nháp chưa nghiệm thu, không có hướng dẫn bấm UI mới cho đợt này. Giữ nháp ngoài phạm vi, stash, backup và artifact lần Windows trước; dừng chờ kết quả, không triển khai 15 hoặc tự push.

### Sửa bước sao lưu trước test trên Windows PowerShell 5.1

Người dùng chạy `-Repository -DeviceSerial 'emulator-5554'`, script dừng exit 1 trong `Copy-Item` khi sao lưu logcat `SessionManagerTest-freshInstanceReadsEncryptedIdentityAfterProcessRestart.txt`. Lỗi phát sinh **trước lệnh Gradle**, chưa có bằng chứng bộ 126/39 chạy; không ghi nhận là test Repository thất bại.

Đo đường dẫn đích gây lỗi được **260 ký tự**. Tái hiện bằng **Windows PowerShell 5.1.26100.9818** thực tế: Copy-Item cây connected vào cấu trúc backup cũ thất bại tại cùng tệp, nhưng sao chép cùng nguồn sang đường dẫn ngắn hơn thành công. Nguồn tồn tại, không phải thiếu logcat. Kết quả phù hợp giới hạn MAX_PATH 260 gồm ký tự kết thúc của API Windows: [Microsoft — Maximum Path Length Limitation](https://learn.microsoft.com/en-us/windows/win32/fileio/maximum-file-path-limitation). Log tái hiện ở `.task-cache/phase4-12-13/backup-fix/repro-final.log`.

Chỉ sửa phần backup của script hiện có: tạo `testDebugUnitTest.zip` và `connected.zip` trong thư mục UUID, giữ cây tên tương đối trong archive thay vì tạo đường dẫn đích lồng dài. Dùng ZipFile sẵn có của .NET, đối chiếu số lượng, kích thước và SHA-256 từng entry với nguồn trước khi cho Gradle chạy. Tên entry của .NET Framework/PS 5.1 có thể dùng dấu `\`; chuẩn hóa dấu phân cách chỉ lúc đối chiếu. Không xóa bản backup dở của lần lỗi, không thay source/log gốc, registry, ExecutionPolicy toàn máy, tham số hoặc selector test.

Đã kiểm riêng hàm backup trích từ script bằng **PowerShell 5.1**, không gọi whole-script/ADB/Gradle: **9 tệp JVM + 32 tệp connected** đóng ZIP và khớp từng SHA-256; kiểm thêm fixture Unicode, nhị phân, thư mục rỗng và từ chối ghi đè ZIP có sẵn đều đạt. Parser PS 5.1 có 0 lỗi; 41 artifact nguồn giữ nguyên hash. Log tại `.task-cache/phase4-12-13/backup-fix/check.log`. Các ZIP kiểm chứng nằm trong cache riêng, không đưa vào commit.

Lệnh Windows ở trên **giữ nguyên**, tiếp tục dùng `-Repository -DeviceSerial 'emulator-5554'` khi thiết bị còn ở trạng thái device. Bộ dự kiến vẫn **126 JVM + 39 instrumentation**. Trạng thái 12/13 vẫn `[ ]`; chưa stage/commit/push và chưa có kết quả test mới của hai mục sau lần sửa backup.

## Đợt 25 - Nghiệm thu và commit Repository: mục 12–13, 19/11/2026

### Đối chiếu Windows, XML và source

Người dùng cung cấp `BUILD SUCCESSFUL in 2m 21s`, 71 task executed, PASS đủ bốn suite Android, tổng `126 JVM + 39 Android instrumentation`, failures/errors/skipped=0 và **`$LASTEXITCODE=0`**. Đã đối chiếu trực tiếp artifact mới:

- **8 XML JVM**, thời gian sửa **09:57:58 UTC+7**, ngày 19/11/2026; tổng 126 testcase. Từng suite có số/tên method khớp source hiện tại, không trùng testcase trong lớp, không node failure/error/skipped; mọi summary failures/errors/skipped=0.
- **1 XML Android chứa 4 suite**, thời gian sửa **10:00:18 UTC+7**; tổng 39 testcase, số/tên method khớp source, không trùng hoặc bỏ qua. Cả bốn suite ghi device=`emulator-5554`; tên file `TEST-Pixel_10_Pro(AVD) - 17.xml`. `test-result-exit-code.txt` của runner Android ghi 0, khớp trạng thái thành công của console người dùng.
- **11 tệp source/test/script/báo cáo** trong manifest `.task-cache/phase4-12-13/before-windows-backup-fixed-manifest.json` không đổi SHA-256 từ lúc chuẩn bị đến lúc đối chiếu. Sau kiểm này chỉ cập nhật báo cáo; không sửa code đã được Windows test để rồi dùng lại XML cũ.
- Backup lần chạy mới ở `.task-cache/phase4-12-13/windows-6333a073857844be943c7cb0b0a881a6`, thời gian thư mục **09:56:44**: `testDebugUnitTest.zip` giữ 9 tệp và `connected.zip` giữ 32 tệp. Đọc từng entry và so SHA-256 với manifest trước test: **41/41 artifact cũ khớp**, không cần giải nén đường dẫn dài. Lỗi backup trước đó được giữ làm lịch sử, không đánh đồng với lỗi test.

| Phạm vi | Suite/testcase | Kết quả |
|---|---|---|
| JVM Repository mới | RealContactsRepositoryTest 43 | 43/43 đạt |
| JVM Auth/Fake hồi quy | RealAuthRepositoryTest 11; FakeRepositoriesTest 6; FakeContactsSessionTest 8 | 25/25 đạt |
| JVM hợp đồng client hồi quy | ContactDtosTest 10; ContactApiTest 16; ContactErrorMapperTest 22; ContactTransportTest 10 | 58/58 đạt |
| **Tổng JVM** | **8 suite, 126 testcase** | **Failures/errors/skipped=0** |
| Android Repository/Room/TCP mới | ContactsRepositoryRoomTest 12 | 12/12 đạt |
| Android nền hồi quy | SessionManagerTest 14; RoomFoundationTest 3; ContactsRoomTest 10 | 27/27 đạt |
| **Tổng Android** | **4 suite, 39 testcase** | **Failures/errors/skipped=0** |

Nguồn trực tiếp: [XML Repository JVM](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.repository.RealContactsRepositoryTest.xml), [XML transport](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.remote.api.ContactTransportTest.xml), [XML Android](<../../app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10_Pro(AVD) - 17.xml>), [mã thoát runner](../../app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt), [source 43 test](../../app/src/test/java/com/utechat/data/repository/RealContactsRepositoryTest.java) và [source 12 test](../../app/src/androidTest/java/com/utechat/data/repository/ContactsRepositoryRoomTest.java). Không cộng 116 test agent trước đó, 83/27 của đợt 23 hoặc test backend vào tổng lần này.

### Nghiệm thu và giới hạn

**12 và 13 đạt điều kiện checklist trong phạm vi Repository REST/Room và hợp đồng phiên.** Đã có kết quả cho lookup/lời mời, mọi route mutation, đối soát ba danh sách, lỗi một phần không phá cache, rollback SQLite thật, phiên cũ/độ mới của response, LiveData đổi phiên, ACK xác nhận nhưng refresh lỗi so với kết quả ghi chưa biết, không tự retry và không tạo friend từ lịch sử ACCEPTED. Guard được kiểm khi NETWORK bắt đầu, khi DISK ghi và khi main thực sự nhận kết quả. Kết quả Room thật đóng phần còn thiếu ở đợt 24; checklist 12/13 chuyển `[x]`.

Ca TCP Android dùng ServerSocket fixture thật với Retrofit/Gson → Repository → Room; các ca HTTP Android khác dùng response interceptor fixture. Đây không phải kiểm toàn luồng với backend PostgreSQL/hosted HTTPS; bằng chứng backend/contract tại 09–10 vẫn tách riêng. FIFO NETWORK/DISK là điều kiện constructor được Injection cung cấp bằng hai single-thread executor, DISK dùng chung với clear phiên. Chưa nghiệm thu UI 15–20, offline toàn luồng nhiều màn hoặc production. Không có UI mới để hướng dẫn bấm trong commit này.

Console người dùng có warning `sun.misc.Unsafe::arrayBaseOffset` từ protobuf 3.25.5; XML vẫn không failure/error/skipped. Không thay dependency chỉ để xóa warning. Các lỗi SQLite/trigger trong test rollback là điều kiện fixture có chủ đích; tên testcase tương ứng đều đạt.

### Commit và bảo toàn

- Đưa đúng **12/13** vào một commit sau đối chiếu: 7 tệp nguồn (`ContactsRepository`, `ContactsResult`, `CurrentUserIdProvider`, `FakeAuthRepository`, `RealContactsRepository`, `SessionManager`, `Injection`), 2 tệp test (`RealContactsRepositoryTest`, `ContactsRepositoryRoomTest`), script và báo cáo: **11 tệp**. Nội dung đầy đủ đã được liệt kê ở bảng đợt 24; chỉ stage các đường dẫn này, không add toàn bộ.
- Snapshot trước cập nhật báo cáo tại `.task-cache/phase4-12-13/acceptance-commit-manifest.json` ghi SHA-256 **135 tệp** working tree/artifact/ZIP và ba stash. Chỉ nội dung báo cáo thay đổi trong lượt đối chiếu; source đã test, các nháp ngoài phạm vi, artifact mới và ZIP cũ giữ nguyên. Không đưa XML/log/APK/ZIP/cache/.env vào commit, không reset/xóa/stash hoặc sửa lịch sử.
- Author và Committer yêu cầu `MeanFen <nghiapp11102006@gmail.com>`; kiểm trên commit vừa tạo. Không chạy lại Gradle/ADB/test trong lượt đối chiếu vì source khớp manifest và đủ bằng chứng mới.
- **01–14 đã nghiệm thu theo phạm vi từng mục; 15–20 giữ `[ ]`.** Dừng sau commit 12/13, không triển khai mục thứ ba và không push.

## Đợt 26 - Chuẩn bị ViewModel và danh sách bạn bè: mục 15–16, 19/11/2026

### Phạm vi và thay đổi

Tiếp tục trên `TeamProject`, nhánh `phase4_NP`, HEAD nền `421470647a2b981016c15e5678fbdc3f644090fc`. Chỉ hai mục **15/16**, sau khi 11/14 và 12/13 đã được nghiệm thu. Snapshot 21 tệp dirty/untracked đầu đợt, HEAD và ba stash ở `.task-cache/phase4-15-16/20261119-101327/manifest.json`; có bản sao nội dung nháp trước khi sửa. Không tạo project/worktree khác.

| Phần | Nội dung chuẩn bị trong đợt này |
|---|---|
| ViewModel 15 | Giữ phone/filter/tab và hạn chờ 429 trong SavedStateHandle; một tác vụ/observer tại một thời điểm, gỡ observer kể cả callback đồng bộ và onCleared; thông báo được tiêu thụ một lần. Năm trạng thái RelationshipState có danh sách thao tác, không có Nhắn tin. |
| Phiên và lỗi | ContactsRepository chuyển tiếp sessionChanges từ provider hiện có, không thiết kế lại cơ chế phiên 14. So khóa thật từ Repository để chấp nhận phiên đã khôi phục khi tín hiệu LiveData ban đầu còn null. Đổi phiên xóa nhập liệu/kết quả/cache hiển thị ngay; bỏ callback cũ. 409 có ID/chiều hợp lệ chỉ cập nhật pending, không tự accept; thiếu metadata buộc tải lại. ACK nhưng cache chưa mới và lỗi chưa rõ có ghi server hay chưa đều không cho tự lặp mutation. |
| Danh sách 16 | Fragment riêng chỉ hiển thị bạn bè, lọc tên không phân biệt hoa thường, kéo tải lại, empty/loading/error/retry; lỗi mạng giữ cache. Badge nhận count từ Repository. ListAdapter/DiffUtil sao payload và kiểm lại vị trí click; hủy kết bạn/chặn qua DialogFragment có xác nhận, giữ qua recreate. ViewBinding/observer/watcher theo vòng đời View, chuỗi Anh–Việt và màu DayNight. |
| Điều hướng | Giữ root nav_contacts, contactsListFragment, title_contacts và ba action/destination ID. Ba đích con tạm dùng ContactsDestinationFragment với thông báo chưa khả dụng và nút Back: chỉ kiểm nối graph/back stack cho 16. **Không nghiệm thu hoặc đưa chức năng màn tìm người/lời mời/chặn 17–19 vào đợt này.** |
| Kiểm thử/bàn giao | 31 test JVM ViewModel; 12 test Android UI; host chỉ có ở Debug, non-exported, dùng graph/View/XML thật. Tái sử dụng script hiện có, thêm -ContactsUi bao gồm toàn bộ hồi quy 11–14; giữ ZIP backup và kiểm hash tương thích PowerShell 5.1. |

Nguồn: [ViewModel](../../app/src/main/java/com/utechat/ui/contacts/ContactsViewModel.java), [danh sách bạn](../../app/src/main/java/com/utechat/ui/contacts/ContactsListFragment.java), [adapter](../../app/src/main/java/com/utechat/ui/contacts/ContactsAdapter.java), [xác nhận](../../app/src/main/java/com/utechat/ui/contacts/ContactsConfirmDialog.java), [graph](../../app/src/main/res/navigation/nav_contacts.xml), [31 test JVM](../../app/src/test/java/com/utechat/ui/contacts/ContactsViewModelTest.java), [12 test UI](../../app/src/androidTest/java/com/utechat/ui/contacts/ContactsListUiTest.java), [script](../../scripts/test-phase4-contacts-foundation.ps1).

Đã tái sử dụng bản nháp thuộc 15/16. Tám tệp ngoài phạm vi giữ nguyên SHA-256: `.gitignore`, `ContactsScreenFragment`, `SearchUserFragment`, `FriendRequestsFragment`, `BlockedUsersFragment`, `fragment_search_user.xml`, bản nháp backend `ContactsApiTest` và `tash list`. Hai dependency file có sẵn thay đổi SwipeRefreshLayout được giữ nguyên nội dung nháp và thuộc phạm vi 16. Các lớp màn con nháp vẫn biên dịch trong working tree nhưng không được graph mới gọi; không cần đưa các lớp đó vào commit 15/16. Không đổi backend/schema/DAO, URL mạng, secret hoặc cấu hình release.

### Kiểm đã thực hiện và phần chưa có bằng chứng

- **JUnit trực tiếp: 147/147 đạt, exit 0** = 31 ViewModel mới + 116 hồi quy dữ liệu/HTTP mapper. Dùng JDK 27 `--release 17`, AndroidX và dependency thật đã cache, mockable android.jar của AGP. Log `.task-cache/phase4-15-16/unit.log`. Không gồm 10 ContactTransportTest dùng socket, không phải Gradle test hoặc Android instrumentation; không cộng kết quả các đợt cũ vào lần này.
- 31 ca mới kiểm tải/giữ cache khi lỗi, loading/chống click lặp, gỡ observer, SavedStateHandle cùng/khác phiên, tín hiệu phiên ban đầu null, validation/normalize, năm trạng thái, metadata 409 hai chiều, cooldown 429 không dùng sleep, bỏ lookup cũ khi đổi phone, xóa phiên/callback cũ, thông báo một lần, ACK/cache chưa mới và không retry mutation chưa rõ kết quả.
- **Gradle offline biên dịch app, JVM test và Android test thành công**, gồm AAPT/resource, ViewBinding và các bản nháp đang có; log `.task-cache/phase4-15-16/compile-final.log`. Đây chỉ là compile, **không chứng minh 12 ca UI hay Room/Keystore đã chạy**. Lượt đầu môi trường agent không nạp được native-platform.dll; sau khi được phép dùng runtime/cache thật, compiler phát hiện API vị trí dòng chưa có ở RecyclerView hiện dùng và import closeSoftKeyboard mơ hồ; đã sửa trước build cuối. Không nâng RecyclerView hoặc thư viện để né lỗi. Cảnh báo API Bundle Serializable cũ và unchecked generics của test còn được compiler ghi nhận.
- **PowerShell 5.1.26100.9818**: parser 0 lỗi; selector và số @Test trong source khớp cả ba chế độ: mặc định **83+27**, -Repository **126+39**, -ContactsUi **157+51**. Tám self-check XML đạt: nhận fixture đúng, từ chối XML cũ/skipped/thiếu ca/sai class/trùng tên/summary sai/node failure. Log `.task-cache/phase4-15-16/script-check.log`. Chỉ trích và kiểm selector/hàm đọc XML; không gọi whole-script, ADB hoặc mở thiết bị trong agent.
- **Chưa chạy Android UI, Room/Keystore hoặc full bộ 157/51 trên Windows cho source đợt này.** 12 ca UI được chuẩn bị cho hiển thị/lọc và empty, badge pending đến, recreate không tải trùng, kéo refresh/disable, cancel và confirm unfriend, block qua recreate, ba destination/back stack, cache khi offline và retry refresh, mutation chưa rõ kết quả, logout, tiếng Việt/chế độ tối và nhãn accessibility. UI dùng Fake fixture có điều khiển; 39 ca Room/Keystore hồi quy dùng bộ hiện có. Không thay test DAO bằng Fake hoặc xem fixture UI là backend/PostgreSQL thật; toàn luồng với ba tài khoản/hosted vẫn thuộc 20.

### Lệnh Windows PowerShell 5.1

`-ContactsUi` đã bao gồm Repository và các bộ nền nên chỉ chạy một lệnh dưới đây, không chạy thêm -Repository đồng thời. `emulator-5554` là serial người dùng đã cung cấp, script kiểm lại dòng device; nếu serial thay đổi thì lấy lại từ adb devices. Bước chuẩn bị thiết bị/backup thất bại trước Gradle không được tính là test thất bại hoặc test đã chạy.

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\test-phase4-contacts-foundation.ps1" -ContactsUi -DeviceSerial 'emulator-5554'
$LASTEXITCODE
```

Cần **157 JVM (9 suite) + 51 Android instrumentation (5 suite)**: 31/12 mới cộng 126/39 hồi quy, đủ XML mới và failures/errors/skipped=0, hai Gradle BUILD SUCCESSFUL, script exit 0. Script giữ backup ZIP tại `.task-cache/phase4-15-16/windows-UUID`; không thay ExecutionPolicy toàn máy. XML tại `app/build/test-results/testDebugUnitTest` và `app/build/outputs/androidTest-results/connected`. Sau người dùng chạy mới đối chiếu thời gian, số/tên testcase với source và SHA-256 manifest bàn giao; chưa dùng XML 12/13 cũ để nghiệm thu 15/16.

### Bấm thử và điểm dừng

Trên app với tài khoản thử đã đăng nhập, mở **Danh bạ**: kiểm tên/tiêu đề, nhập một phần tên rồi xóa bộ lọc, kéo xuống làm mới. Mở **Thao tác** của một bạn: chọn hủy kết bạn/chặn, thử đóng xác nhận trước, rồi xác nhận bằng tài khoản thử; kiểm hàng tương ứng được cập nhật. Xoay màn hình khi đã lọc và khi dialog đang mở; chuyển sáng/tối, Anh/Việt; thử mất mạng rồi Tải lại, kiểm cache còn hiển thị và không gửi lại mutation khi chỉ tải lại. Ba nút tìm người/lời mời/danh sách chặn hiện chỉ tới đích chưa khả dụng và quay về; nghiệp vụ các màn đó chưa làm trong đợt này. Kết quả bấm thử thủ công **chưa được kiểm chứng trong agent**.

Manifest bàn giao `.task-cache/phase4-15-16/before-windows-manifest.json` ghi hash 24 tệp trong phạm vi, tám nháp ngoài phạm vi, 54 artifact trước test, tên 31/12 ca mới, HEAD/branch và ba stash để đối chiếu sau Windows. `git diff --check` đạt; kiểm tên tiến trình Java/ADB/Gradle cuối lượt không trả dòng nào, không có kết quả đọc command line qua CIM vì môi trường từ chối truy cập CIM.

**15 `[ ]`, 16 `[ ]`**, chờ kết quả Windows/Android và đối chiếu. 01–14 giữ nghiệm thu cũ; 17–20 giữ nguyên phạm vi chưa nghiệm thu. Index trống, HEAD và ba stash giữ nguyên; chưa stage/commit/push. Chỉ sau khi đủ test và đối chiếu mới commit tối đa hai mục 15/16 cùng báo cáo, Author và Committer `MeanFen <nghiapp11102006@gmail.com>`, kiểm danh tính trên commit mới. Dừng chờ kết quả, không triển khai mục thứ ba.

### Đối chiếu lượt Windows thất bại và sửa UI, 19/11/2026

Người dùng cung cấp connectedDebugAndroidTest FAILED, BUILD FAILED in 4m 54s và script exit 1. Đây là lượt đã chạy test, khác lỗi chuẩn bị thiết bị/backup ở các đợt trước. Trước khi sửa, cả 24 tệp scope khớp SHA-256 manifest bàn giao.

- **JVM: 157/157 đạt**, 9 XML mới, tên/số method khớp chín source, không failure/error/skipped. Có đủ ContactTransportTest 10 ca và ContactsViewModelTest 31 ca; không thay kết quả này bằng con số JUnit trực tiếp 147 của agent.
- **Android: chưa đủ bộ 51.** XML mới ghi ContactsRepositoryRoomTest 12/12 đạt; ContactsListUiTest chỉ ghi 8/12 ca và cả tám lỗi: bảy RootViewWithoutFocusException trước thao tác/assert UI, một IllegalStateException trong ca offlineRetainsRowsAndRetryOnlyRefreshes khi SnackbarLayout bị thêm vào FragmentContainerView. Runner còn ghi android-test.testRunFailed do process crash; đây là bản ghi lỗi runner, không tính thành ca nghiệp vụ. Bốn ca UI và ba suite nền 27 ca không có bằng chứng hoàn tất ở lượt này.
- Lỗi crash có stack trace tại BaseTransientBottomBar.showView → FragmentContainerView.addView. ContactsUi.notice cũ truyền root Fragment; host Debug cũ gắn NavHost vào android.R.id.content, NavHost cũng dùng ID này cho root của mình. Đã đổi Snackbar sang content Activity rõ ràng, host dùng ID Debug riêng contacts_ui_test_host. Ca offline bổ sung assert snackbar_text để kiểm regression; không bỏ ca lỗi hoặc nới gate XML.
- Chẩn đoán riêng bằng ADB đang chạy sẵn: emulator-5554 có trạng thái device, màn hình Awake, isKeyguardShowing=false. Không có instrumentation khác khi bắt đầu ca tái hiện. APK test đã bị gỡ sau lượt Gradle, lệnh am instrument đầu tiên báo Unable to find instrumentation info và **không chạy test**; sau install -r đúng hai APK cũ, một ca UI tái hiện vẫn lỗi focus. Dù WMS ghi cửa sổ ContactsUiTestActivity đang focus, Espresso ghi DecorView.hasWindowFocus=false. **Chưa xác định nguyên nhân gốc focus; không kết luận thiết bị bị khóa hoặc do warning Unsafe.** Log chẩn đoán `.task-cache/phase4-15-16/focus-repro-installed.log`.
- XML/log lỗi và JVM được giữ trong `.task-cache/phase4-15-16/failed-20261119-104424/{connected.zip,jvm.zip,manifest.json}`. Đọc ZIP và đối chiếu SHA-256 **36/36 tệp khớp**; log gốc không bị xóa. Tên method/số ca JVM đã đối chiếu tại verified-jvm.json cùng thư mục.

Nguồn: [XML Android lỗi](<../../app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10_Pro(AVD) - 17.xml>), [XML ViewModel JVM](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.ui.contacts.ContactsViewModelTest.xml), [ContactsUi](../../app/src/main/java/com/utechat/ui/contacts/ContactsUi.java), [host Debug](../../app/src/debug/java/com/utechat/ui/contacts/ContactsUiTestActivity.java). 15/16 vẫn `[ ]`; chưa commit/push, không chuyển sang mục 17.

Kiểm tiếp sau sửa host: ca danh sách vẫn lỗi focus, dù requestFocus đã tạo focus cho View; bỏ thử nghiệm requestFocus/KEEP_SCREEN_ON khỏi bản cuối. Ảnh `.task-cache/phase4-15-16/focus-screen.png` chụp lúc ca chạy có nền đen và thanh điều hướng; người dùng cũng xác nhận nhìn thấy màn đen. Thử android:hardwareAccelerated=false **chỉ trên host Debug** vẫn thất bại (`focus-software-probe.log`), nên đã bỏ thuộc tính thử này; không suy ra nguyên nhân gốc là GPU. Không đổi AVD, driver, dependency, Activity chính hoặc kiểm tra focus của Espresso.

Ca kiểm parent Snackbar ban đầu có assert đọc lại thông báo ngắn sau launch và không tìm thấy View (`snackbar-parent-probe.log`); không dùng ca đó để báo regression đạt. Bản cuối giữ một Snackbar riêng LENGTH_INDEFINITE trong lúc kiểm parent, rồi dismiss; không dùng thời gian hết hạn làm điều kiện test và không nới các assert UI sẵn có. Helper trả lại Snackbar để test kiểm chính View của nó. `assembleDebug` và `assembleDebugAndroidTest` **BUILD SUCCESSFUL, exit 0** sau sửa này; log `.task-cache/phase4-15-16/snackbar-parent-final-build.log`. Số ca vẫn 31 JVM mới và 12 UI, selector **157/51** không đổi.

[Inference] Bước chẩn đoán tiếp theo khi còn màn đen là Cold Boot một lần cho chính AVD Pixel 10 Pro, rồi quan sát lại và chạy script; chưa có bằng chứng Cold Boot sẽ giải quyết lỗi focus. Theo [Android Developers — Snapshots](https://developer.android.com/studio/run/emulator-snapshots), lệnh Cold Boot nằm trong menu AVD ở Tools → Device Manager. Không chọn Wipe Data, không tạo AVD mới hoặc thay cấu hình toàn máy. Lệnh PowerShell -ContactsUi ở trên giữ nguyên; nếu serial đổi sau khởi động, chọn serial thật từ adb devices trước. Chưa lấy 157 JVM đạt trước sửa UI để coi toàn bộ đợt đã đạt; vẫn cần XML mới đủ 51 Android cho bản cuối trước commit.

Ca offline chạy riêng trên APK cuối đã đi qua assert Snackbar có parent là content Activity và không có FragmentContainerView trong chuỗi parent; sau đó **vẫn thất bại ở assert Espresso dòng 151 vì RootViewWithoutFocusException**. Không còn crash FragmentContainerView trong lần chẩn đoán này, nhưng **testcase không đạt** và không tính là nghiệm thu UI. Log `.task-cache/phase4-15-16/snackbar-parent-final-probe.log`; không sinh XML Gradle mới từ lệnh am instrument. Không tiếp tục chạy cả bộ trên cùng trạng thái màn đen.

Manifest bàn giao bản sửa `.task-cache/phase4-15-16/before-windows-snackbar-fix-manifest.json` giữ hash **25 tệp scope** (thêm ID host Debug), tám bản nháp ngoài phạm vi và artifact lỗi trước lượt chạy tiếp. HEAD vẫn 4214706, index trống và ba stash giữ nguyên. **15/16 chưa nghiệm thu, chưa có commit mới, không push**; dừng để người dùng Cold Boot/kiểm lại môi trường và gửi kết quả script.

### Tiếp quản sau gián đoạn và chẩn đoán có kiểm soát, 19/11/2026

Trước Cold Boot, XML toàn bộ gần nhất sửa lúc **04:03:44 UTC (11:03:44 UTC+7)** ghi đủ 51 ca Android: **39 đạt, 12 UI lỗi RootViewWithoutFocusException**, không có lỗi crash FragmentContainerView. Chín XML JVM ghi **157/157 đạt**; tên/số method của 14 suite khớp source ở thời điểm đối chiếu. Artifact này là bằng chứng của lượt trước, **không phải nghiệm thu mã hiện tại sau khi thêm chẩn đoán**. Đã sao lưu và so SHA-256 56 artifact Android + 11 JVM ở `.task-cache/phase4-15-16/failed-focus-24a6bde4ec6e4408bddae0cbbf13f15b`.

Hai probe tạm từ lượt trước nằm trong ContactsWindowProbe/ContactsWindowProbeTest và manifest Debug: Activity nền tảng/AppCompat chỉ có TextView đều không có window focus; probe nền tảng với theme Android chuẩn cũng thất bại lúc **04:44:08 UTC**. Log `window-probe/{result.log,native-theme-result.log}`. Các probe không có NavHost/Repository; kết quả chỉ xác nhận triệu chứng xảy ra cả ngoài màn danh bạ, chưa xác định nguyên nhân gốc. Probe chưa thuộc selector bộ 157/51; chưa dùng chúng để bỏ/nới assertion của 12 ca UI. Lượt chỉ đọc sau gián đoạn không sửa source hoặc chạy test mới.

Người dùng xác nhận lần chạy thất bại trước đó **chưa khởi động lại emulator**, rồi thực hiện Cold Boot đúng AVD theo hướng dẫn Tools → Device Manager → menu AVD Pixel_10_Pro → Stop nếu đang chạy → Cold Boot. Không Wipe Data, xóa dữ liệu hoặc tự tạo AVD mới. Sau xác nhận đã tới màn hình chính, kiểm ADB thực tế thấy **hai thiết bị**:

| Serial sau Cold Boot | AVD thực tế | Trạng thái kiểm được |
|---|---|---|
| emulator-5554 | Medium_Phone_API_37.0 | device; đây không phải Pixel, không chọn cho ca đại diện |
| **emulator-5556** | **Pixel_10_Pro** | device; sys.boot_completed=1; Awake; isKeyguardShowing=false; không có instrumentation đang chạy lúc kiểm |

Nguồn serial/AVD là output adb devices và adb -s SERIAL emu avd name sau Cold Boot, không lấy lại serial 5554 của lượt cũ. Không tự mở/dừng/thay cấu hình hai AVD này trong agent.

Ca đại diện giữ nguyên **com.utechat.ui.contacts.ContactsListUiTest#listsFriendsAndFiltersNameWithoutChangingCache**; runner từ app/build.gradle.kts là **androidx.test.runner.AndroidJUnitRunner**. Thêm tham số **-UiCheck Focus** vào script hiện có để chọn đúng một method, bỏ JVM và các suite khác. **-UiCheck Suite** chỉ chọn 12 ca trong lớp UI và chỉ dùng sau ca đại diện đạt; **-ContactsUi** vẫn là 157/51 và chưa chạy khi UI còn lỗi. Không ghép -UiCheck với -Repository/-ContactsUi.

Script dùng --rerun-tasks :app:connectedDebugAndroidTest để Gradle build/cài app và APK test từ source hiện tại, chọn ANDROID_SERIAL cùng android.injected.device.serial. Trước chạy sao lưu XML/log/additional output cũ vào ZIP đã kiểm hash; ghi source-before.json/source-after.json, apks.json và gradle-ui.log trong cache UUID. Phát hiện source đổi khi chạy hoặc APK cũ hơn thời điểm bắt đầu thì không nhận kết quả; Focus còn kiểm XML chỉ có đúng method đã chọn. Không thay gate fail/error/skipped hoặc dùng APK đã cài từ probe cũ.

Ca đại diện chỉ được bọc try/catch để thu bằng chứng trước @After đóng Activity rồi **ném lại lỗi gốc**, mọi assertion giữ nguyên. ContactsUiDiagnostics dùng PlatformTestStorage có sẵn trong AndroidX Test, không thêm dependency: ghi stack trace, PNG tại lúc lỗi, trạng thái Activity/decor, trạng thái focus cửa sổ, top Activity/power và logcat lọc theo PID/tag. Không dump Intent, .env, token hoặc nội dung nhập của app khác. Gradle lấy các file về `app/build/outputs/connected_android_test_additional_output/.../contacts-ui-focus`. Thu thập lỗi cũng không được đổi testcase thành đạt.

PowerShell **5.1.26100.9818** parser 0 lỗi; self-check selector/source đạt cả năm chế độ: 83/27, 126/39, 157/51, Focus **0/1**, Suite **0/12**; tám ca XML gate đạt. Log `.task-cache/phase4-15-16/ui-focus-script-check.log`. Đây là kiểm script/selector, chưa phải kết quả ca UI sau Cold Boot. Lệnh chuẩn bị cho đúng Pixel sau Cold Boot:

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '.\scripts\test-phase4-contacts-foundation.ps1' -UiCheck Focus -DeviceSerial 'emulator-5556'
$LASTEXITCODE
```

Chưa nghiệm thu/commit 15/16. Chờ XML mới đúng một ca, hash source/APK và bằng chứng sau Cold Boot; nếu đạt mới chạy Suite UI, rồi mới bàn giao lệnh đầy đủ -ContactsUi với serial thực tế. Không chuyển sang mục 17–20, không push.

**Kết quả Focus sau Cold Boot:** Gradle BUILD SUCCESSFUL in 4m 50s, 71 task executed, script exit **0**. XML chỉ có đúng `ContactsListUiTest.listsFriendsAndFiltersNameWithoutChangingCache`, tests=1, failures/errors/skipped=0, thuộc device **emulator-5556**. Cache `.task-cache/phase4-15-16/windows-18f31a3b18414aa8ae28e21c506460f4` giữ source-before/source-after của **231 tệp** khớp hash và apks.json; APK app/test được tạo mới lúc **06:25:52/06:25:51 UTC**, sau thời điểm bắt đầu. Log tổng tại `.task-cache/phase4-15-16/after-cold-boot-focus.log`. Không có failure nên helper không tạo bằng chứng lỗi mới; đây là 1/1 UI đạt, không phải 12/12 hoặc 157/51. Warning Unsafe vẫn xuất hiện trong lượt đạt, không dùng warning làm nguyên nhân lỗi.

Sau khi đối chiếu Focus, chuyển đúng hai source probe tạm và manifest có probe vào `.task-cache/phase4-15-16/window-probe/archived-source-282c5e1fb31c4674bc346f4c7974979c`; không xóa chúng hoặc log. Khôi phục manifest Debug từ bản trước probe đã so đúng SHA-256 với checkpoint, vẫn giữ ContactsUiTestActivity non-exported. Tám nháp ngoài phạm vi giữ nguyên hash. Thay đổi này chỉ loại công cụ chẩn đoán tạm khỏi APK bàn giao; không đổi host danh bạ, production code, assertion hoặc dependency để làm test đạt. Bộ Suite tiếp theo build lại bản không còn probe và gồm cả ca đại diện để kiểm lại source cuối.

Chỉ sau Focus đạt mới bắt đầu `-UiCheck Suite -DeviceSerial emulator-5556`, chọn đúng 12 ca UI, không chạy JVM/Room nền hoặc 157/51. Chưa kết luận nguyên nhân gốc focus; bằng chứng hiện tại là trước Cold Boot lỗi focus, sau Cold Boot ca đại diện đã đạt với assertion giữ nguyên.

**Suite đầu sau Cold Boot: 9/12 đạt, 3 ca lỗi**, BUILD FAILED in 3m 40s, exit 1; cache `windows-b4788e9fddcf491591c8fc89e135aad9`, log `.task-cache/phase4-15-16/after-cold-boot-ui-suite.log`. Bằng chứng và cách xử lý từng lỗi:

| Ca lỗi | Bằng chứng | Thay đổi trong phạm vi test 16 |
|---|---|---|
| cancelUnfriendNeverCallsRepository | Root cũ có token=null, INVISIBLE; log LifecycleMonitor ghi host DESTROYED rồi MainActivity mở bằng intent LAUNCHER. Người dùng xác nhận đã thao tác trên emulator trong lúc test chạy. | Không đổi assertion hoặc code Cancel; yêu cầu không chạm/Home/Back/mở app trên Pixel khi bộ tự động chạy. |
| vietnameseNightResourcesAndAccessibleActionsUseCurrentTheme | Assert yêu cầu vi nhưng nhận en. Source AppCompat **1.6.1** trong cache: API 33+ set/getApplicationLocales tìm LocaleManager qua Activity delegate sống, trả null nếu chưa có Activity; test cũ gọi trước launch. | Test dùng LocaleManager từ target Context trên API 33+, AppCompat trên API cũ; vẫn kiểm ngôn ngữ vi và UI_MODE_NIGHT_YES. Cleanup không truyền MODE_NIGHT_UNSPECIFIED vì setDefaultNightMode không chấp nhận giá trị này; dùng FOLLOW_SYSTEM đúng hành vi mặc định được tài liệu class mô tả. Không sửa code ngôn ngữ production hoặc nâng thư viện. |
| offlineRetainsRowsAndRetryOnlyRefreshes | Assert parent Snackbar null sau waitForIdleSync; queue/animation chưa bảo đảm onShown, khác lỗi gắn vào FragmentContainerView trước đây. | Đăng ký callback onShown, chờ CountDownLatch có giới hạn 5 giây rồi giữ nguyên toàn bộ assert parent. Đây là chờ điều kiện hiển thị thật, không thêm sleep hoặc tắt focus. |

Source thư viện đã đọc từ appcompat-1.6.1-sources.jar: AppCompatDelegate.setApplicationLocales, getLocaleManagerForApplication và setDefaultNightMode; không áp kết luận này cho mọi phiên bản AppCompat. Các thay đổi xác thực fixture/test lifecycle, không giảm số ca, không bỏ assert tiếng Việt/DayNight hoặc parent Snackbar. Lượt Suite đầu và ảnh lỗi được ZIP giữ nguyên trước lượt kiểm tiếp.

**Suite sau sửa: 12/12 đạt**, Gradle BUILD SUCCESSFUL in **2m 10s**, 71 task executed, script exit **0**, failures/errors/skipped=0. XML mới ghi device=**emulator-5556** và tên đủ 12 method khớp source cuối, gồm cả ca đại diện, Cancel, locale/DayNight và offline/Snackbar. Cache `.task-cache/phase4-15-16/windows-0fc6e9324681407d802e709cab560ad3` giữ source-before/source-after và apks.json; source không đổi trong lượt hoặc sau khi chạy đến lúc đối chiếu. Log `.task-cache/phase4-15-16/ui-suite-after-sync-fix.log`. Không cộng 1 ca Focus hoặc 9 ca Suite trước vào tổng 12 này. Cảnh báo Unsafe vẫn có trong lượt đạt.

Nguồn trực tiếp: [XML UI 12/12](<../../app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10_Pro(AVD) - 17.xml>), [source test cuối](../../app/src/androidTest/java/com/utechat/ui/contacts/ContactsListUiTest.java), [script có Focus/Suite](../../scripts/test-phase4-contacts-foundation.ps1). Ảnh lấy lúc lỗi đã được lưu ở lượt Suite đầu; lần thử đọc ảnh live sau Suite cuối gặp launcher vì Activity đã đóng, không dùng ảnh đó để khẳng định giao diện còn đen hoặc đã hết đen. Không tuyên bố đã tìm ra nguyên nhân gốc của toàn bộ trạng thái màn đen trước Cold Boot.

### Bàn giao chạy đầy đủ sau Focus và Suite đạt

Đến đây mới đưa lệnh đầy đủ **157 JVM + 51 Android** cho bản cuối. Giữ Pixel_10_Pro sẵn sàng; serial đã kiểm sau Cold Boot là **emulator-5556**, không dùng lại 5554 đang thuộc AVD khác. Không thao tác chạm/Home/Back hoặc mở app trong khi bộ tự động chạy; không chạy thêm Gradle/test song song.

```powershell
Set-Location 'D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '.\scripts\test-phase4-contacts-foundation.ps1' -ContactsUi -DeviceSerial 'emulator-5556'
$LASTEXITCODE
```

**Bộ đầy đủ chưa chạy trên source cuối.** 157 JVM và 39 Android nền của lượt cũ giữ đúng lịch sử, không dùng để lấp phần còn thiếu ở lượt mới. 15/16 vẫn `[ ]`; chỉ nghiệm thu/commit sau khi người dùng chạy đủ bộ, exit 0 và đối chiếu XML/source cuối. Manifest bàn giao `.task-cache/phase4-15-16/before-windows-full-after-cold-boot.json` ghi source scope, các nháp bảo toàn, ba stash, HEAD và artifact UI hiện có. Chưa stage/commit/push; không triển khai mục mới.

## Đợt 27 - Nghiệm thu và commit ViewModel/danh sách bạn: mục 15–16, 19/11/2026

### Đối chiếu kết quả đầy đủ với source cuối

Người dùng gửi BUILD SUCCESSFUL in 1m 8s, 71 task executed, PASS 157 JVM + 51 Android instrumentation và **LASTEXITCODE=0**. Đã đối chiếu artifact tại repo, không chạy lại Gradle/ADB/test trong lượt nghiệm thu:

- **9 XML JVM**, thời gian sửa **13:45:17 UTC+7, 19/11/2026**, tổng **157 testcase**. Tên/số method của từng lớp khớp source, không trùng ca, không node failure/error/skipped và mọi summary đều 0 lỗi/bỏ qua.
- **1 XML Android chứa 5 suite**, thời gian sửa **13:46:25 UTC+7**, tổng **51 testcase**; số/tên method khớp source, không trùng hoặc bỏ qua. Tất cả năm suite ghi **device=emulator-5556**, đúng Pixel_10_Pro sau Cold Boot. File runner test-result-exit-code.txt ghi **0**.
- Cả **26 tệp scope** khớp SHA-256 manifest before-windows-full-after-cold-boot.json trước khi cập nhật báo cáo. Source/test/script đã được Windows test không bị sửa để dùng lại XML cũ; trong lượt này chỉ cập nhật báo cáo tiến độ và checklist.
- Backup trước lượt full tại `.task-cache/phase4-15-16/windows-ca8e123cbe7741329831506cc81ca846`, thư mục tạo lúc **13:44:53 UTC+7**. Đã đọc từng entry ZIP và đối chiếu hash với manifest bàn giao: **28/28 artifact trước test khớp**; giữ XML UI 12/12 và JVM cũ đúng lịch sử. Cache lỗi, ảnh và các probe được lưu ở đợt 26 vẫn giữ nguyên, không bị thay bằng kết quả mới.

| Bộ | Các suite và số ca | Kết quả lượt full mới |
|---|---|---|
| JVM ViewModel 15 mới | ContactsViewModelTest 31 | 31/31 đạt |
| JVM Auth/Fake hồi quy | RealAuthRepositoryTest 11; FakeRepositoriesTest 6; FakeContactsSessionTest 8 | 25/25 đạt |
| JVM client/HTTP hồi quy | ContactDtosTest 10; ContactApiTest 16; ContactErrorMapperTest 22; ContactTransportTest 10 | 58/58 đạt |
| JVM Repository hồi quy | RealContactsRepositoryTest 43 | 43/43 đạt |
| **Tổng JVM** | **9 suite, 157 testcase** | **Failures/errors/skipped=0** |
| Android UI 16 mới | ContactsListUiTest 12 | 12/12 đạt |
| Android Repository/Room/TCP | ContactsRepositoryRoomTest 12 | 12/12 đạt |
| Android nền phiên/Room | SessionManagerTest 14; RoomFoundationTest 3; ContactsRoomTest 10 | 27/27 đạt |
| **Tổng Android** | **5 suite, 51 testcase** | **Failures/errors/skipped=0** |

Nguồn trực tiếp: [XML ViewModel JVM](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.ui.contacts.ContactsViewModelTest.xml), [XML transport JVM](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.remote.api.ContactTransportTest.xml), [XML Android đủ 51 ca](<../../app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10_Pro(AVD) - 17.xml>), [mã runner](../../app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt), [31 ca ViewModel](../../app/src/test/java/com/utechat/ui/contacts/ContactsViewModelTest.java), [12 ca UI](../../app/src/androidTest/java/com/utechat/ui/contacts/ContactsListUiTest.java). Không cộng Focus 1 ca, Suite 12 ca trước đó, JUnit trực tiếp 147 ca hoặc test backend vào tổng lượt này.

### Nghiệm thu và giới hạn

**15 và 16 đạt checklist theo phạm vi được giao; chuyển `[x]`.** ViewModel kiểm được tải/observer, năm trạng thái và hành động, SavedStateHandle phone/filter/tab, sự kiện một lần, 409/429 và deadline, bỏ callback/luồng cache của phiên cũ, phân biệt ACK với kết quả ghi chưa rõ và không retry mutation mù. UI kiểm hiển thị/lọc/empty, kéo refresh/disable, badge pending nhận đến, xác nhận Cancel/unfriend/block, recreate, nav ID/back stack, lỗi mạng/cache/retry, logout, tiếng Việt/chế độ tối và nhãn hành động. Các ca Room/Keystore nền được chạy mới trên thiết bị, không thay DAO bằng Fake.

Ba màn con chỉ nghiệm thu nối ID/action, nhãn và Back qua ContactsDestinationFragment; nghiệp vụ tìm người/lời mời/danh sách chặn vẫn thuộc **17–19**, chưa triển khai trong commit này. 12 ca UI dùng fixture để kiểm View/XML/Nav/Dialog thật, không phải toàn luồng với backend/PostgreSQL hoặc hosted HTTPS. Bằng chứng backend 09–10 giữ riêng; nghiệm thu tích hợp ba tài khoản và Lint/toàn hệ thống còn thuộc 20. Các nhánh tương thích API cũ của fixture locale chưa được chạy trên thiết bị khác API 37 trong đợt này.

Lỗi Snackbar gắn nhầm parent và hai lỗi fixture locale/đồng bộ Snackbar đã sửa, các assert và đủ 12 ca vẫn giữ nguyên. Người dùng xác nhận đã thao tác khi ca Cancel bị mất Activity; ca giữ nguyên đã đạt ở Suite sau và lượt full mới. Trước/sau Cold Boot là bằng chứng thời điểm khác nhau, **không khẳng định đã chứng minh nguyên nhân gốc màn đen ban đầu**. Warning Unsafe vẫn xuất hiện trong lượt đạt; không nâng thư viện để xóa warning.

Bấm thử bằng tài khoản thử: mở **Danh bạ**, lọc một phần tên rồi xóa, kéo tải lại; mở Thao tác → hủy kết bạn/chặn, thử đóng xác nhận rồi xác nhận, kiểm hàng tương ứng cập nhật. Xoay màn hình khi có bộ lọc hoặc dialog, đổi sáng/tối và Anh/Việt, thử offline rồi tải lại để kiểm cache/thông báo. Ba nút màn con hiện chỉ tới trang chưa khả dụng và Back. Hướng dẫn bấm thử không được tính thêm thành test tự động hoặc xác nhận toàn luồng backend.

### Phạm vi commit và bảo toàn

Chỉ commit một đợt hai mục **15/16**, sau test và đối chiếu; **26 tệp** gồm nguồn, resource/config cần cho hai mục, test/host Debug, script và báo cáo. Author và Committer yêu cầu **MeanFen <nghiapp11102006@gmail.com>**, kiểm trực tiếp sau khi tạo commit; không sửa danh tính toàn máy hoặc lịch sử. Danh sách tệp ngay dưới là danh sách stage được kiểm, không git add toàn bộ.

Snapshot `.task-cache/phase4-15-16/acceptance-commit-manifest.json` giữ hash source, tám nháp ngoài phạm vi, artifact mới và ZIP trước khi stage. Tám nháp `.gitignore`, ContactsScreenFragment, SearchUserFragment, FriendRequestsFragment, BlockedUsersFragment, fragment_search_user.xml, backend ContactsApiTest và tash list không đưa vào commit; ba stash giữ nguyên. Không đưa .env, XML/log/APK/ZIP/cache hoặc source probe tạm vào commit.

**01–16 đã nghiệm thu theo phạm vi từng mục; 17–20 giữ `[ ]`.** Không chạy lại test khi source khớp manifest và XML đủ mới. Dừng sau commit này; không triển khai mục thứ ba và không tự push. Console git push Everything up-to-date do người dùng cung cấp thuộc thời điểm trước commit mới; không coi đó là bằng chứng commit mới đã được đẩy lên remote.

Danh sách 26 tệp thuộc commit 15/16:

- `app/build.gradle.kts`
- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/java/com/utechat/ui/contacts/ContactsUiTestActivity.java`
- `app/src/main/java/com/utechat/data/repository/ContactsRepository.java`
- `app/src/main/java/com/utechat/data/repository/fake/FakeContactsRepository.java`
- `app/src/main/java/com/utechat/data/repository/real/RealContactsRepository.java`
- `app/src/main/java/com/utechat/data/repository/RelationshipState.java`
- `app/src/main/java/com/utechat/ui/contacts/ContactsListFragment.java`
- `app/src/main/java/com/utechat/ui/contacts/ContactsAdapter.java`
- `app/src/main/java/com/utechat/ui/contacts/ContactsConfirmDialog.java`
- `app/src/main/java/com/utechat/ui/contacts/ContactsDestinationFragment.java`
- `app/src/main/java/com/utechat/ui/contacts/ContactsUi.java`
- `app/src/main/java/com/utechat/ui/contacts/ContactsViewModel.java`
- `app/src/main/res/layout/fragment_contacts_list.xml`
- `app/src/main/res/layout/fragment_contacts_destination.xml`
- `app/src/main/res/layout/item_contact.xml`
- `app/src/main/res/navigation/nav_contacts.xml`
- `app/src/main/res/values/strings_contacts.xml`
- `app/src/main/res/values-vi/strings_contacts.xml`
- `gradle/libs.versions.toml`
- `app/src/test/java/com/utechat/ui/contacts/ContactsViewModelTest.java`
- `app/src/androidTest/java/com/utechat/ui/contacts/ContactsListUiTest.java`
- `scripts/test-phase4-contacts-foundation.ps1`
- `docs/phase4/01-phone-validation.md`
- `app/src/debug/res/values/ids.xml`
- `app/src/androidTest/java/com/utechat/ui/contacts/ContactsUiDiagnostics.java`

## Đợt 28 — Sửa riêng Android Lint RestrictedApi (2026-11-19)

### Phạm vi và bằng chứng trước sửa

Theo yêu cầu sửa Lint rồi commit và dừng: chỉ sửa đường chuyển tác vụ lên main trong Auth, Contacts và Session đã có; không triển khai mục 17–20, không sửa/chạy backend hoặc xử lý báo cáo 204 Errors. HEAD trước đợt là `183785ec5872125ce89db356cea9679ed56b4876`, nhánh `phase4_NP`.

Lượt `:app:lintDebug` trước sửa tạo báo cáo mới ngày 19/11 lúc 15:19 +07:00: **18 errors, tất cả RestrictedApi, và 55 warnings**. Sáu vị trí gọi ArchTaskExecutor thuộc RealAuthRepository (2), RealContactsRepository (3), SessionManager (1); mỗi biểu thức bị kiểm quyền truy cập lớp/phương thức nội bộ AndroidX. Số 61 warnings trong ảnh không được tái hiện ở lượt này. Bản sao HTML/XML/SARIF/text trước sửa nằm tại `.task-cache/lint-restricted-api/baseline/`.

Lần offline đầu dừng do thiếu dependency `lifecycle-viewmodel-desktop` và `lifecycle-runtime-desktop` 2.8.7, chưa phân tích Lint. Cho Gradle tải dependency theo cấu hình hiện có rồi chạy với `--no-configuration-cache` đã thu được lỗi trên. Không sửa dependency, build config, thêm baseline, suppress hoặc tắt luật để làm Lint đạt.

### Bản sửa và kiểm thử mới

- MainThreadExecutor dùng Handler/Looper công khai: chạy ngay nếu đang ở main, còn từ nền thì post vào main. Không thay hàng đợi NETWORK/DISK hoặc vị trí kiểm phiên/revision trong callback.
- Thay đủ sáu lời gọi RestrictedApi trong nguồn production. Giữ constructor hiện có của hai Repository; thêm tham số Executor cho fixture JVM điều khiển main mà không cần Looper giả. Các delegate ArchTaskExecutor còn lại chỉ ở fixture test, không ở `app/src/main`.
- Thêm một ca Auth giữ callback đọc cache ở main rồi đăng nhập lại, xác nhận hồ sơ cũ không được phát/ghi. Giữ nguyên các ca Contacts kiểm phiên/revision khi main bị trễ.
- Thêm hai ca thiết bị kiểm main chạy inline và nền chạy trên main theo FIFO; thêm một ca Session giữ main bằng latch, đổi hai phiên rồi xác nhận chỉ phiên mới nhất được phát. Không bỏ ca, nới assertion hoặc thêm sleep để đạt.
- Cập nhật đúng số ca trong script hiện có; vẫn kiểm XML mới, đủ class/case, không lỗi/bỏ qua và giữ backup ZIP đối chiếu SHA-256. Script được chạy bằng **Windows PowerShell 5.1**, ExecutionPolicy Bypass riêng tiến trình.

| Kiểm tra mới trên nguồn cuối | Kết quả |
|---|---|
| `gradlew.bat --offline --no-configuration-cache --no-daemon --console=plain :app:lintDebug` | **BUILD SUCCESSFUL; 0 errors, 55 warnings** |
| Script `-Repository -DeviceSerial emulator-5554`, 8 suite JVM | **127 testcase; failures/errors/skipped=0** |
| Script trên, 5 suite Android instrumentation | **42 testcase; failures/errors/skipped=0**, runner exit code 0 |
| Auth JVM / Contacts JVM | RealAuthRepositoryTest **12** / RealContactsRepositoryTest **43** |
| JVM hồi quy Fake/client/HTTP | FakeRepositoriesTest 6, FakeContactsSessionTest 8, ContactDtosTest 10, ContactApiTest 16, ContactErrorMapperTest 22, ContactTransportTest 10 |
| Android Looper / Session / Room | MainThreadExecutorTest **2**, SessionManagerTest **15**, ContactsRepositoryRoomTest **12**, RoomFoundationTest **3**, ContactsRoomTest **10** |

Thiết bị thực tế của lượt này: `emulator-5554`, **Medium_Phone_API_37.0**, Android 17/API 37, boot completed và Awake trước test; không mặc định đó là Pixel ở các lượt trước. Script build lại APK và test APK với `--rerun-tasks`; không dùng APK hoặc XML cũ để nghiệm thu. JVM XML timestamp bắt đầu `2026-11-19T08:22:37Z`; Android XML ghi timestamp `2026-11-19T08:23:17` (không có hậu tố múi giờ), mtime UTC là `08:23:19Z`. Gradle wrapper 9.6.0; JAVA_HOME launcher là jdk-27, cấu hình daemon hiện có chọn toolchainVersion 25, không thay toolchain project.

Nguồn đối chiếu: [Lint XML sau sửa](../../app/build/reports/lint-results-debug.xml), [Lint HTML](../../app/build/reports/lint-results-debug.html), [Auth JVM XML](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.repository.RealAuthRepositoryTest.xml), [Contacts JVM XML](../../app/build/test-results/testDebugUnitTest/TEST-com.utechat.data.repository.RealContactsRepositoryTest.xml), [Android XML đủ 42 ca](<../../app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml>), [runner exit code](../../app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt). Log đầy đủ ở `.task-cache/lint-restricted-api/regression.log` và `lint-after.log`; hash nguồn trước test và artifact cuối được lưu cùng thư mục. Hash toàn bộ `app/src` cùng script/config được đối chiếu sau test: không đổi. 55 warnings khớp baseline theo id, nội dung và tệp, không chỉ bằng tổng số.

### Checklist, giới hạn và commit

- [x] Tái hiện 18 lỗi RestrictedApi bằng Lint mới, lưu bằng chứng trước/sau; nguồn production không còn ArchTaskExecutor.
- [x] Lint đạt và hồi quy mới 127 JVM + 42 Android đạt, đối chiếu XML/source; Room và Looper chạy thật trên thiết bị.
- [x] Bảo toàn tám bản nháp ngoài phạm vi và ba stash; không stage backend, `.gitignore`, `.env`, log/XML/APK/cache.
- [ ] Chưa sửa/kiểm lại backend 204 Errors; không xác nhận nguyên nhân của toàn bộ 204 từ ảnh báo lỗi.
- [ ] Chưa xử lý 55 cảnh báo Lint, chưa chạy Lint Release, UI suite hay tích hợp toàn hệ thống trong đợt này. Kết quả UI 15/16 ở đợt 27 là bằng chứng của nguồn thời điểm đó; không cộng vào kết quả mới 127/42. Chưa kiểm nhánh API thấp hơn 37 trên thiết bị.

Không có thay đổi UI nên không thêm luồng bấm thử. Đây là đợt sửa lỗi nền, không đánh dấu hoàn thành thêm mục chính; **17–20 giữ trạng thái chưa hoàn thành**. Chỉ tạo một commit sau các kiểm tra trên; Author và Committer yêu cầu **MeanFen <nghiapp11102006@gmail.com>**, kiểm lại trên commit đã tạo. Không push; dừng sau commit.

Danh sách **10 tệp** dành cho commit sửa Lint:

- `app/src/main/java/com/utechat/data/MainThreadExecutor.java`
- `app/src/main/java/com/utechat/data/repository/real/RealAuthRepository.java`
- `app/src/main/java/com/utechat/data/repository/real/RealContactsRepository.java`
- `app/src/main/java/com/utechat/data/session/SessionManager.java`
- `app/src/test/java/com/utechat/data/repository/RealAuthRepositoryTest.java`
- `app/src/test/java/com/utechat/data/repository/RealContactsRepositoryTest.java`
- `app/src/androidTest/java/com/utechat/data/MainThreadExecutorTest.java`
- `app/src/androidTest/java/com/utechat/data/session/SessionManagerTest.java`
- `scripts/test-phase4-contacts-foundation.ps1`
- `docs/phase4/01-phone-validation.md`

## Đợt 29 — Backend test mặc định dùng PostgreSQL nhúng, không cần Docker (2026-11-19)

### Phạm vi và thay đổi

Theo yêu cầu bỏ phụ thuộc Docker khi chạy test: sửa riêng thiết lập test backend, không triển khai mục 17–20 hoặc sửa nghiệp vụ/migration. HEAD trước đợt là `c5a472123bacf916ce0d192475100123aac7d517`, nhánh `phase4_NP`.

- PostgresFixture tự khởi động EmbeddedPostgres khi không truyền `phase4.pg.url`; dùng dependency 2.0.7 đã có, không thêm/nâng thư viện. Một engine dùng chung trong JVM, chỉ lắng nghe `127.0.0.1`, cổng tự chọn; mỗi fixture vẫn tạo database UUID riêng và close chỉ DROP database của mình. Zonky có shutdown hook dọn engine khi JVM kết thúc.
- Không cần script, Docker, `.env` hoặc mật khẩu PostgreSQL để chạy Maven test mặc định. Nếu cấp `phase4.pg.url` tường minh, vẫn giữ đường PostgreSQL test ngoài cùng các guard localhost/cổng/database admin, không credentials/query/fragment trong URL và mật khẩu test riêng; không âm thầm rơi về nhúng khi URL sai.
- Giữ FlywayMigrationTest dùng fixture chung: khi chạy mặc định, cả baseline V1, nâng V3/V4 và lịch sử seed V2 được kiểm trên PostgreSQL nhúng thật. Không chuyển sang H2 hoặc mock database.
- PostgresFixtureGuardTest từ 7 lên **9 ca**: thay ca bắt buộc thiếu URL phải lỗi bằng kiểm khởi động nhúng thật; thêm kiểm cách ly/dọn đúng database và từ chối URL rỗng tường minh. Các guard URL sai trước đó giữ nguyên.
- Script `backend/scripts/test-phase4-foundation.ps1` bỏ Docker preflight/run/inspect/cleanup, `PostgresImage` và thao tác biến mật khẩu; giữ các switch chọn suite, backup báo cáo và kiểm XML mới/không lỗi/không bỏ qua. Bộ `-AndroidContract` nay có **253 test** do thêm 2 ca fixture, không giảm số ca nghiệp vụ.
- Lượt Maven đầy đủ phát hiện thêm lỗi Mockito/Byte Buddy không nhận class version Java 27 ở 4 ca Health. Thêm `net.bytebuddy.experimental=true` vào **Surefire test JVM** trong POM theo thông báo lỗi; không đổi JDK, dependency, HealthControllerTest, assertion hoặc cấu hình runtime production.

### Bằng chứng kiểm thử theo thời điểm

| Lượt | Kết quả thực tế |
|---|---|
| Trước sửa: Maven trực tiếp, một ca Flyway nâng database thủ công | **1 Error**: fixture yêu cầu script cấp PostgreSQL; lưu `baseline.log` và XML trước sửa |
| Sau sửa fixture: PostgresFixtureGuardTest + FlywayMigrationTest | **12 test, 0 failures/errors/skipped, BUILD SUCCESS**; PostgreSQL nhúng khởi động thật |
| Maven đầy đủ trước sửa cấu hình Byte Buddy | **280 test, 0 failures, 4 errors, 0 skipped**; 4 errors đều thuộc HealthControllerTest, nguyên nhân Java 27 chưa được Byte Buddy hiện có hỗ trợ mặc định |
| Kiểm riêng Health sau sửa Surefire | **4 test, 0 failures/errors/skipped, BUILD SUCCESS** |
| **Lượt đầy đủ cuối, PowerShell 5.1 + Maven trực tiếp, nguồn/POM cuối** | **24 suite, 280 testcase, 0 failures/errors/skipped, BUILD SUCCESS**, 04:07, kết thúc `2026-11-19T15:46:30+07:00` |

Lệnh thực tế lượt cuối là `mvn -o -B -ntp -f backend/pom.xml test`, chạy từ repo qua Windows PowerShell 5.1, ExecutionPolicy Bypass riêng tiến trình. Maven 3.9.16, JDK 27; engine nhúng báo **PostgreSQL 14.10** theo binary dependency có sẵn 14.10.1, không phải Docker PostgreSQL 16 ở các lượt nghiệm thu cũ. Không truyền `phase4.pg.url`, không cấp mật khẩu test và không gọi Docker trong lượt cuối. `-o` dùng cache dependency có sẵn; máy mới cần cho Maven tải dependency ở lần đầu.

Đối chiếu độc lập tất cả 24 XML cuối: mtime sau thời điểm bắt đầu lượt cuối, summary bằng số node testcase, không failure/error/skipped hoặc testcase trùng; tổng **280**. Hash toàn bộ `backend/src`, POM và script trước/sau lượt cuối không đổi. 20 suite được chọn bởi `-AndroidContract`, gồm đủ **253 testcase**, đều có mặt và đạt trong lượt Maven đầy đủ; 27 ca còn lại gồm AuthApiTest 16, HealthControllerTest 4, LocalFileOtpDeliveryTest 2 và ContactsApiTest 5. **ContactsApiTest là bản nháp chưa track**, chỉ được Maven tự phát hiện khi chạy đầy đủ, không sửa hoặc đưa vào phạm vi commit. 58 unit client Android lồng trong một ca contract không cộng lần nữa vào tổng 280.

Nguồn trực tiếp: [Fixture XML 9 ca](../../backend/target/surefire-reports/TEST-com.utechat.backend.support.PostgresFixtureGuardTest.xml), [Flyway XML 3 ca](../../backend/target/surefire-reports/TEST-com.utechat.backend.config.FlywayMigrationTest.xml), [Health XML 4 ca cuối](../../backend/target/surefire-reports/TEST-com.utechat.backend.health.HealthControllerTest.xml), [contract XML](../../backend/target/surefire-reports/TEST-com.utechat.backend.AndroidContactContractTest.xml). `.task-cache/backend-embedded/` giữ log baseline, focused, full trước sửa Byte Buddy, Health sau sửa và `final-full.log`; các thư mục backup giữ XML trước/sau riêng. `source-before-final.json` và `final-xml-manifest.json` ghi hash nguồn và 24 XML cuối. Kiểm tiến trình sau Maven: **0 postgres/pg_ctl/initdb.exe còn chạy** trong lần kiểm này.

Script giữ UTF-8 BOM, kiểm Parser bằng Windows PowerShell **5.1.26100.9818: 0 lỗi**; không còn lệnh gọi Docker. Chưa chạy lại toàn bộ script sau lượt Maven đầy đủ để tránh lặp 253 ca: các selector/số ca của script đã được đối chiếu với XML mới của cùng nguồn, không gọi đó là bằng chứng script end-to-end mới.

### Cách chạy và giới hạn

Từ thư mục repo, chạy backend đầy đủ mà không cần Docker hoặc script:

```powershell
mvn -B -ntp -f .\backend\pom.xml test
```

Hoặc dùng cổng nghiệm thu 01–10 hiện có để chọn 253 ca và kiểm XML, tương thích PowerShell 5.1:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\backend\scripts\test-phase4-foundation.ps1" -AndroidContract
```

Đã kiểm trên **Windows x64/JDK 27**; chưa chạy Linux/macOS/ARM hoặc JDK khác trong đợt này, không tuyên bố “mọi máy đều chạy” và không quy toàn bộ 204 Errors cũ về một nguyên nhân từ ảnh. Máy cần JDK/Maven và binary/runtime PostgreSQL tương thích; tham khảo [yêu cầu nền tảng và troubleshooting Zonky](https://github.com/zonkyio/embedded-postgres). Chưa kiểm lại đường PostgreSQL cấp tường minh; chưa thử hosted/Supabase, Android UI hoặc kiểm tổng mục 20.

**01–16 giữ trạng thái nghiệm thu theo phạm vi cũ; 17–20 chưa hoàn thành.** Không thay đổi UI nên không có luồng bấm thử mới. Tám bản nháp ngoài phạm vi và ba stash giữ nguyên hash/ID. Theo yêu cầu commit tiếp theo của người dùng, chỉ stage/commit năm tệp bên dưới sau khi đối chiếu lại diff, hash nguồn và 24 XML/280 ca đạt; không chạy trùng test khi nguồn đã khớp. Author và Committer yêu cầu **MeanFen <nghiapp11102006@gmail.com>**, kiểm trực tiếp trên commit đã tạo. Không push; không đưa `.env`, bản nháp, XML/log/cache vào Git. Trong 280 ca đã kiểm có 5 ca ContactsApiTest nháp không đưa vào commit; 275 ca còn lại thuộc các suite đã track.

Năm tệp thuộc phạm vi sửa: `backend/pom.xml`, `backend/scripts/test-phase4-foundation.ps1`, `backend/src/test/java/com/utechat/backend/support/PostgresFixture.java`, `backend/src/test/java/com/utechat/backend/support/PostgresFixtureGuardTest.java` và báo cáo này.
