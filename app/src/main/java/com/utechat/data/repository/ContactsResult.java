package com.utechat.data.repository;

/** Tách thao tác thành công khỏi việc cache chưa làm mới được sau phản hồi REST. */
public final class ContactsResult<T> {
    // Dữ liệu chỉ do factory thành công gán; lỗi không mang theo dữ liệu thành công giả.
    public T data;
    public String errorCode;
    // Metadata 409 giúp lớp gọi thao tác trên lời mời đang tồn tại, không tự tạo lời mời mới.
    public String existingRequestId, direction;
    public int retryAfterSeconds;
    // Repository quyết định khi nào phải tải lại cache; bộ đọc lỗi HTTP không tự bật cờ này.
    public boolean refreshRequired;

    private ContactsResult() { }

    public boolean isSuccess() { return errorCode == null; }

    /** Cho phép dữ liệu null khi thao tác thành công không trả nội dung. */
    public static <T> ContactsResult<T> ok(T data) {
        ContactsResult<T> result = new ContactsResult<>();
        result.data = data;
        return result;
    }

    /** Chặn mã lỗi null/rỗng vì isSuccess dùng null để biểu diễn thành công. */
    public static <T> ContactsResult<T> error(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("Mã lỗi không được trống");
        }
        ContactsResult<T> result = new ContactsResult<>();
        result.errorCode = code;
        return result;
    }
}
