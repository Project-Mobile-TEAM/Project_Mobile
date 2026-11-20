package com.utechat.ui.contacts;

import android.view.*;
import android.widget.Button;
import android.widget.PopupMenu;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.*;
import com.utechat.R;
import com.utechat.databinding.ItemContactRowBinding;
import java.util.Objects;

/** Hàng danh bạ dạng chip theo Figma; dùng chung Row và Action với ContactsAdapter. Thao tác "block" không có chip. */
public class ContactRowsAdapter extends ListAdapter<ContactsAdapter.Row,ContactRowsAdapter.Holder> {
    private static final DiffUtil.ItemCallback<ContactsAdapter.Row> DIFF=new DiffUtil.ItemCallback<>() {
        public boolean areItemsTheSame(@NonNull ContactsAdapter.Row a,@NonNull ContactsAdapter.Row b) { return a.key().equals(b.key()); }
        public boolean areContentsTheSame(@NonNull ContactsAdapter.Row a,@NonNull ContactsAdapter.Row b) {
            return Objects.equals(a.name(),b.name())&&Objects.equals(a.detail(),b.detail())&&Objects.equals(a.target(),b.target())
                    &&a.actions().equals(b.actions())&&Objects.equals(a.user()==null?null:a.user().id,b.user()==null?null:b.user().id);
        }
    };
    private final ContactsAdapter.Action listener;
    private boolean actionsEnabled=true;

    public ContactRowsAdapter(ContactsAdapter.Action listener) { super(DIFF);this.listener=listener; }

    public void setActionsEnabled(boolean value) {
        if(actionsEnabled==value) return;
        actionsEnabled=value;notifyItemRangeChanged(0,getItemCount());
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemContactRowBinding b;
        Holder(ItemContactRowBinding b) { super(b.getRoot());this.b=b; }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type) {
        return new Holder(ItemContactRowBinding.inflate(LayoutInflater.from(parent.getContext()),parent,false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h,int position) {
        ContactsAdapter.Row r=getItem(position);
        h.b.avatar.setText(ContactsText.initials(r.name()));
        h.b.name.setText(r.name());
        h.b.detail.setText(r.detail());
        h.b.detail.setVisibility(r.detail()==null||r.detail().isBlank()?View.GONE:View.VISIBLE);
        // Chip đặc cho thao tác chính, chip xám cho từ chối, chip viền cho thao tác đơn (hủy/bỏ chặn/hủy kết bạn).
        bind(h,r,h.b.chipFilled,pick(r,"accept","send"));
        bind(h,r,h.b.chipTonal,pick(r,"reject"));
        bind(h,r,h.b.chipOutlined,pick(r,"cancel","unblock","unfriend"));
        // Chặn luôn nhắm ID người dùng, kể cả hàng đang mang ID lời mời để accept/cancel.
        h.b.actions.setVisibility(r.actions().contains("block")?View.VISIBLE:View.GONE);
        h.b.actions.setEnabled(actionsEnabled);
        h.b.actions.setContentDescription(h.b.getRoot().getContext().getString(R.string.contacts_actions_for,r.name()));
        h.b.actions.setOnClickListener(v->{
            if(!actionsEnabled) return;
            PopupMenu menu=new PopupMenu(v.getContext(),v);menu.getMenu().add(R.string.contacts_block);
            menu.setOnMenuItemClickListener(item->{
                int now=h.getAdapterPosition();if(!actionsEnabled||now==RecyclerView.NO_POSITION) return false;
                ContactsAdapter.Row latest=getItem(now);
                if(!r.key().equals(latest.key())||latest.user()==null||!latest.actions().contains("block")) return false;
                listener.run("block",latest.user().id,latest.user());return true;
            });
            menu.show();
        });
    }

    private static String pick(ContactsAdapter.Row r,String... actions) {
        for(String action:actions) if(r.actions().contains(action)) return action;
        return null;
    }

    private void bind(Holder h,ContactsAdapter.Row r,Button chip,String action) {
        chip.setVisibility(action==null?View.GONE:View.VISIBLE);
        if(action==null) return;
        chip.setText("reject".equals(action)?R.string.contacts_dismiss:ContactsAdapter.label(action));
        // Chữ chip ngắn ("Xóa"); TalkBack đọc đủ hành động và tên người.
        chip.setContentDescription(chip.getContext().getString(R.string.contacts_chip_desc,
                chip.getContext().getString(ContactsAdapter.label(action)),r.name()));
        chip.setEnabled(actionsEnabled);
        chip.setOnClickListener(v->{
            int now=h.getAdapterPosition();
            if(!actionsEnabled||now==RecyclerView.NO_POSITION) return;
            // Đọc lại hàng sau mỗi lần diff để không gửi thao tác cho mục tiêu cũ.
            ContactsAdapter.Row latest=getItem(now);
            if(latest.actions().contains(action)) listener.run(action,latest.target(),latest.user());
        });
    }
}
