package com.utechat.data.remote.model;
import java.io.Serializable;

/** DTO Gson camelCase độc lập với Entity của Room. */
public final class ContactDtos {
    private ContactDtos() { }

    /** Hồ sơ tối thiểu dùng trong lời mời; không thêm SĐT vào model này. */
    public static class User implements Serializable { public String id,displayName,avatarUrl; }

    /** Giữ đúng tên isContact; ID và chiều pending dùng để chọn thao tác trên lời mời hiện có. */
    public static class Lookup extends User {
        public boolean isContact,hasPendingRequest;
        public String relationshipStatus,pendingRequestId,pendingRequestDirection;
    }

    /** phone có thể null; chỉ Repository quyết định cách giữ hồ sơ đã cache khi thiếu trường. */
    public static class Contact extends User { public String phone,createdAt; public boolean blocked; }

    /** Thời điểm ISO-8601 do server trả; respondedAt có thể chưa có khi đang chờ. */
    public static class Request implements Serializable {
        public String id,status,createdAt,respondedAt; public User requester,addressee;
    }

    /** Chỉ gửi người nhận; server lấy danh tính người gửi từ JWT, không cho client tự khai. */
    public static class Send { public final String addresseeId; public Send(String id) { addresseeId=id; } }

    /** Thân lỗi dùng chung cấu trúc error.code và error.details của backend. */
    public static class ErrorBody { public Error error; }
    public static class Error { public String code; public Details details; }

    /** direction thuộc lỗi 409; khác tên pendingRequestDirection trong kết quả lookup. */
    public static class Details { public int retryAfterSeconds; public String existingRequestId,direction; }
}
