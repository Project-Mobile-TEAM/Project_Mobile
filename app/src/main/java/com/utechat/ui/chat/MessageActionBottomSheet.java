package com.utechat.ui.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.utechat.R;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.translate.MessageTranslator;
import com.utechat.databinding.DialogMessageActionsBinding;
import java.util.List;

/** Menu nhấn giữ dùng chung cho các loại tin nhắn. */
public final class MessageActionBottomSheet {
    public static final List<String> FIXED_EMOJIS = List.of("❤️", "😂", "👍", "😮", "😢", "😡");
    public static final long RECALL_WINDOW_MS = 86_400_000L;

    private MessageActionBottomSheet() {}

    public interface Listener {
        void onEmojiSelected(String emoji);
        void onTranslate(String language);
        void onHideTranslation();
        void onRecall();
        void onDeleteForMe();
    }

    /** Quyền hiển thị trên thiết bị. Backend vẫn kiểm tra quyền và thời hạn khi xử lý yêu cầu. */
    public static final class ActionVisibility {
        public final boolean showEmojiBar;
        public final boolean showTranslateVi;
        public final boolean showTranslateEn;
        public final boolean showHideTranslation;
        public final boolean showRecall;
        public final boolean showDeleteForMe;

        private ActionVisibility(boolean emoji, boolean translate, boolean hide, boolean recall, boolean delete) {
            showEmojiBar = emoji;
            showTranslateVi = translate;
            showTranslateEn = translate;
            showHideTranslation = hide;
            showRecall = recall;
            showDeleteForMe = delete;
        }
    }

    public static ActionVisibility computeVisibility(
            @NonNull MessageEntity message, @Nullable String meId, boolean readOnly,
            boolean isBlocked, boolean hasActiveTranslation, long nowMs) {
        String type = message.getType();
        boolean recalled = message.getRecalledAt() != null;
        boolean sent = message.getServerId() != null;
        boolean canModify = sent && !recalled && !readOnly && !isBlocked && !"SYSTEM".equals(type);
        boolean translate = !recalled && "TEXT".equals(type)
                && message.getText() != null && !message.getText().isBlank();
        boolean recall = canModify && !"CALL".equals(type)
                && meId != null && meId.equals(message.getSenderId())
                && nowMs - message.getCreatedAt() < RECALL_WINDOW_MS;
        return new ActionVisibility(canModify, translate, translate && hasActiveTranslation, recall, sent);
    }

    @Nullable public static BottomSheetDialog show(
            @NonNull Context context, @NonNull MessageEntity message, @Nullable String meId,
            @Nullable String myCurrentEmoji, boolean readOnly, boolean isBlocked,
            boolean hasActiveTranslation, @NonNull Listener listener) {
        ActionVisibility actions = computeVisibility(message, meId, readOnly, isBlocked,
                hasActiveTranslation, System.currentTimeMillis());
        if (!actions.showEmojiBar && !actions.showTranslateVi && !actions.showRecall && !actions.showDeleteForMe) {
            return null; // Không mở bảng trống cho tin chưa gửi.
        }

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        DialogMessageActionsBinding binding = DialogMessageActionsBinding.inflate(
                LayoutInflater.from(dialog.getContext()));
        dialog.setContentView(binding.getRoot());
        binding.emojiBar.setVisibility(actions.showEmojiBar ? View.VISIBLE : View.GONE);
        binding.actionDivider.setVisibility(actions.showEmojiBar ? View.VISIBLE : View.GONE);

        TextView[] targets = {binding.emoji0, binding.emoji1, binding.emoji2,
                binding.emoji3, binding.emoji4, binding.emoji5};
        for (int index = 0; index < targets.length; index++) {
            String emoji = FIXED_EMOJIS.get(index);
            TextView target = targets[index];
            target.setText(emoji);
            target.setSelected(emoji.equals(myCurrentEmoji));
            if (target.isSelected()) target.setBackgroundResource(R.drawable.bg_reaction_chip_mine);
            target.setOnClickListener(view -> {
                dialog.dismiss();
                listener.onEmojiSelected(emoji);
            });
        }
        bindAction(binding.actionTranslateVi, actions.showTranslateVi, dialog, () -> listener.onTranslate(MessageTranslator.LANG_VI));
        bindAction(binding.actionTranslateEn, actions.showTranslateEn, dialog, () -> listener.onTranslate(MessageTranslator.LANG_EN));
        bindAction(binding.actionHideTranslation, actions.showHideTranslation, dialog, listener::onHideTranslation);
        bindAction(binding.actionRecall, actions.showRecall, dialog, listener::onRecall);
        bindAction(binding.actionDeleteForMe, actions.showDeleteForMe, dialog, listener::onDeleteForMe);
        dialog.show();
        return dialog;
    }

    /** Đóng menu trước khi mở bảng xác nhận hoặc đổi trạng thái tin đang được xem. */
    private static void bindAction(View view, boolean visible, BottomSheetDialog dialog, Runnable action) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        view.setOnClickListener(ignored -> {
            dialog.dismiss();
            action.run();
        });
    }
}
