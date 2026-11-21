package com.utechat.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.utechat.R;
import com.utechat.databinding.ItemGroupMemberBinding;
import java.util.function.BiConsumer;

/** Thành viên đang ở trong nhóm; nút ba chấm chỉ hiện khi mình là admin và dòng đó không phải mình. */
public class GroupMembersAdapter extends ListAdapter<GroupMembersAdapter.Row, GroupMembersAdapter.Holder> {
    public record Row(String userId, String name, boolean admin, boolean actions) { }

    private final BiConsumer<Row, View> onActions;

    public GroupMembersAdapter(BiConsumer<Row, View> onActions) {
        super(DIFF);
        this.onActions = onActions;
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemGroupMemberBinding b;
        Holder(ItemGroupMemberBinding b) { super(b.getRoot()); this.b = b; }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(ItemGroupMemberBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        Row r = getItem(position);
        h.b.name.setText(r.name());
        h.b.avatar.setText(ConversationListAdapter.initials(r.name()));
        h.b.role.setVisibility(r.admin() ? View.VISIBLE : View.GONE);
        h.b.actions.setVisibility(r.actions() ? View.VISIBLE : View.GONE);
        h.b.actions.setContentDescription(h.itemView.getContext().getString(R.string.group_member_actions, r.name()));
        h.b.actions.setOnClickListener(v -> onActions.accept(r, v));
    }

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<>() {
        @Override public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) { return a.userId().equals(b.userId()); }
        @Override public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) { return a.equals(b); }
    };
}
