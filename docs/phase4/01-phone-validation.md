# Phase 4 — Báo cáo tiến độ: chuẩn hóa SĐT và giới hạn tra cứu

Ngày thực hiện: 17/11/2026. Đây là file báo cáo chung duy nhất, được cập nhật sau từng đợt nhỏ.

Cập nhật mới nhất: 18/11/2026 - đợt 14, triển khai code và bộ test API 03–04; build Maven/JDK 27 và 46 test không cần PostgreSQL đạt, chờ chạy bộ 117 test trên Windows để nghiệm thu 03–04. Mục 01–02 giữ trạng thái đã nghiệm thu.

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

Hai thành phần đã kiểm thử riêng là chuẩn hóa SĐT (28 test) và giới hạn tra cứu (8 test). **Mục 01–02 đã được nghiệm thu tại đợt 13; API backend 03–04 đã được nghiệm thu tại đợt 15 bằng bộ Windows 117 test.** Luồng tìm người trên Android chưa hoàn chỉnh; chuẩn hóa ô nhập và gọi API từ màn hình còn thuộc mục 17. Các mục 05–20 vẫn chưa được nghiệm thu toàn mục, dù một số đã có code nháp trong `TeamProject`.

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
- [ ] **05. Xem lời mời đã nhận và đã gửi:** chỉ trả lời mời liên quan tài khoản hiện tại, lọc chiều/trạng thái đúng; không tiết lộ SĐT trong hồ sơ tối thiểu của lời mời; không cho người thứ ba đọc dữ liệu trái quyền.
- [ ] **06. Chấp nhận lời mời:** chỉ người nhận được thực hiện, kiểm tra block; cập nhật lời mời, tạo hai dòng bạn bè và sự kiện nguyên tử; chấp nhận lặp không tạo trùng; chấp nhận lại lời mời cũ sau unfriend không tạo lại bạn bè; kiểm thử accept đồng thời với block.
- [ ] **07. Từ chối và hủy lời mời:** chỉ người nhận được reject, chỉ người gửi được cancel; kiểm chuyển trạng thái hợp lệ, thao tác lặp và ghi sự kiện; accept sau reject/cancel phải bị từ chối nhất quán.
- [ ] **08. Danh sách bạn bè và hủy kết bạn:** trả đúng bạn bè của tài khoản; unfriend cập nhật cả hai chiều, bảo toàn dòng chặn và hạ `is_friend` trên dòng chặn còn lại; ghi sự kiện cho trạng thái thực sự thay đổi, không tạo trùng khi thao tác lặp.
- [ ] **09. Chặn, danh sách chặn và bỏ chặn:** lưu quyết định chặn một chiều nhưng cản tương tác hai chiều; hủy lời mời chờ khi chặn; kiểm chặn người lạ, chặn bạn, hai bạn cùng chặn rồi bỏ chặn và unfriend trong lúc chặn; bỏ chặn không khôi phục quan hệ đã hủy.

### Dữ liệu Android

**Phụ thuộc bắt buộc:** thiết kế và kiểm chứng cơ chế phiên, executor và phản hồi cũ của mục 14 trước khi ghi cache trong mục 12–13. Fake Repository và Injection được mở rộng cùng các phần sử dụng chúng.

- [ ] **10. Retrofit, DTO Android và ánh xạ lỗi:** khai báo đủ 11 endpoint, dùng client/Bearer hiện có; ánh xạ đúng JSON backend, `isContact`, trạng thái quan hệ, ID/chiều lời mời, `retryAfterSeconds`; có kiểm thử tương thích hợp đồng và lỗi mạng.
  - [x] **10a. DTO Android và JSON Gson:** đọc đúng lookup, lời mời, danh sách contact và details của lỗi 409/429; body gửi chỉ gồm addresseeId; 10 test Gson đạt. Phạm vi DTO; kiểm hợp đồng Retrofit được ghi riêng ở 10b.
  - [x] **10b. Interface Retrofit cho 11 endpoint:** kiểm method/path/query/body, chuyển JSON sang DTO/List, 201/204, giữ lỗi HTTP 409/429 và truyền IOException; 16 test dùng Retrofit/OkHttp/Gson thật với interceptor giả lập đạt. Chưa kiểm backend/JWT; bộ ánh xạ HTTP được ghi riêng ở 10c.
  - [x] **10c. Bộ ánh xạ lỗi HTTP độc lập:** đọc code/details sang ContactsResult, giữ HTTP_<status> khi thiếu code hoặc body sai, không ép metadata sai kiểu, đóng errorBody; 22 test mới đạt. Chưa nối vào Repository, chưa hoàn thành xử lý lỗi mạng/phiên hoặc thông báo UI.
