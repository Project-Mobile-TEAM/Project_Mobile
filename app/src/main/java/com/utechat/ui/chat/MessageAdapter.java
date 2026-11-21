package com.utechat.ui.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.R;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.databinding.ItemMessageInBinding;
import com.utechat.databinding.ItemMessageOutBinding;
import com.utechat.databinding.ItemMessageSystemBinding;
import com.utechat.databinding.ViewDatePillBinding;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tin nhắn trong phòng (D-15) với 6 kiểu hiển thị. Tin ảnh/thoại/file/vị trí/cuộc gọi chỉ hiện thẻ tóm tắt,
 * KHÔNG gắn hành động mở/phát/gọi (thuộc phase sau); kiểu lạ hiện thẻ "không hỗ trợ" thay vì crash.
 */
public class MessageAdapter extends ListAdapter<MessageEntity, RecyclerView.ViewHolder> {
    static final int TYPE_SYSTEM = 0, TYPE_TEXT_OUT = 1, TYPE_TEXT_IN = 2,
            NON_TEXT_SUMMARY_OUT = 3, NON_TEXT_SUMMARY_IN = 4, UNKNOWN_FALLBACK = 5;
    private static final Set<String> SUMMARY = Set.of("IMAGE", "AUDIO", "VIDEO", "FILE", "LOCATION", "CALL");

    @Nullable private final String meId;
    private final Consumer<MessageEntity> onFailedClick;
    @Nullable private Map<String, UserEntity> users;
    private boolean group;
    private long highlighted = -1;

    public MessageAdapter(@Nullable String meId, Consumer<MessageEntity> onFailedClick) {
        super(DIFF);
        this.meId = meId;
        this.onFailedClick = onFailedClick;
    }

