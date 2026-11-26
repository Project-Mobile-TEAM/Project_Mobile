# Phase 7: Message Actions, Reactions & Stickers - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-27
**Phase:** 07-message-actions-reactions-stickers
**Areas discussed:** Gỡ tin nhắn 24h & Xóa ở phía bạn, Bày tỏ cảm xúc tin nhắn (Emoji Reactions), Tải bộ nhãn dán & Gửi Sticker, Menu nhấn giữ hợp nhất & Hành vi Offline

---

## Tổng hợp quyết định cốt lõi từ người dùng (4 mảng chính)

**User's specification (free-text):**
- **Gỡ tin trong 24 giờ và Xóa phía bạn:** Chỉ người gửi được gỡ tin cho mọi người. Backend tính hạn 24 giờ từ thời điểm gửi và kiểm quyền, kể cả trong nhóm. Tin đã gỡ giữ vị trí trong hội thoại với dòng “Tin nhắn đã được gỡ”. Khi đồng bộ, cập nhật preview, loại nội dung khỏi FTS4 và vô hiệu hóa tải lại media của tin đã gỡ. Xóa phía bạn chỉ ẩn tin trên các thiết bị của tài khoản đã xóa.
- **Thả cảm xúc:** Dùng 6 emoji cố định. Mỗi người có tối đa một cảm xúc trên mỗi tin. Chọn emoji khác để đổi, chạm lại emoji đang dùng để bỏ. Chạm vào cụm cảm xúc để xem ai đã thả. Đồng bộ kết quả giữa các thiết bị.
- **Tải bộ và gửi sticker:** Backend cung cấp hai bộ mẫu. Người dùng có thể thêm hoặc gỡ bộ, đồng bộ danh sách đã thêm theo tài khoản và tải ảnh về cache để xem offline. Sticker đã gửi vẫn hiển thị trong lịch sử sau khi gỡ bộ. Dùng bong bóng sticker riêng và cho phép gửi sticker đã tải qua hàng đợi khi mất mạng.
- **Menu nhấn giữ và hành vi offline:** Dùng một Bottom Sheet chung cho Cảm xúc, Dịch, Gỡ và Xóa, hiển thị theo loại tin và quyền. Đặt nút mở khay sticker cạnh ô nhập. Gỡ, xóa và đổi cảm xúc cần kết nối để server xác nhận. Khi mất mạng, báo rõ trạng thái và giữ nguyên tin đang hiển thị. Dịch tiếp tục hoạt động offline nếu đã tải gói ngôn ngữ.

---

## Xác nhận 3 tình huống biên

### Câu 1: Quyền khi đã rời nhóm (chỉ đọc) hoặc bị chặn ở chat 1-1

| Option | Description | Selected |
|--------|-------------|----------|
| Chỉ cho phép "Xóa ở phía bạn"; chặn Gỡ tin 24h và Thả cảm xúc (`403 NOT_A_MEMBER` / `403 BLOCKED`) | Nhất quán với chế độ chỉ đọc Phase 5 (`D-12`) | ✓ |
| Vẫn cho người gửi Gỡ tin 24h, chỉ chặn Thả cảm xúc | Nới lỏng gỡ tin khi đã rời/bị chặn | |

**User's choice:** Chỉ cho phép "Xóa ở phía bạn"; chặn Gỡ tin 24h và Thả cảm xúc (`403 NOT_A_MEMBER` / `403 BLOCKED`) — (Recommended).

### Câu 2: Cập nhật preview trên Danh sách chat khi tin mới nhất bị Gỡ hoặc Xóa phía bạn

| Option | Description | Selected |
|--------|-------------|----------|
| Tin bị gỡ → "Tin nhắn đã được gỡ"; tin bị xóa phía bạn → tự lùi về tin gần nhất còn lại chưa xóa (hoặc rỗng) | Chính xác theo trạng thái thực tế của hội thoại | ✓ |
| Giữ nguyên preview cũ | Đơn giản hóa DAO nhưng lệch dữ liệu | |

**User's choice:** Tin bị gỡ → "Tin nhắn đã được gỡ"; tin bị xóa phía bạn → tự lùi về tin gần nhất còn lại chưa xóa (hoặc rỗng) — (Recommended).

### Câu 3: Trạng thái mặc định của 2 bộ Sticker mẫu cho tài khoản mới

| Option | Description | Selected |
|--------|-------------|----------|
| Cài sẵn 1 bộ mặc định (gửi được ngay), bộ thứ 2 nằm trong Cửa hàng để bấm Tải về / Gỡ ra | Thuận tiện demo cả luồng gửi ngay lẫn luồng tải/gỡ bộ | ✓ |
| Cả 2 bộ đều chưa cài sẵn | Người dùng phải tự bấm tải trước mới gửi được | |

**User's choice:** Cài sẵn 1 bộ mặc định, bộ thứ 2 nằm trong Cửa hàng để bấm Tải về / Gỡ ra — (Recommended).

---

## the agent's Discretion

- Chi tiết thiết kế 2 bộ ảnh sticker mẫu (`.webp`/`.png` gọn nhẹ phục vụ từ backend), bố cục chi tiết khay sticker / cửa hàng và reaction chip.

## Deferred Ideas

- Testing, Quality & Release Packaging → chuyển sang **Phase 8**.
- Multi-emoji per user, full emoji keyboard, user-uploaded custom sticker packs, và Admin gỡ tin thành viên khác → thuộc **V2**.
