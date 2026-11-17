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

## Checklist các hạng mục còn lại của Phase 4

Hai thành phần đã kiểm thử riêng là chuẩn hóa SĐT (28 test) và giới hạn tra cứu (8 test). Chúng chưa tạo thành luồng tìm người hoàn chỉnh. Các mục dưới đây đều chưa được nghiệm thu, dù một số đã có code nháp trong `TeamProject`.

Có 20 hạng mục để theo dõi, không mặc định tương ứng đúng 20 lượt thực hiện. Mục lớn có thể tách thành nhiều đợt nhỏ. Trước khi viết, đọc lại bản nháp liên quan để sửa và tái sử dụng phần phù hợp. Chỉ đánh dấu `[x]` khi đã có bằng chứng đạt các điều kiện của mục đó.

### Backend

- [ ] **01. Migration `is_friend` và database chạy thử:** kiểm tra tạo database mới và nâng cấp database cũ; phân biệt bạn bè với chặn; xử lý rõ dữ liệu chặn cũ không đủ thông tin; cập nhật bộ nạp schema chạy thử cùng migration mới. Không sửa migration đã áp dụng.
- [ ] **02. DTO, lỗi API, chính sách quan hệ, khóa cặp và sự kiện:** chốt JSON/mã lỗi; tái sử dụng JWT Phase 3; thống nhất khóa cặp trước khi khóa dòng; bổ sung `SyncEventWriter` và hợp đồng `CONTACT_REQUEST_CREATED`, `CONTACT_REQUEST_UPDATED`, `CONTACT_UPDATED`; cập nhật tài liệu hợp đồng tương ứng.
  - [x] **02a. Lỗi thiếu query bắt buộc:** thiếu `phone` trả `400 PHONE_INVALID`; thiếu tham số khác trả `400 VALIDATION`; 4 test Spring MVC đạt. Các phần còn lại của mục 02 vẫn chưa nghiệm thu.
- [ ] **03. API tìm người bằng SĐT:** server kiểm E.164 và danh tính từ JWT, nối `LookupRateLimiter`; xác minh `400 PHONE_INVALID`, `400 SELF`, `404 NOT_FOUND` cho các trường hợp cần che hồ sơ và `429 RATE_LIMITED` kèm thời gian chờ; trả đúng trạng thái quan hệ và ID/chiều lời mời. Chuẩn hóa ô nhập phía Android được nối tại mục 17.
- [ ] **04. Gửi lời mời kết bạn:** cấm tự gửi, gửi khi có block, gửi khi đã là bạn; xử lý trùng cùng chiều và ngược chiều bằng `409 REQUEST_ALREADY_PENDING`; hai yêu cầu đồng thời chỉ tạo một lời mời chờ; ghi sự kiện cho đúng hai bên trong cùng transaction.
- [ ] **05. Xem lời mời đã nhận và đã gửi:** chỉ trả lời mời liên quan tài khoản hiện tại, lọc chiều/trạng thái đúng; không tiết lộ SĐT trong hồ sơ tối thiểu của lời mời; không cho người thứ ba đọc dữ liệu trái quyền.
- [ ] **06. Chấp nhận lời mời:** chỉ người nhận được thực hiện, kiểm tra block; cập nhật lời mời, tạo hai dòng bạn bè và sự kiện nguyên tử; chấp nhận lặp không tạo trùng; chấp nhận lại lời mời cũ sau unfriend không tạo lại bạn bè; kiểm thử accept đồng thời với block.
- [ ] **07. Từ chối và hủy lời mời:** chỉ người nhận được reject, chỉ người gửi được cancel; kiểm chuyển trạng thái hợp lệ, thao tác lặp và ghi sự kiện; accept sau reject/cancel phải bị từ chối nhất quán.
- [ ] **08. Danh sách bạn bè và hủy kết bạn:** trả đúng bạn bè của tài khoản; unfriend cập nhật cả hai chiều, bảo toàn dòng chặn và hạ `is_friend` trên dòng chặn còn lại; ghi sự kiện cho trạng thái thực sự thay đổi, không tạo trùng khi thao tác lặp.
- [ ] **09. Chặn, danh sách chặn và bỏ chặn:** lưu quyết định chặn một chiều nhưng cản tương tác hai chiều; hủy lời mời chờ khi chặn; kiểm chặn người lạ, chặn bạn, hai bạn cùng chặn rồi bỏ chặn và unfriend trong lúc chặn; bỏ chặn không khôi phục quan hệ đã hủy.

### Dữ liệu Android

**Phụ thuộc bắt buộc:** thiết kế và kiểm chứng cơ chế phiên, executor và phản hồi cũ của mục 14 trước khi ghi cache trong mục 12–13. Fake Repository và Injection được mở rộng cùng các phần sử dụng chúng.

- [ ] **10. Retrofit, DTO Android và ánh xạ lỗi:** khai báo đủ 11 endpoint, dùng client/Bearer hiện có; ánh xạ đúng JSON backend, `isContact`, trạng thái quan hệ, ID/chiều lời mời, `retryAfterSeconds`; có kiểm thử tương thích hợp đồng và lỗi mạng.
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

### Quy tắc áp dụng ở mỗi đợt nhỏ

- Kiểm thử ngay từng phần: PostgreSQL thật/nhúng cho nghiệp vụ backend, Room thật cho DAO, unit test cho Repository và ViewModel. Mục 20 là nghiệm thu tổng thể, không phải lúc bắt đầu viết test.
- Tích hợp một luồng thật ngay khi backend và client đủ thành phần để kiểm tra, không chờ tất cả màn hình hoàn tất.
- Mọi thay đổi quan hệ phải ghi `sync_events` trong cùng transaction; WebSocket và `/api/sync` thuộc Phase 5, gọi thoại thuộc Phase 6.
- Chọn một phần nhỏ → kiểm tra đạt → cập nhật duy nhất file báo cáo này → commit đúng phạm vi → push `phase4_NP` không force → dừng cho người dùng xem. Nếu push thất bại, ghi cách kết nối và nguyên văn lỗi.
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