- [ ] **11. Room DAO và cập nhật cache bằng transaction:** đối soát để xóa dữ liệu hết hiệu lực, giữ hồ sơ và quyền riêng tư khi DTO thiếu trường; lưu dòng chặn và hồ sơ tối thiểu nguyên tử; giữ schema v1 theo kế hoạch; kiểm truy vấn và rollback bằng Room in-memory thật.
- [ ] **12. Repository cho tìm kiếm và lời mời:** nối REST với Room, bảo vệ callback theo phiên và độ mới; cập nhật lời mời sau thao tác; accept không tự tạo bạn từ lịch sử `ACCEPTED` mà đối soát danh sách server; giữ các chữ ký cũ còn được sử dụng.
- [ ] **13. Repository cho bạn bè và chặn:** cập nhật cache đúng sau unfriend/block/unblock; phản hồi tải lại cũ không ghi đè thao tác mới; phân biệt thao tác đã lưu nhưng refresh thất bại với kết quả ghi chưa xác định do mất phản hồi; không suy đoán quan hệ bạn bè.
- [ ] **14. Cô lập phiên, offline, Fake Repository và Injection:** tái sử dụng Auth/SessionManager; kiểm cả ID tài khoản và phiên, gồm đăng xuất rồi đăng nhập lại cùng tài khoản; dọn Room và bộ nhớ tạm, loại callback cũ; mất mạng giữ cache và phiên; Fake phản ánh đúng nghiệp vụ, kiểm thử cũ vẫn đạt; dùng chung executor ghi cache với dọn phiên.

### Giao diện và nghiệm thu

- [ ] **15. `ContactsViewModel`:** quản lý tải dữ liệu, 5 trạng thái quan hệ, lỗi/thông báo một lần và trạng thái thao tác; xử lý `409`/`429`; giữ ô nhập, bộ lọc và tab qua xoay màn hình; không áp dụng kết quả của phiên cũ.
- [ ] **16. Màn hình danh sách bạn bè:** hiển thị/lọc tên, kéo làm mới, hủy kết bạn/chặn có xác nhận; badge chỉ đếm lời mời nhận đến còn `PENDING`; nối các màn con qua `nav_contacts.xml`; giữ tiêu đề và ID tương thích điều hướng hiện tại.
- [ ] **17. Màn hình tìm người:** nối `ContactsValidator` vào ô nhập và gọi backend thật; hiện nút hợp lệ cho `STRANGER`, `OUTGOING_PENDING`, `INCOMING_PENDING`, `FRIEND`, `BLOCKED`; xử lý trạng thái tải, lỗi, thử lại và thời gian chờ; ẩn nút Nhắn tin trong Phase 4.
- [ ] **18. Màn hình lời mời:** hai tab Đã nhận/Đã gửi; đủ accept/reject/cancel và chặn theo quyền; danh sách và badge cập nhật đúng sau thao tác; giữ tab khi xoay màn hình.
- [ ] **19. Màn hình danh sách chặn:** hiển thị dữ liệu online/cache offline, bỏ chặn có xác nhận và đối soát đúng; có empty/loading/error state. Với cả mục 16–19: dùng ViewBinding đúng vòng đời, `ListAdapter + DiffUtil`, Material DayNight và chuỗi Anh–Việt.
- [ ] **20. Nghiệm thu toàn luồng:** chạy Android với backend và PostgreSQL thật, tắt Fake; dùng ít nhất ba tài khoản thử A/B/C để kiểm cả người thứ ba; kiểm quyền, đồng thời, request lặp, offline giữa thao tác, đổi tài khoản/đổi phiên, xoay màn hình, sáng tối và Anh–Việt; chạy bộ backend test, Android unit test, Room test và Lint cần thiết; ghi đúng kết quả và giới hạn còn lại.

### Quy tắc áp dụng ở mỗi đợt

- Kiểm thử ngay từng phần: PostgreSQL thật/nhúng cho nghiệp vụ backend, Room thật cho DAO, unit test cho Repository và ViewModel. Mục 20 là nghiệm thu tổng thể, không phải lúc bắt đầu viết test.
- Tích hợp một luồng thật ngay khi backend và client đủ thành phần để kiểm tra, không chờ tất cả màn hình hoàn tất.
- Mọi thay đổi quan hệ phải ghi `sync_events` trong cùng transaction; WebSocket và `/api/sync` thuộc Phase 5, gọi thoại thuộc Phase 6.
- Từ đợt 10: chọn hai mục cấp chính chưa hoàn thành theo phụ thuộc, hoàn thành mục thứ nhất rồi tiếp tục mục thứ hai trong cùng lượt; tối đa hai mục, không triển khai mục thứ ba. Nghiệm thu toàn mục, không bàn giao chỉ vì xong một phần con. Nếu phụ thuộc chưa hoàn thành thì ưu tiên phụ thuộc vào một trong hai vị trí; nếu gặp trở ngại thực sự không giải quyết được thì ghi bằng chứng, không hạ tiêu chuẩn hoặc tính các phần con thành hai mục hoàn thành.
- Cập nhật duy nhất file báo cáo này, chỉ stage thay đổi thuộc hai mục đã chọn và báo cáo; ưu tiên một commit cho mỗi mục hoàn thành, với Author và Committer `MeanFen <nghiapp11102006@gmail.com>`. Không sửa lịch sử, không tự push; người dùng tự kiểm tra và push bằng Terminal. Cuối lượt báo trạng thái thật, kiểm thử, khả năng bấm thử UI, commit và trở ngại rồi dừng. Các quy tắc một phần nhỏ/lượt và các lần push ở phần trước là lịch sử.
- Chỉ làm việc trong `D:\HCMUTE\Y3\S1\T2\LapTrinhDiDong\TeamProject`; bảo toàn các bản nháp chưa commit và bản dự phòng hiện có.

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
