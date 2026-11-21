package com.utechat.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.databinding.ItemMemberSelectBinding;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Danh sách bạn bè dùng chung: Tin nhắn mới (chạm để mở chat) và Tạo nhóm (selectable = true, chạm để chọn).
 */
public class MemberSelectAdapter extends ListAdapter<UserEntity, MemberSelectAdapter.Holder> {
    private final boolean selectable;
    private final Consumer<UserEntity> onClick;
    private final Set<String> selected = new LinkedHashSet<>();

    public MemberSelectAdapter(boolean selectable, Consumer<UserEntity> onClick) {
        super(DIFF);
        this.selectable = selectable;
        this.onClick = onClick;
    }

    /** Id đã chọn theo thứ tự chọn (Tạo nhóm gửi danh sách này lên server). */
    public Set<String> getSelected() { return Set.copyOf(selected); }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemMemberSelectBinding b;
        Holder(ItemMemberSelectBinding b) { super(b.getRoot()); this.b = b; }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(ItemMemberSelectBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        UserEntity u = getItem(position);
        String name = u.getDisplayName() == null ? "" : u.getDisplayName();
        h.b.name.setText(name);
        h.b.detail.setText(u.getPhone());
        h.b.avatar.setText(ConversationListAdapter.initials(name));
        h.b.check.setVisibility(selectable ? View.VISIBLE : View.GONE);
        h.b.check.setChecked(selected.contains(u.getId()));
        h.itemView.setOnClickListener(v -> {
            if (selectable && !selected.remove(u.getId())) selected.add(u.getId());
            if (selectable) notifyItemChanged(h.getAdapterPosition());
            onClick.accept(u);
        });
    }

    private static final DiffUtil.ItemCallback<UserEntity> DIFF = new DiffUtil.ItemCallback<>() {
        @Override public boolean areItemsTheSame(@NonNull UserEntity a, @NonNull UserEntity b) { return a.getId().equals(b.getId()); }
        @Override public boolean areContentsTheSame(@NonNull UserEntity a, @NonNull UserEntity b) {
            return Objects.equals(a.getDisplayName(), b.getDisplayName()) && Objects.equals(a.getPhone(), b.getPhone());
        }
    };
}
