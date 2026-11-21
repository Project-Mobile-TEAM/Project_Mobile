package com.utechat.ui.chat;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.utechat.R;

/** Dịch mã lỗi server/repository của chat sang chuỗi hiển thị; mã lạ dùng thông báo chung. */
public final class ChatErrors {
    private ChatErrors() { }

    @StringRes public static int message(@Nullable String code) {
        if (code == null) return R.string.chat_error_generic;
        switch (code) {
            case "BLOCKED": return R.string.chat_error_blocked;
            case "CONTACT_REQUIRED": return R.string.chat_contact_required;
            case "NOT_A_MEMBER": return R.string.chat_error_not_member;
            case "NOT_ADMIN": return R.string.chat_error_not_admin;
            case "GROUP_FULL": return R.string.chat_error_group_full;
            case "ADMIN_HANDOVER_REQUIRED": return R.string.chat_error_handover_required;
            case "MEMBERSHIP_CHANGED": return R.string.chat_error_membership_changed;
            case "RATE_LIMITED": return R.string.chat_error_rate_limited;
            case "VALIDATION": return R.string.chat_error_validation;
            case "NOT_FOUND": return R.string.chat_error_not_found;
            case "NETWORK": return R.string.chat_error_network;
            default: return R.string.chat_error_generic;
        }
    }
}
