---
phase: "01"
status: clean
depth: standard
execution_mode: inline
reviewed: 2026-11-11
files_reviewed: 8
findings:
  critical: 0
  warning: 0
  info: 0
  total: 0
---

# Phase 01 — Code Review

Scope: .gitignore, .env.example, docs/SUPABASE_SETUP.md, core DDL, demo seed,
verify-schema.sql, test-db-connection.ps1 và test-schema.mjs.
Review trực tiếp trong phiên chính theo Codex skill adapter; không có reviewer độc lập/subagent.

## Kết quả

Không còn lỗi cần sửa trong phạm vi implementation đã kiểm tra.
Đối chiếu DDL với ERD: đủ 13 bảng V1, FK, kiểu dữ liệu, trường quyền riêng tư, PHONE_DEVICE,
cặp lời mời không phân biệt chiều, attachment unique và watermark read <= delivered.
Tách db/demo đúng ranh giới production. Bộ test dùng PostgreSQL WASM thực thi SQL,
không chỉ tìm chuỗi trong source.

## Lỗi đã sửa và kiểm chứng

- Seed replay từng hạ mốc read của Bob/Charlie về 0: dùng GREATEST, test nâng receipt trước
  khi seed lại xác nhận không lùi.
- Sequence BIGSERIAL không dùng ID message cố định; test thêm message sau seed không va chạm.
- HTTP 401 không được đánh giá như RLS PASS; exception không có Response được xử lý dưới StrictMode.
- Không hardcode mật khẩu/hash demo; test BCrypt thực sự so khớp mật khẩu ngẫu nhiên trong bộ nhớ.
- Verifier trả exception khi index thiếu, RLS tắt hoặc policy mở; cả ba mutation tests PASS.

## Giới hạn

16/16 SQL checks và kiểm tra cấu hình PowerShell PASS. Full từ chối placeholder đúng mã thoát.
Không đánh giá cloud/Flyway/TLS/pooler đã hoạt động, không có credentials để kiểm.
Bản thân RLS không thay thế phân quyền nghiệp vụ phía Spring Boot; phần đó thuộc Phase 3–6.
Không có UI change; UI audit không áp dụng. Phase 1 không có suite phase trước để regression.
