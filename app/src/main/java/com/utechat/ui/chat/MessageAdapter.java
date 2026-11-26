package com.utechat.ui.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.databinding.ItemMessageInBinding;
import com.utechat.databinding.ItemMessageOutBinding;
import com.utechat.databinding.ItemMessageSummaryInBinding;
import com.utechat.databinding.ItemMessageSummaryOutBinding;
import com.utechat.databinding.ItemMessageSystemBinding;
import com.utechat.databinding.ViewDatePillBinding;
import com.utechat.ui.media.VoiceBubblePlayer;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bộ điều hợp tin nhắn phòng chat Phase 5 & Phase 6 (D-03, D-13..D-15):
 * - Bảo toàn tuyệt đối 6 viewType và hàm static viewType()/summaryMeta() cho kiểm thử Phase 5.
 * - Bong bóng TEXT (item_message_in/out) hỗ trợ nhấn giữ dịch EN<->VI nội tuyến (translation_box).
 * - Bong bóng media/vị trí/cuộc gọi (item_message_summary_in/out) dựng ảnh Glide, poster video + nút phát,
 *   voice player nội tuyến, thẻ tài liệu/vị trí/cuộc gọi và thanh tiến trình upload/download với cập nhật payload cục bộ.
 */
public class MessageAdapter extends ListAdapter<MessageEntity, RecyclerView.ViewHolder> {
    static final int TYPE_SYSTEM = 0, TYPE_TEXT_OUT = 1, TYPE_TEXT_IN = 2,
            NON_TEXT_SUMMARY_OUT = 3, NON_TEXT_SUMMARY_IN = 4, UNKNOWN_FALLBACK = 5;
    private static final Set<String> SUMMARY = Set.of("IMAGE", "AUDIO", "VIDEO", "FILE", "LOCATION", "CALL");
    private static final Object PAYLOAD_VOICE = new Object();
    private static final Object PAYLOAD_TRANSFER = new Object();
    private static final Object PAYLOAD_TRANSLATION = new Object();
    private static final int VIDEO_REMOTE_POSTER_COLOR = 0xFF1A1F26;

    /** Callback tương tác cho các loại bong bóng media, vị trí và dịch tin nhắn TEXT. */
    public interface InteractionListener {
        void onImageClick(@NonNull MessageEntity message);

        void onVideoClick(@NonNull MessageEntity message);

        void onVoiceToggleClick(@NonNull MessageEntity message);

        void onVoiceSeek(@NonNull MessageEntity message, int positionMs);

        void onFileClick(@NonNull MessageEntity message);

        void onLocationClick(@NonNull MessageEntity message);

        void onTextLongClick(@NonNull MessageEntity message);

        void onHideTranslationClick(@NonNull MessageEntity message);
    }

    /** Trạng thái hiển thị bản dịch cho một tin TEXT (không bao giờ có trường phần trăm tải!). */
    public static final class TranslationUiEntry {
        public final boolean downloading;
        @Nullable public final String translatedText;
        @Nullable public final String targetLang;

        private TranslationUiEntry(boolean downloading, @Nullable String translatedText, @Nullable String targetLang) {
            this.downloading = downloading;
            this.translatedText = translatedText;
            this.targetLang = targetLang;
        }

        @NonNull
        public static TranslationUiEntry downloading(@NonNull String targetLang) {
            return new TranslationUiEntry(true, null, targetLang);
        }

