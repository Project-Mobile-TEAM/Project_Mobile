package com.utechat.data.repository;
/** Khóa phiên không chứa JWT; thay đổi cả khi đăng xuất rồi đăng nhập lại cùng tài khoản. */
public interface CurrentUserIdProvider {
    // null khi chưa đăng nhập; không dùng tài khoản demo làm fallback cho phiên thật.
    String getCurrentUserId();
    // Caller chụp khóa lúc bắt đầu yêu cầu, không coi chỉ ID tài khoản là đủ xác định phiên.
    String sessionKey();
    // Luồng không chứa token: Repository xóa dữ liệu đang quan sát ngay khi đổi phiên.
    androidx.lifecycle.LiveData<String> sessionChanges();
    // Kiểm khóa và chạy transaction dưới cùng khóa với logout; caller xếp ghi trên DISK chung.
    boolean runIfCurrent(String key,Runnable action);
}
