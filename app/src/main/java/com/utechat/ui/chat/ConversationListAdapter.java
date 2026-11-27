package com.utechat.ui.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.utechat.R;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.databinding.ItemConversationBinding;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Danh sách hội thoại (D-08); DiffUtil theo id nên không cần notifyDataSetChanged(). */
public class ConversationListAdapter extends ListAdapter<ConversationListItem, ConversationListAdapter.Holder> {
    private final Consumer<ConversationListItem> onClick;
    @Nullable private Map<String, UserEntity> users;
    @Nullable private final String meId;

    public ConversationListAdapter(@Nullable String meId, Consumer<ConversationListItem> onClick) {
        super(DIFF);
        this.meId = meId;
        this.onClick = onClick;
    }

    /** Tên người dùng đến sau danh sách: vẽ lại phần xem trước SYSTEM/nhóm cho đúng tên. */
    public void setUsers(@Nullable Map<String, UserEntity> users) {
        if (Objects.equals(this.users, users)) return;
        this.users = users;
        notifyItemRangeChanged(0, getItemCount());
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemConversationBinding b;
        Holder(ItemConversationBinding b) { super(b.getRoot()); this.b = b; }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        ConversationListItem item = getItem(position);
        bindConversation(h.b, item, users, meId);
        h.itemView.setOnClickListener(v -> onClick.accept(item));
    }

    /** Dùng chung với GlobalSearchAdapter để hai danh sách trông giống hệt nhau. */
    static void bindConversation(ItemConversationBinding b, ConversationListItem item,
                                 @Nullable Map<String, UserEntity> users, @Nullable String meId) {
        Context c = b.getRoot().getContext();
        boolean group = "GROUP".equals(item.conversation.getType());
        String preview = SystemMessageFormatter.preview(c::getString, item.lastMessageType, item.lastMessageText,
                item.lastMessageAttachmentJson, item.lastMessageSenderId, group, users, meId,
                item.lastMessageRecalledAt);
        bindRow(b, item.getEffectiveTitle(), preview, group, item.conversation.getLastMessageAt(),
                item.conversation.getUnreadCount(), item.conversation.getMuted());
    }

    static void bindRow(ItemConversationBinding b, String title, CharSequence preview, boolean group,
                        long timeMillis, int unread, boolean muted) {
        Context c = b.getRoot().getContext();
        b.title.setText(title);
        b.avatar.setText(group ? "" : initials(title));
        b.avatarGroup.setVisibility(group ? View.VISIBLE : View.GONE);
        b.preview.setText(preview);
        b.time.setText(timeMillis > 0 ? formatTime(c, timeMillis) : "");
        // Có tin chưa đọc: tên đậm, giờ màu nhấn, huy hiệu đỏ (Figma).
        boolean hasUnread = unread > 0;
        b.title.setTypeface(ResourcesCompat.getFont(c, hasUnread ? R.font.poppins_semibold : R.font.poppins_regular));
        b.time.setTextColor(hasUnread ? ContextCompat.getColor(c, R.color.ute_accent)
                : ContextCompat.getColor(c, R.color.ute_text_secondary));
        b.badge.setVisibility(hasUnread ? View.VISIBLE : View.GONE);
        b.badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
        b.badge.setContentDescription(c.getResources().getQuantityString(R.plurals.chat_unread_desc, unread, unread));
        b.muted.setVisibility(muted ? View.VISIBLE : View.GONE);
    }

    /** "Nguyễn Hoài An" → "NA": chữ đầu của từ đầu và từ cuối, như avatar trong Figma. */
    static String initials(@Nullable String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].substring(0, parts[0].offsetByCodePoints(0, 1));
        if (parts.length == 1) return first.toUpperCase(Locale.ROOT);
        String last = parts[parts.length - 1];
        return (first + last.substring(0, last.offsetByCodePoints(0, 1))).toUpperCase(Locale.ROOT);
    }

    /** Hôm nay: giờ; hôm qua: "Hôm qua"; trong tuần: thứ; xa hơn: ngày/tháng. */
    static String formatTime(Context c, long millis) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);
        if (day.equals(today)) return DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone));
        if (day.equals(today.minusDays(1))) return c.getString(R.string.chat_yesterday);
        Locale locale = c.getResources().getConfiguration().getLocales().get(0);
        if (day.isAfter(today.minusDays(7))) return day.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);
        return DateTimeFormatter.ofPattern("dd/MM").format(day);
    }

    private static final DiffUtil.ItemCallback<ConversationListItem> DIFF = new DiffUtil.ItemCallback<>() {
        @Override public boolean areItemsTheSame(@NonNull ConversationListItem a, @NonNull ConversationListItem b) {
            return a.conversation.getId().equals(b.conversation.getId());
        }
        @Override public boolean areContentsTheSame(@NonNull ConversationListItem a, @NonNull ConversationListItem b) {
            return a.getEffectiveTitle().equals(b.getEffectiveTitle())
                    && Objects.equals(a.lastMessageText, b.lastMessageText)
                    && Objects.equals(a.lastMessageType, b.lastMessageType)
                    && Objects.equals(a.lastMessageAttachmentJson, b.lastMessageAttachmentJson)
                    && Objects.equals(a.lastMessageSenderId, b.lastMessageSenderId)
                    && a.conversation.getLastMessageAt() == b.conversation.getLastMessageAt()
                    && a.conversation.getUnreadCount() == b.conversation.getUnreadCount()
                    && a.conversation.getMuted() == b.conversation.getMuted();
        }
    };
}