        @NonNull
        public static TranslationUiEntry translated(@NonNull String translatedText, @NonNull String targetLang) {
            return new TranslationUiEntry(false, translatedText, targetLang);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TranslationUiEntry)) return false;
            TranslationUiEntry that = (TranslationUiEntry) o;
            return downloading == that.downloading
                    && Objects.equals(translatedText, that.translatedText)
                    && Objects.equals(targetLang, that.targetLang);
        }

        @Override
        public int hashCode() {
            return Objects.hash(downloading, translatedText, targetLang);
        }
    }

    @Nullable private final String meId;
    private final Consumer<MessageEntity> onFailedClick;
    @Nullable private InteractionListener interactionListener;
    @Nullable private Map<String, UserEntity> users;
    @NonNull private Map<String, AttachmentTransferEntity> transfersMap = Collections.emptyMap();
    @NonNull private Map<String, TranslationUiEntry> translationState = Collections.emptyMap();
    @NonNull private VoiceBubblePlayer.PlaybackState voiceState = VoiceBubblePlayer.PlaybackState.idle();
    private boolean group;
    private long highlighted = -1;

    public MessageAdapter(@Nullable String meId, Consumer<MessageEntity> onFailedClick) {
        super(DIFF);
        this.meId = meId;
        this.onFailedClick = onFailedClick;
    }

    public void setInteractionListener(@Nullable InteractionListener listener) {
        this.interactionListener = listener;
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

    public void setTransfersMap(@Nullable Map<String, AttachmentTransferEntity> map) {
        Map<String, AttachmentTransferEntity> next = map != null ? map : Collections.emptyMap();
        if (Objects.equals(this.transfersMap, next)) return;
        Map<String, AttachmentTransferEntity> prev = this.transfersMap;
        this.transfersMap = next;
        // Chỉ vẽ lại đúng những tin nhắn media có trạng thái/tiến độ truyền tệp thực sự thay đổi.
        for (int i = 0; i < getItemCount(); i++) {
            MessageEntity m = getItem(i);
            String type = m.getType();
            if (type == null || !SUMMARY.contains(type)) continue;
            JsonObject meta = parseJsonObject(m.getAttachmentJson());
            AttachmentTransferEntity before = resolveTransfer(m, meta, prev);
            AttachmentTransferEntity after = resolveTransfer(m, meta, next);
            if (!Objects.equals(before, after)) {
                notifyItemChanged(i, PAYLOAD_TRANSFER);
            }
        }
    }

    public void setVoicePlaybackState(@NonNull VoiceBubblePlayer.PlaybackState state) {
        String prevId = this.voiceState.clientId;
        String nextId = state.clientId;
        this.voiceState = state;
        // Chỉ cập nhật tối đa 2 dòng voice (dòng cũ & dòng mới) với payload cục bộ để không giật SeekBar.
        int prevPos = indexOfClientId(prevId);
        int nextPos = indexOfClientId(nextId);
        if (prevPos >= 0) {
            notifyItemChanged(prevPos, PAYLOAD_VOICE);
        }
        if (nextPos >= 0 && nextPos != prevPos) {
            notifyItemChanged(nextPos, PAYLOAD_VOICE);
        }
    }

    public void setTranslationState(@Nullable Map<String, TranslationUiEntry> map) {
        Map<String, TranslationUiEntry> next = map != null ? map : Collections.emptyMap();
        if (Objects.equals(this.translationState, next)) return;
        Map<String, TranslationUiEntry> prev = this.translationState;
        this.translationState = next;
        for (int i = 0; i < getItemCount(); i++) {
            MessageEntity m = getItem(i);
            String cid = m.getClientId();
            if (cid == null) continue;
            if (!Objects.equals(prev.get(cid), next.get(cid))) {
                notifyItemChanged(i, PAYLOAD_TRANSLATION);
            }
        }
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

    private int indexOfClientId(@Nullable String clientId) {
        if (clientId == null) return -1;
        for (int i = 0; i < getItemCount(); i++) {
            if (clientId.equals(getItem(i).getClientId())) return i;
        }
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
    static class SummaryOutHolder extends RecyclerView.ViewHolder {
        final ItemMessageSummaryOutBinding b;
        SummaryOutHolder(ItemMessageSummaryOutBinding b) { super(b.getRoot()); this.b = b; }
    }
    static class SummaryInHolder extends RecyclerView.ViewHolder {
        final ItemMessageSummaryInBinding b;
        SummaryInHolder(ItemMessageSummaryInBinding b) { super(b.getRoot()); this.b = b; }
    }
    static class SysHolder extends RecyclerView.ViewHolder {
        final ItemMessageSystemBinding b;
        SysHolder(ItemMessageSystemBinding b) { super(b.getRoot()); this.b = b; }
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater in = LayoutInflater.from(parent.getContext());
        if (type == TYPE_SYSTEM) return new SysHolder(ItemMessageSystemBinding.inflate(in, parent, false));
        if (type == TYPE_TEXT_OUT) return new OutHolder(ItemMessageOutBinding.inflate(in, parent, false));
        if (type == NON_TEXT_SUMMARY_OUT) return new SummaryOutHolder(ItemMessageSummaryOutBinding.inflate(in, parent, false));
        if (type == NON_TEXT_SUMMARY_IN) return new SummaryInHolder(ItemMessageSummaryInBinding.inflate(in, parent, false));
        return new InHolder(ItemMessageInBinding.inflate(in, parent, false));
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        ImageView imageView;
        if (holder instanceof SummaryOutHolder) {
            imageView = ((SummaryOutHolder) holder).b.mediaImage;
        } else if (holder instanceof SummaryInHolder) {
            imageView = ((SummaryInHolder) holder).b.mediaImage;
        } else {
            return;
        }
        // RecyclerView có thể tái chế hàng sau khi Activity bị hủy, nên hủy yêu cầu ảnh qua context ứng dụng.
        Glide.with(imageView.getContext().getApplicationContext()).clear(imageView);
    }

    @Override
    public void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        boolean handled = false;
        MessageEntity m = getItem(position);
        if (payloads.contains(PAYLOAD_TRANSLATION)) {
            if (holder instanceof OutHolder) {
                OutHolder h = (OutHolder) holder;
                bindTranslationBox(m, h.b.translationBox, h.b.translationLabel, h.b.translationHide,
                        h.b.translationProgress, h.b.translationText);
                handled = true;
            } else if (holder instanceof InHolder && getItemViewType(position) != UNKNOWN_FALLBACK) {
                InHolder h = (InHolder) holder;
                bindTranslationBox(m, h.b.translationBox, h.b.translationLabel, h.b.translationHide,
                        h.b.translationProgress, h.b.translationText);
                handled = true;
            }
        }
        if (payloads.contains(PAYLOAD_VOICE) || payloads.contains(PAYLOAD_TRANSFER)) {
            JsonObject meta = parseJsonObject(m.getAttachmentJson());
            AttachmentTransferEntity transfer = resolveTransfer(m, meta, transfersMap);
            String msgType = m.getType() != null ? m.getType() : "";
            if (holder instanceof SummaryOutHolder) {
                SummaryOutHolder h = (SummaryOutHolder) holder;
                if (payloads.contains(PAYLOAD_VOICE) && "AUDIO".equals(msgType)) {
                    updateVoicePlaybackViews(m, meta, h.b.voicePlayPause, h.b.voiceSeekbar, h.b.voiceDuration);
                }
                if (payloads.contains(PAYLOAD_TRANSFER) && ("IMAGE".equals(msgType) || "VIDEO".equals(msgType))) {
                    loadThumbnail(h.b.mediaImage, "VIDEO".equals(msgType), meta, transfer);
                }
                bindTransferProgress(m, msgType, transfer, h.b.mediaProgress);
                handled = true;
            } else if (holder instanceof SummaryInHolder) {
                SummaryInHolder h = (SummaryInHolder) holder;
                if (payloads.contains(PAYLOAD_VOICE) && "AUDIO".equals(msgType)) {
                    updateVoicePlaybackViews(m, meta, h.b.voicePlayPause, h.b.voiceSeekbar, h.b.voiceDuration);
                }
                if (payloads.contains(PAYLOAD_TRANSFER) && ("IMAGE".equals(msgType) || "VIDEO".equals(msgType))) {
                    loadThumbnail(h.b.mediaImage, "VIDEO".equals(msgType), meta, transfer);
                }
                bindTransferProgress(m, msgType, transfer, h.b.mediaProgress);
                handled = true;
            }
        }
        if (!handled) {
            super.onBindViewHolder(holder, position, payloads);
        }
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
            h.b.summary.setVisibility(View.GONE);
            h.b.text.setVisibility(View.VISIBLE);
            h.b.text.setText(m.getText());
            h.b.meta.setText(c.getString(R.string.chat_dot_pair, time, c.getString(statusLabel(m.getStatus()))));
            bindFailedRow(c, m, h.b.failed);
            bindTranslationBox(m, h.b.translationBox, h.b.translationLabel, h.b.translationHide,
                    h.b.translationProgress, h.b.translationText);

            boolean failed = "FAILED".equals(m.getStatus());
            View.OnClickListener click = failed ? v -> onFailedClick.accept(m) : null;
            h.b.bubble.setOnClickListener(click);
            h.b.failed.setOnClickListener(click);
            h.b.bubble.setClickable(failed);
            h.b.failed.setClickable(failed);
            h.b.bubble.setOnLongClickListener(v -> {
                if (interactionListener != null) {
                    interactionListener.onTextLongClick(m);
                    return true;
                }
                return false;
            });
            return;
        }

        if (holder instanceof InHolder) {
            InHolder h = (InHolder) holder;
            bindDate(h.b.date, position);
            bindSenderHeader(c, m, h.b.sender, h.b.avatar);
            h.b.meta.setText(time);
            h.b.summary.setVisibility(View.GONE);
            if (type == UNKNOWN_FALLBACK) {
                h.b.text.setText(R.string.chat_type_unknown);
                h.b.text.setTextColor(ContextCompat.getColor(c, R.color.ute_text_secondary));
                h.b.translationBox.setVisibility(View.GONE);
                h.b.bubble.setOnLongClickListener(null);
            } else {
                h.b.text.setTextColor(ContextCompat.getColor(c, R.color.ute_text));
                h.b.text.setText(m.getText());
                bindTranslationBox(m, h.b.translationBox, h.b.translationLabel, h.b.translationHide,
                        h.b.translationProgress, h.b.translationText);
                h.b.bubble.setOnLongClickListener(v -> {
                    if (interactionListener != null) {
                        interactionListener.onTextLongClick(m);
                        return true;
                    }
                    return false;
                });
            }
            return;
        }

        if (holder instanceof SummaryOutHolder) {
            SummaryOutHolder h = (SummaryOutHolder) holder;
            bindDate(h.b.date, position);
            h.b.meta.setText(c.getString(R.string.chat_dot_pair, time, c.getString(statusLabel(m.getStatus()))));
            bindFailedRow(c, m, h.b.failed);
            boolean failed = "FAILED".equals(m.getStatus());
            h.b.failed.setOnClickListener(failed ? v -> onFailedClick.accept(m) : null);
            h.b.failed.setClickable(failed);
            bindMediaSummaryBubble(c, m, true, failed,
                    h.b.bubble, h.b.mediaImageBox, h.b.mediaImage, h.b.mediaPlayBadge,
                    h.b.voiceBox, h.b.voicePlayPause, h.b.voiceSeekbar, h.b.voiceDuration,
                    h.b.summary, h.b.summaryIcon, h.b.summaryLabel, h.b.text, h.b.mediaProgress);
            return;
        }

        SummaryInHolder h = (SummaryInHolder) holder;
        bindDate(h.b.date, position);
        bindSenderHeader(c, m, h.b.sender, h.b.avatar);
        h.b.meta.setText(time);
        bindMediaSummaryBubble(c, m, false, false,
                h.b.bubble, h.b.mediaImageBox, h.b.mediaImage, h.b.mediaPlayBadge,
                h.b.voiceBox, h.b.voicePlayPause, h.b.voiceSeekbar, h.b.voiceDuration,
                h.b.summary, h.b.summaryIcon, h.b.summaryLabel, h.b.text, h.b.mediaProgress);
    }

    private void bindSenderHeader(Context c, MessageEntity m, TextView senderView, TextView avatarView) {
        UserEntity sender = users == null || m.getSenderId() == null ? null : users.get(m.getSenderId());
        String name = sender == null || sender.getDisplayName() == null ? c.getString(R.string.chat_someone) : sender.getDisplayName();
        senderView.setVisibility(group ? View.VISIBLE : View.GONE);
        senderView.setText(name);
        avatarView.setVisibility(group ? View.VISIBLE : View.GONE);
        avatarView.setText(ConversationListAdapter.initials(name));
    }

    private static void bindFailedRow(Context c, MessageEntity m, TextView failedView) {
        boolean failed = "FAILED".equals(m.getStatus());
        failedView.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (failed && m.getErrorCode() != null) {
            failedView.setText(c.getString(R.string.chat_dot_pair,
                    c.getString(ChatErrors.message(m.getErrorCode())), c.getString(R.string.chat_resend)));
        } else {
            failedView.setText(R.string.chat_status_failed);
        }
    }

    private void bindTranslationBox(
            MessageEntity m,
            View box,
            TextView label,
            TextView hideBtn,
            LinearProgressIndicator progress,
            TextView translatedText) {
        TranslationUiEntry entry = m.getClientId() != null ? translationState.get(m.getClientId()) : null;
        if (entry == null) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        if (entry.downloading) {
            // Tiến trình không xác định, tuyệt đối không hiển thị phần trăm (D-15).
            label.setText(R.string.chat_translating_download);
            hideBtn.setVisibility(View.GONE);
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
            translatedText.setVisibility(View.GONE);
        } else {
            label.setText(R.string.chat_translated_label);
            hideBtn.setVisibility(View.VISIBLE);
            hideBtn.setOnClickListener(v -> {
                if (interactionListener != null) {
                    interactionListener.onHideTranslationClick(m);
                }
            });
            progress.setVisibility(View.GONE);
            translatedText.setVisibility(View.VISIBLE);
            translatedText.setText(entry.translatedText);
        }
    }

    private void bindMediaSummaryBubble(
            Context c,
            MessageEntity m,
            boolean outgoing,
            boolean failed,
            View bubble,
            View imageBox,
            ImageView imageView,
            ImageView playBadge,
            View voiceBox,
            ImageButton voicePlayPause,
            SeekBar voiceSeekbar,
            TextView voiceDuration,
            View summaryRow,
            ImageView summaryIcon,
            TextView summaryLabel,
            TextView detailText,
            LinearProgressIndicator progressBar) {
        String msgType = m.getType() != null ? m.getType() : "";
        JsonObject meta = parseJsonObject(m.getAttachmentJson());
        AttachmentTransferEntity transfer = resolveTransfer(m, meta, transfersMap);

        // Reset toàn bộ nhóm con trước khi dựng theo từng loại cụ thể và hủy tải Glide nếu tái dùng cho loại khác.
        if (!"IMAGE".equals(msgType) && !"VIDEO".equals(msgType)) {
            Glide.with(imageView).clear(imageView);
            imageView.setBackground(null);
        }
        imageBox.setVisibility(View.GONE);
        playBadge.setVisibility(View.GONE);
        voiceBox.setVisibility(View.GONE);
        summaryRow.setVisibility(View.GONE);
        detailText.setVisibility(View.GONE);
        bubble.setOnLongClickListener(null);

        switch (msgType) {
            case "IMAGE": {
                imageBox.setVisibility(View.VISIBLE);
                loadThumbnail(imageView, false, meta, transfer);
                if (m.getText() != null && !m.getText().trim().isEmpty()) {
                    detailText.setVisibility(View.VISIBLE);
                    detailText.setText(m.getText().trim());
                }
                bubble.setOnClickListener(v -> {
                    if (failed) {
                        onFailedClick.accept(m);
                    } else if (interactionListener != null) {
                        interactionListener.onImageClick(m);
                    }
                });
                break;
            }
            case "VIDEO": {
                imageBox.setVisibility(View.VISIBLE);
                playBadge.setVisibility(View.VISIBLE);
                loadThumbnail(imageView, true, meta, transfer);
                String dur = summaryMeta("VIDEO", m.getAttachmentJson());
                String caption = m.getText() != null && !m.getText().trim().isEmpty() ? m.getText().trim() : null;
                String combined = caption != null ? (dur != null ? caption + " · " + dur : caption) : dur;
                if (combined != null) {
                    detailText.setVisibility(View.VISIBLE);
                    detailText.setText(combined);
                }
                bubble.setOnClickListener(v -> {
                    if (failed) {
                        onFailedClick.accept(m);
                    } else if (interactionListener != null) {
                        interactionListener.onVideoClick(m);
                    }
                });
                break;
            }
            case "AUDIO": {
                voiceBox.setVisibility(View.VISIBLE);
                voiceSeekbar.setTag(R.id.voice_seekbar, Boolean.FALSE);
                voiceSeekbar.setOnSeekBarChangeListener(null);
                updateVoicePlaybackViews(m, meta, voicePlayPause, voiceSeekbar, voiceDuration);

                voicePlayPause.setOnClickListener(v -> {
                    if (failed) {
                        onFailedClick.accept(m);
                    } else if (interactionListener != null) {
                        interactionListener.onVoiceToggleClick(m);
                    }
                });
                voiceSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && interactionListener != null) {
                            interactionListener.onVoiceSeek(m, progress);
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {
                        seekBar.setTag(R.id.voice_seekbar, Boolean.TRUE);
                    }
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {
                        seekBar.setTag(R.id.voice_seekbar, Boolean.FALSE);
                    }
                });
                bubble.setOnClickListener(failed ? v -> onFailedClick.accept(m) : null);
                break;
            }
            case "FILE": {
                summaryRow.setVisibility(View.VISIBLE);
                summaryIcon.setImageResource(R.drawable.ic_file);
                String fileName = str(meta, "fileName");
                summaryLabel.setText(fileName != null && !fileName.isBlank()
                        ? fileName : c.getString(R.string.chat_type_file));
                long sizeBytes = optLong(meta, "sizeBytes", transfer != null ? transfer.getSizeBytes() : 0L);
                String sizeText = sizeBytes > 0 ? formatBytes(sizeBytes) : c.getString(R.string.media_open_external);
                detailText.setVisibility(View.VISIBLE);
                detailText.setText(sizeText);
                bubble.setOnClickListener(v -> {
                    if (failed) {
                        onFailedClick.accept(m);
                    } else if (interactionListener != null) {
                        interactionListener.onFileClick(m);
                    }
                });
                break;
            }
            case "LOCATION": {
                summaryRow.setVisibility(View.VISIBLE);
                summaryIcon.setImageResource(R.drawable.ic_location);
                summaryLabel.setText(R.string.chat_type_location);
                String coords = summaryMeta("LOCATION", m.getAttachmentJson());
                detailText.setVisibility(View.VISIBLE);
                detailText.setText(coords != null
                        ? coords + " · " + c.getString(R.string.media_open_maps)
                        : c.getString(R.string.media_open_maps));
                bubble.setOnClickListener(v -> {
                    if (failed) {
                        onFailedClick.accept(m);
                    } else if (interactionListener != null) {
                        interactionListener.onLocationClick(m);
                    }
                });
                break;
            }
            case "CALL":
            default: {
                summaryRow.setVisibility(View.VISIBLE);
                summaryIcon.setImageResource(R.drawable.ic_phone);
                summaryLabel.setText(R.string.chat_type_call);
                String callDetail = formatCallSummary(c, meta);
                if (callDetail != null) {
                    detailText.setVisibility(View.VISIBLE);
                    detailText.setText(callDetail);
                }
                bubble.setOnClickListener(failed ? v -> onFailedClick.accept(m) : null);
                break;
            }
        }

        bindTransferProgress(m, msgType, transfer, progressBar);
    }

    private void updateVoicePlaybackViews(
            MessageEntity m,
            @Nullable JsonObject meta,
            ImageButton voicePlayPause,
            SeekBar voiceSeekbar,
            TextView voiceDuration) {
        boolean isCurrent = m.getClientId() != null && m.getClientId().equals(voiceState.clientId);
        boolean isPlaying = isCurrent && voiceState.playing;
        voicePlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        int metaDuration = optInt(meta, "durationMs", 0);
        int totalDuration = isCurrent && voiceState.durationMs > 0 ? voiceState.durationMs : metaDuration;
        int currentPos = isCurrent ? voiceState.positionMs : 0;
        int safeMax = Math.max(1, totalDuration);
        if (voiceSeekbar.getMax() != safeMax) {
            voiceSeekbar.setMax(safeMax);
        }
        // Không ghi đè vị trí thanh tua khi người dùng đang kéo tay trên SeekBar.
        if (!Boolean.TRUE.equals(voiceSeekbar.getTag(R.id.voice_seekbar))) {
            voiceSeekbar.setProgress(Math.min(currentPos, safeMax));
        }
        voiceDuration.setText(formatDurationMs(isCurrent && currentPos > 0 ? currentPos : totalDuration));
    }

    private void bindTransferProgress(
            MessageEntity m,
            String msgType,
            @Nullable AttachmentTransferEntity transfer,
            LinearProgressIndicator progressBar) {
        boolean inFlightTransfer = transfer != null
                && ("QUEUED".equals(transfer.getState())
                || "UPLOADING".equals(transfer.getState())
                || "DOWNLOADING".equals(transfer.getState()));
        boolean voiceLoading = "AUDIO".equals(msgType) && m.getClientId() != null
                && m.getClientId().equals(voiceState.clientId) && voiceState.loading;
        if (inFlightTransfer || voiceLoading) {
            progressBar.setVisibility(View.VISIBLE);
            int pct = transfer != null ? transfer.getProgress() : 0;
            if (voiceLoading || pct <= 0) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(Math.min(100, pct));
            }
        } else {
            progressBar.setVisibility(View.GONE);
        }
    }

    @Nullable
    private static AttachmentTransferEntity resolveTransfer(
            MessageEntity m, @Nullable JsonObject meta, Map<String, AttachmentTransferEntity> map) {
        if (m.getClientId() != null) {
            AttachmentTransferEntity up = map.get(m.getClientId());
            if (up != null) return up;
        }
        String mediaUrl = str(meta, "mediaUrl");
        if (mediaUrl != null) {
            int slash = mediaUrl.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < mediaUrl.length()) {
                return map.get("dl:" + mediaUrl.substring(slash + 1));
            }
        }
        return null;
    }

    private static void loadThumbnail(
            ImageView target, boolean isVideo, @Nullable JsonObject meta, @Nullable AttachmentTransferEntity transfer) {
        String localPath = str(meta, "localPath");
        if (localPath == null && transfer != null) {
            localPath = transfer.getLocalPath();
        }
        File localFile = localPath != null ? new File(localPath) : null;
        if (localFile != null && localFile.isFile()) {
            target.setBackground(null);
            Glide.with(target)
                    .load(localFile)
                    .centerCrop()
                    .placeholder(R.drawable.ic_camera)
                    .error(R.drawable.ic_camera)
                    .into(target);
            return;
        }
        if (isVideo) {
            // Video từ xa chưa tải về cache: hiển thị poster tối + nút phát thay vì kéo nguyên tệp tối đa 50MB qua Glide.
            Glide.with(target).clear(target);
            target.setImageDrawable(null);
            target.setBackgroundColor(VIDEO_REMOTE_POSTER_COLOR);
            return;
        }
        target.setBackground(null);
        String mediaUrl = str(meta, "mediaUrl");
        if (mediaUrl == null && transfer != null) {
            mediaUrl = transfer.getMediaUrl();
        }
        if (mediaUrl != null && !mediaUrl.trim().isEmpty()) {
            try {
                String resolved = ApiClient.resolveBackendMediaUrl(BuildConfig.BACKEND_BASE_URL, mediaUrl);
                Glide.with(target)
                        .load(resolved)
                        .centerCrop()
                        .placeholder(R.drawable.ic_camera)
                        .error(R.drawable.ic_camera)
                        .into(target);
                return;
            } catch (IllegalArgumentException ignored) {
            }
        }
        Glide.with(target).clear(target);
        target.setImageResource(R.drawable.ic_camera);
    }

    @Nullable
    private static String formatCallSummary(Context c, @Nullable JsonObject meta) {
        if (meta == null || !meta.has("call") || !meta.get("call").isJsonObject()) {
            return null;
        }
        JsonObject call = meta.getAsJsonObject("call");
        String state = str(call, "state");
        if ("MISSED".equalsIgnoreCase(state)) return c.getString(R.string.call_status_no_answer);
        if ("REJECTED".equalsIgnoreCase(state)) return c.getString(R.string.call_status_rejected);
        if ("BUSY".equalsIgnoreCase(state)) return c.getString(R.string.call_status_busy);
        if ("FAILED".equalsIgnoreCase(state)) return c.getString(R.string.call_status_offline);
        if ("ENDED".equalsIgnoreCase(state)) return c.getString(R.string.call_status_ended);
        return null;
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

    private static String formatDurationMs(int ms) {
        long totalSec = Math.max(0, ms / 1000L);
        return String.format(Locale.ROOT, "%d:%02d", totalSec / 60L, totalSec % 60L);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Nullable
    static JsonObject parseJsonObject(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement e = JsonParser.parseString(json);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Chi tiết hiển thị được của tin không phải chữ: tên file, thời lượng mm:ss, tọa độ làm tròn.
     * Chỉ đọc chữ/số; JSON hỏng trả null.
     */
    @Nullable static String summaryMeta(@Nullable String type, @Nullable String json) {
        if (type == null || json == null || json.isBlank()) return null;
        JsonObject o = parseJsonObject(json);
        if (o == null) return null;
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
            return null;
        }
    }

    @Nullable static String str(@Nullable JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int optInt(@Nullable JsonObject o, String key, int fallback) {
        if (o == null) return fallback;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsInt(); } catch (RuntimeException ex) { return fallback; }
    }

    private static long optLong(@Nullable JsonObject o, String key, long fallback) {
        if (o == null) return fallback;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsLong(); } catch (RuntimeException ex) { return fallback; }
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
