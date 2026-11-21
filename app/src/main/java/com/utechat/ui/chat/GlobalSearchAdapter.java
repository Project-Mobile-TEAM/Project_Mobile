package com.utechat.ui.chat;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.utechat.R;
import com.utechat.data.local.MessageText;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.databinding.ItemChatSectionBinding;
import com.utechat.databinding.ItemConversationBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Kết quả tìm kiếm toàn cục (D-13), tách rõ hai phần: "Hội thoại" (khớp tên) và
 * "Tin nhắn trên thiết bị" (FTS4 trên cache cục bộ, không phải toàn bộ lịch sử server).
 */
public class GlobalSearchAdapter extends ListAdapter<GlobalSearchAdapter.Row, RecyclerView.ViewHolder> {
    /** Một dòng: tiêu đề mục, hội thoại khớp tên, hoặc tin nhắn khớp nội dung. */
    public static final class Row {
        final String key;
        @Nullable final String header;
        @Nullable final ConversationListItem conversation;
        @Nullable final MessageDao.MessageSearchHit hit;
        private Row(String key, @Nullable String header, @Nullable ConversationListItem c, @Nullable MessageDao.MessageSearchHit h) {
            this.key = key; this.header = header; this.conversation = c; this.hit = h;
        }
        public static Row header(String text) { return new Row("h:" + text, text, null, null); }
        public static Row conversation(ConversationListItem c) { return new Row("c:" + c.conversation.getId(), null, c, null); }
        public static Row message(MessageDao.MessageSearchHit h) { return new Row("m:" + h.message.getLocalId(), null, null, h); }
    }

    public interface Listener {
        void openConversation(ConversationListItem item);
        void openMessage(MessageDao.MessageSearchHit hit);
    }

    private static final int HEADER = 0, CONVERSATION = 1, MESSAGE = 2;
    private final Listener listener;
    @Nullable private final String meId;
    @Nullable private Map<String, UserEntity> users;
    private String query = "";

    public GlobalSearchAdapter(@Nullable String meId, Listener listener) {
        super(DIFF);
        this.meId = meId;
        this.listener = listener;
    }

    public void setUsers(@Nullable Map<String, UserEntity> users) { this.users = users; }

    /** Gửi danh sách cùng từ khóa để tô đậm phần khớp trong đoạn trích. */
    public void submit(List<Row> rows, String query) {
        this.query = query;
        submitList(rows);
    }

    @Override public int getItemViewType(int position) {
        Row r = getItem(position);
        return r.header != null ? HEADER : r.conversation != null ? CONVERSATION : MESSAGE;
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (type == HEADER) return new RecyclerView.ViewHolder(ItemChatSectionBinding.inflate(inflater, parent, false).getRoot()) { };
        return new ConversationListAdapter.Holder(ItemConversationBinding.inflate(inflater, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = getItem(position);
        if (r.header != null) { ((TextView) holder.itemView).setText(r.header); return; }
        ItemConversationBinding b = ((ConversationListAdapter.Holder) holder).b;
        if (r.conversation != null) {
            ConversationListAdapter.bindConversation(b, r.conversation, users, meId);
            holder.itemView.setOnClickListener(v -> listener.openConversation(r.conversation));
            return;
        }
        MessageDao.MessageSearchHit hit = Objects.requireNonNull(r.hit);
        boolean group = "GROUP".equals(hit.conversationType);
        String sender = hit.message.getSenderId();
        String text = hit.message.getText() == null ? "" : hit.message.getText();
        // Giữ quy ước xem trước của danh sách: "Bạn: ..." hoặc "Tên: ..." trong nhóm.
        String prefix = sender != null && sender.equals(meId) ? b.getRoot().getContext().getString(R.string.chat_preview_me, "")
                : group ? b.getRoot().getContext().getString(R.string.chat_preview_sender, senderName(sender), "") : "";
        SpannableString snippet = new SpannableString(prefix + text);
        int color = ContextCompat.getColor(b.getRoot().getContext(), R.color.ute_accent);
        for (int[] range : matchRanges(text, query)) {
            int s = prefix.length() + range[0], e = prefix.length() + range[1];
            snippet.setSpan(new ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            snippet.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String title = hit.conversationTitle == null ? "" : hit.conversationTitle;
        ConversationListAdapter.bindRow(b, title, snippet, group, hit.message.getCreatedAt(), 0, false);
        holder.itemView.setOnClickListener(v -> listener.openMessage(hit));
    }

    private String senderName(@Nullable String id) {
        UserEntity u = id == null || users == null ? null : users.get(id);
        return u == null || u.getDisplayName() == null ? "" : u.getDisplayName();
    }

    /**
     * Vị trí [đầu, cuối) các từ khóa trong text, so khớp không dấu ("slide" khớp "Slide", "bao cao" khớp "báo cáo").
     * Chuẩn hóa từng ký tự để chỉ số trên chuỗi không dấu ánh xạ ngược về chuỗi gốc.
     */
    static List<int[]> matchRanges(String text, @Nullable String query) {
        List<int[]> out = new ArrayList<>();
        String q = MessageText.normalize(query);
        if (text == null || q == null || q.isBlank()) return out;
        StringBuilder norm = new StringBuilder();
        List<Integer> origin = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            String n = MessageText.normalize(String.valueOf(text.charAt(i)));
            for (int k = 0; k < n.length(); k++) { norm.append(n.charAt(k)); origin.add(i); }
        }
        String hay = norm.toString();
        for (String token : q.trim().split("\\s+")) {
            for (int at = hay.indexOf(token); at >= 0; at = hay.indexOf(token, at + token.length())) {
                out.add(new int[]{origin.get(at), origin.get(at + token.length() - 1) + 1});
            }
        }
        return out;
    }

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<>() {
        @Override public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) { return a.key.equals(b.key); }
        // Từ khóa đổi thì đoạn tô đậm đổi theo; cho vẽ lại các dòng tin nhắn.
        @Override public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) { return a.header != null && a.key.equals(b.key); }
    };
}
