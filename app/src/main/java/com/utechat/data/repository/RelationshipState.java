package com.utechat.data.repository;
/** Năm trạng thái quyết định nút thao tác trên màn tìm kiếm. */
public enum RelationshipState {
    STRANGER, OUTGOING_PENDING, INCOMING_PENDING, FRIEND, BLOCKED;

    /** Chỉ gợi ý nút; quyền cuối cùng vẫn do server kiểm tra. Phase 4 không có nút Nhắn tin. */
    public java.util.List<String> actions() {
        return switch(this) {
            case STRANGER -> java.util.List.of("send","block");
            case OUTGOING_PENDING -> java.util.List.of("cancel","block");
            case INCOMING_PENDING -> java.util.List.of("accept","reject","block");
            case FRIEND -> java.util.List.of("unfriend","block");
            case BLOCKED -> java.util.List.of("unblock");
        };
    }
}
