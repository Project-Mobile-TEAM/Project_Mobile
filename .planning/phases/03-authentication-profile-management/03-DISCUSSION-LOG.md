# Phase 3: Authentication & Profile Management - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-11
**Phase:** 3-Authentication & Profile Management
**Areas discussed:** OTP Demo Mechanism, Auth Navigation Flow, Session & Logout Cleanup, Device Management UI, Privacy & Soft Delete, i18n & DayNight

---

## Registration, Login & OTP Demo

### OTP Demo Mechanism
| Option | Description | Selected |
|--------|-------------|----------|
| Trả OTP demo trong response API (khi chế độ demo bật) + hiển thị Snackbar/Dialog trên app | Vừa bảo mật đúng chuẩn V1, vừa cho phép demo mượt mà kể cả khi host cloud mà không cần endpoint /api/demo/sms ở prod | ✓ |
| Chỉ đọc từ bảng demo_sms_outbox bằng script backend riêng | Cần mở terminal riêng để tra cứu mã OTP mỗi khi demo | |
| Mã OTP demo cố định 123456 cho môi trường test | Nhanh gọn nhất khi giảng viên chấm thi nhưng kém tính linh hoạt | |

**User's choice:** Trả OTP demo trong response API (khi chế độ demo bật) + hiển thị Snackbar/Dialog trên app
**Notes:** Giúp người chấm bài hoặc giảng viên có thể test trực tiếp trên điện thoại/emulator mà không cần mở database hoặc backend log để lấy mã OTP.

### Auth Navigation Flow
| Option | Description | Selected |
|--------|-------------|----------|
| Tách màn hình rõ ràng: LoginFragment -> RegisterFragment -> OtpVerifyFragment -> ForgotPasswordFragment | Chuẩn Navigation Component, mỗi màn hình độc lập, dễ viết ViewModel và unit test theo slide môn học | ✓ |
| Gộp chung trên 1 màn hình dùng TabLayout (Đăng nhập / Đăng ký) | Gom chung giao diện nhưng phức tạp hơn khi xử lý trạng thái LiveData | |
| You decide | | |

**User's choice:** Tách màn hình rõ ràng: LoginFragment -> RegisterFragment -> OtpVerifyFragment -> ForgotPasswordFragment

---

## Session & Security Architecture

### Session & Clean Logout
| Option | Description | Selected |
|--------|-------------|----------|
| SessionManager tập trung + AuthInterceptor phân loại lỗi | Giữ nguyên session khi mất mạng (IOException); chỉ logout khi nhận 401; khi logout xóa sạch Room DB + hủy WorkManager + xóa JWT Keystore | ✓ |
| Xử lý phân tán trên từng ViewModel | Mỗi màn hình tự bắt lỗi 401 và tự xử lý xóa dữ liệu riêng lẻ | |
| You decide | | |

**User's choice:** SessionManager tập trung + AuthInterceptor phân loại lỗi
**Notes:** Đảm bảo không đăng xuất người dùng vô cớ khi rớt mạng, đồng thời triệt tiêu rủi ro rò rỉ dữ liệu giữa 2 tài khoản đăng nhập trên cùng một máy.

### Device Management UI
| Option | Description | Selected |
|--------|-------------|----------|
| Màn hình riêng DeviceManagementFragment trong nav_profile.xml | Hiển thị danh sách thiết bị (GET /account/devices) với badge "Thiết bị này", nút thu hồi thiết bị khác (DELETE /account/devices/{id}) kèm dialog xác nhận | ✓ |
| Hiển thị danh sách thiết bị trực tiếp trên màn hình ProfileFragment | Gom chung vào 1 màn hình cuộn | |
| You decide | | |

**User's choice:** Màn hình riêng DeviceManagementFragment trong nav_profile.xml

---

## Privacy & Account Settings

### Privacy & Soft Delete
| Option | Description | Selected |
|--------|-------------|----------|
| Switch toggle quyền riêng tư (PATCH /me) + Dialog xác nhận nhập lại mật khẩu khi Xóa tài khoản (DELETE /me) | Tuân thủ 100% API_CONTRACT.md và an toàn tuyệt đối trước khi soft delete | ✓ |
| Chỉ cần 1 nút bấm xóa tài khoản không cần xác thực mật khẩu | Không an toàn theo chuẩn bảo mật ADR-018 | |
| You decide | | |

**User's choice:** Switch toggle quyền riêng tư (PATCH /me) + Dialog xác nhận nhập lại mật khẩu khi Xóa tài khoản (DELETE /me)

### i18n & DayNight
| Option | Description | Selected |
|--------|-------------|----------|
| Menu cài đặt trong ProfileFragment với RadioDialog chọn Ngôn ngữ và Giao diện | Tự động lưu SharedPreferences và áp dụng tức thì qua AppCompatDelegate | ✓ |
| Tách thành một màn hình Cài đặt chung riêng biệt (SettingsFragment) | | |
| You decide | | |

**User's choice:** Menu cài đặt trong ProfileFragment với RadioDialog chọn Ngôn ngữ và Giao diện

---

## Educational In-Code Commenting Standard
- Bắt buộc 100% mã nguồn (Java, XML, SQL, properties) phải có comment Tiếng Việt chi tiết, súc tích theo quy chuẩn [GEMINI.md](file:///c:/Users/Admin/Desktop/Project-Mobile/GEMINI.md).