    public void setUsers(@Nullable Map<String, UserEntity> users) {
        if (Objects.equals(this.users, users)) return;
        this.users = users;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setGroup(boolean group) {
        if (this.group == group) return;
        this.group = group;
        notifyItemRangeChanged(0, getItemCount());
    }

    /** Tô nền tin vừa nhảy tới từ tìm kiếm; chỉ vẽ lại dòng cũ và dòng mới. */
    public void setHighlightedLocalId(long localId) {
        int old = indexOf(highlighted);
        highlighted = localId;
        if (old >= 0) notifyItemChanged(old);
        int now = indexOf(localId);
        if (now >= 0) notifyItemChanged(now);
    }

    int indexOf(long localId) {
        for (int i = 0; i < getItemCount(); i++) if (getItem(i).getLocalId() == localId) return i;
        return -1;
    }

    static int viewType(MessageEntity m, @Nullable String meId) {
        boolean mine = meId != null && meId.equals(m.getSenderId());
        String type = m.getType() == null ? "" : m.getType();
        if ("SYSTEM".equals(type)) return TYPE_SYSTEM;
        if ("TEXT".equals(type)) return mine ? TYPE_TEXT_OUT : TYPE_TEXT_IN;
        if (SUMMARY.contains(type)) return mine ? NON_TEXT_SUMMARY_OUT : NON_TEXT_SUMMARY_IN;
        return UNKNOWN_FALLBACK;
    }

    @Override public int getItemViewType(int position) { return viewType(getItem(position), meId); }

    /** Nạp thêm tin cũ ở đầu danh sách: dòng đầu cũ có thể không còn là tin đầu ngày, vẽ lại mốc ngày của nó. */
    @Override public void onCurrentListChanged(@NonNull java.util.List<MessageEntity> previous, @NonNull java.util.List<MessageEntity> current) {
        if (previous.isEmpty() || current.isEmpty() || previous.get(0).getLocalId() == current.get(0).getLocalId()) return;
        int i = indexOf(previous.get(0).getLocalId());
        if (i >= 0) notifyItemChanged(i);
    }

    static class OutHolder extends RecyclerView.ViewHolder {
        final ItemMessageOutBinding b;
        OutHolder(ItemMessageOutBinding b) { super(b.getRoot()); this.b = b; }
    }
    static class InHolder extends RecyclerView.ViewHolder {
        final ItemMessageInBinding b;
        InHolder(ItemMessageInBinding b) { super(b.getRoot()); this.b = b; }
    }
    static class SysHolder extends RecyclerView.ViewHolder {
        final ItemMessageSystemBinding b;
        SysHolder(ItemMessageSystemBinding b) { super(b.getRoot()); this.b = b; }
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater in = LayoutInflater.from(parent.getContext());
        if (type == TYPE_SYSTEM) return new SysHolder(ItemMessageSystemBinding.inflate(in, parent, false));
        if (type == TYPE_TEXT_OUT || type == NON_TEXT_SUMMARY_OUT) return new OutHolder(ItemMessageOutBinding.inflate(in, parent, false));
        return new InHolder(ItemMessageInBinding.inflate(in, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageEntity m = getItem(position);
        Context c = holder.itemView.getContext();
        int type = getItemViewType(position);
        holder.itemView.setBackgroundColor(m.getLocalId() == highlighted
                ? ContextCompat.getColor(c, R.color.ute_warning_bg) : android.graphics.Color.TRANSPARENT);
        String time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(m.getCreatedAt()).atZone(ZoneId.systemDefault()));

        if (holder instanceof SysHolder) {
            SysHolder h = (SysHolder) holder;
            bindDate(h.b.date, position);
            h.b.text.setText(SystemMessageFormatter.format(c::getString, m.getAttachmentJson(), users, meId));
            return;
        }
        if (holder instanceof OutHolder) {
            OutHolder h = (OutHolder) holder;
            bindDate(h.b.date, position);
            bindBody(c, type == NON_TEXT_SUMMARY_OUT, m, h.b.summary, h.b.summaryIcon, h.b.summaryLabel, h.b.text);
            h.b.meta.setText(c.getString(R.string.chat_dot_pair, time, c.getString(statusLabel(m.getStatus()))));
            boolean failed = "FAILED".equals(m.getStatus());
            h.b.failed.setVisibility(failed ? View.VISIBLE : View.GONE);
            if (failed && m.getErrorCode() != null) {
                h.b.failed.setText(c.getString(R.string.chat_dot_pair, c.getString(ChatErrors.message(m.getErrorCode())), c.getString(R.string.chat_resend)));
            } else {
                h.b.failed.setText(R.string.chat_status_failed);
            }
            // Chỉ tin lỗi mới bấm được (mở hộp thoại Gửi lại / Xóa tin lỗi).
            View.OnClickListener click = failed ? v -> onFailedClick.accept(m) : null;
            h.b.bubble.setOnClickListener(click);
            h.b.failed.setOnClickListener(click);
            h.b.bubble.setClickable(failed);
            h.b.failed.setClickable(failed);
            return;
        }
        InHolder h = (InHolder) holder;
        bindDate(h.b.date, position);
        UserEntity sender = users == null || m.getSenderId() == null ? null : users.get(m.getSenderId());
        String name = sender == null || sender.getDisplayName() == null ? c.getString(R.string.chat_someone) : sender.getDisplayName();
        h.b.sender.setVisibility(group ? View.VISIBLE : View.GONE);
        h.b.sender.setText(name);
        h.b.avatar.setVisibility(group ? View.VISIBLE : View.GONE);
        h.b.avatar.setText(ConversationListAdapter.initials(name));
        h.b.meta.setText(time);
        if (type == UNKNOWN_FALLBACK) {
            h.b.summary.setVisibility(View.GONE);
            h.b.text.setText(R.string.chat_type_unknown);
            h.b.text.setTextColor(ContextCompat.getColor(c, R.color.ute_text_secondary));
        } else {
            h.b.text.setTextColor(ContextCompat.getColor(c, R.color.ute_text));
            bindBody(c, type == NON_TEXT_SUMMARY_IN, m, h.b.summary, h.b.summaryIcon, h.b.summaryLabel, h.b.text);
        }
    }

    /** Tin chữ hiện nội dung; tin khác hiện icon + nhãn + chi tiết an toàn đọc từ attachmentJson. */
    private static void bindBody(Context c, boolean summary, MessageEntity m, View box, android.widget.ImageView icon,
                                 android.widget.TextView label, android.widget.TextView text) {
        box.setVisibility(summary ? View.VISIBLE : View.GONE);
        if (!summary) {
            text.setVisibility(View.VISIBLE);
            text.setText(m.getText());
            return;
        }
        icon.setImageResource(icon(m.getType()));
        label.setText(SystemMessageFormatter.summaryLabel(m.getType()));
        String meta = summaryMeta(m.getType(), m.getAttachmentJson());
        text.setVisibility(meta == null ? View.GONE : View.VISIBLE);
        text.setText(meta);
    }

    private void bindDate(ViewDatePillBinding date, int position) {
        long now = getItem(position).getCreatedAt();
        boolean first = position == 0 || !sameDay(getItem(position - 1).getCreatedAt(), now);
        date.getRoot().setVisibility(first ? View.VISIBLE : View.GONE);
        if (first) date.dateText.setText(dayLabel(date.getRoot().getContext(), now));
    }

    private static boolean sameDay(long a, long b) {
        ZoneId z = ZoneId.systemDefault();
        return Instant.ofEpochMilli(a).atZone(z).toLocalDate().equals(Instant.ofEpochMilli(b).atZone(z).toLocalDate());
    }

    private static String dayLabel(Context c, long millis) {
        LocalDate day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return c.getString(R.string.chat_today);
        if (day.equals(today.minusDays(1))) return c.getString(R.string.chat_yesterday);
        return DateTimeFormatter.ofPattern("dd/MM/yyyy").format(day);
    }

    static int statusLabel(@Nullable String status) {
        if (status == null) return R.string.chat_status_sent;
        switch (status) {
            case "SENDING": return R.string.chat_status_sending;
            case "DELIVERED": return R.string.chat_status_delivered;
            case "READ": return R.string.chat_status_read;
            case "FAILED": return R.string.chat_status_failed_short;
            default: return R.string.chat_status_sent;
        }
    }

    private static int icon(@Nullable String type) {
        if ("IMAGE".equals(type) || "VIDEO".equals(type)) return R.drawable.ic_camera;
        if ("AUDIO".equals(type)) return R.drawable.ic_mic;
        if ("CALL".equals(type)) return R.drawable.ic_phone;
        if ("LOCATION".equals(type)) return R.drawable.ic_globe;
        return R.drawable.ic_file;
    }

    /**
     * Chi tiết hiển thị được của tin không phải chữ: tên file, thời lượng mm:ss, tọa độ làm tròn.
     * Chỉ đọc chữ/số; không bao giờ dùng URL trong JSON để mở gì cả. JSON hỏng trả null.
     */
    @Nullable static String summaryMeta(@Nullable String type, @Nullable String json) {
        if (type == null || json == null || json.isBlank()) return null;
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return null;
            o = e.getAsJsonObject();
        } catch (RuntimeException malformed) {
            return null;
        }
        try {
            switch (type) {
                case "FILE": return str(o, "fileName");
                case "AUDIO": case "VIDEO": {
                    JsonElement d = o.get("durationMs");
                    if (d == null || !d.isJsonPrimitive()) return null;
                    long s = d.getAsLong() / 1000;
                    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
                }
                case "LOCATION": {
                    JsonElement lat = o.get("latitude"), lng = o.get("longitude");
                    if (lat == null || lng == null || !lat.isJsonPrimitive() || !lng.isJsonPrimitive()) return null;
                    return String.format(Locale.ROOT, "%.4f, %.4f", lat.getAsDouble(), lng.getAsDouble());
                }
                default: return null;
            }
        } catch (RuntimeException wrongType) {
            return null; // Trường có kiểu lạ (chuỗi thay vì số...) thì bỏ chi tiết, vẫn hiện nhãn.
        }
    }

    @Nullable private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static final DiffUtil.ItemCallback<MessageEntity> DIFF = new DiffUtil.ItemCallback<>() {
        @Override public boolean areItemsTheSame(@NonNull MessageEntity a, @NonNull MessageEntity b) {
            return a.getLocalId() == b.getLocalId() && Objects.equals(a.getClientId(), b.getClientId());
        }
        @Override public boolean areContentsTheSame(@NonNull MessageEntity a, @NonNull MessageEntity b) {
            return Objects.equals(a.getText(), b.getText()) && Objects.equals(a.getStatus(), b.getStatus())
                    && Objects.equals(a.getErrorCode(), b.getErrorCode()) && Objects.equals(a.getServerId(), b.getServerId())
                    && Objects.equals(a.getAttachmentJson(), b.getAttachmentJson()) && a.getCreatedAt() == b.getCreatedAt();
        }
    };
}
