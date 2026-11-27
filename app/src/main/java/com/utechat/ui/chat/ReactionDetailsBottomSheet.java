package com.utechat.ui.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.utechat.R;
import com.utechat.data.local.entity.MessageReactionEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.databinding.DialogReactionDetailsBinding;
import com.utechat.databinding.ItemReactionDetailBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chi tiết người đã bày tỏ cảm xúc, có thể lọc theo từng emoji. */
public final class ReactionDetailsBottomSheet {
    private ReactionDetailsBottomSheet() {}

    public static BottomSheetDialog show(
            @NonNull Context context, @NonNull List<MessageReactionEntity> reactions,
            @NonNull Map<String, UserEntity> users, @Nullable String meId) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        DialogReactionDetailsBinding binding = DialogReactionDetailsBinding.inflate(
                LayoutInflater.from(dialog.getContext()));
        dialog.setContentView(binding.getRoot());
        ReactionAdapter adapter = new ReactionAdapter(users, meId);
        binding.reactionList.setLayoutManager(new LinearLayoutManager(context));
        binding.reactionList.setAdapter(adapter);

        List<MessageReactionEntity> all = List.copyOf(reactions);
        addFilter(binding.reactionFilters,
                context.getString(R.string.chat_reactions_all, all.size()), all, adapter).setChecked(true);
        for (String emoji : MessageActionBottomSheet.FIXED_EMOJIS) {
            List<MessageReactionEntity> matching = new ArrayList<>();
            for (MessageReactionEntity reaction : all) {
                if (emoji.equals(reaction.getEmoji())) matching.add(reaction);
            }
            if (!matching.isEmpty()) {
                addFilter(binding.reactionFilters,
                        context.getString(R.string.chat_reaction_option_count, emoji, matching.size()),
                        matching, adapter);
            }
        }
        adapter.show(all);
        dialog.show();
        return dialog;
    }

    private static Chip addFilter(ViewGroup filters, String label, List<MessageReactionEntity> rows,
                                  ReactionAdapter adapter) {
        Chip chip = new Chip(filters.getContext());
        chip.setEnsureMinTouchTargetSize(true);
        chip.setCheckable(true);
        chip.setText(label);
        chip.setOnClickListener(view -> {
            // Chỉ một bộ lọc được chọn tại một thời điểm.
            for (int i = 0; i < filters.getChildCount(); i++) {
                Chip candidate = (Chip) filters.getChildAt(i);
                candidate.setChecked(candidate == chip);
            }
            adapter.show(rows);
        });
        filters.addView(chip);
        return chip;
    }

    private static final class ReactionAdapter extends RecyclerView.Adapter<ReactionHolder> {
        private final Map<String, UserEntity> users;
        @Nullable private final String meId;
        private List<MessageReactionEntity> rows = List.of();

        ReactionAdapter(Map<String, UserEntity> users, @Nullable String meId) {
            this.users = users;
            this.meId = meId;
        }

        void show(List<MessageReactionEntity> next) {
            // Tối đa một dòng cho mỗi thành viên. Thay danh sách khi đổi tab lọc.
            int oldCount = rows.size();
            rows = List.of();
            notifyItemRangeRemoved(0, oldCount);
            rows = next;
            notifyItemRangeInserted(0, rows.size());
        }

        @NonNull @Override public ReactionHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new ReactionHolder(ItemReactionDetailBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override public void onBindViewHolder(@NonNull ReactionHolder holder, int index) {
            MessageReactionEntity reaction = rows.get(index);
            Context context = holder.itemView.getContext();
            UserEntity user = users.get(reaction.getUserId());
            String name = user == null ? null : user.getDisplayName();
            if (reaction.getUserId().equals(meId)) {
                name = context.getString(R.string.chat_you);
            } else if (name == null || name.isBlank()) {
                name = context.getString(R.string.chat_someone);
            }
            holder.binding.reactionName.setText(name);
            holder.binding.reactionAvatar.setText(
                    name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault()));
            holder.binding.reactionEmoji.setText(reaction.getEmoji());
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private static final class ReactionHolder extends RecyclerView.ViewHolder {
        final ItemReactionDetailBinding binding;

        ReactionHolder(ItemReactionDetailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
