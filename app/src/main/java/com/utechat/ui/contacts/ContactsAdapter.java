package com.utechat.ui.contacts;

import android.view.*;
import android.widget.PopupMenu;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.*;
import com.utechat.R;
import com.utechat.databinding.ItemContactBinding;
import com.utechat.data.remote.model.ContactDtos.User;
import java.util.*;

/** Một kiểu thẻ dùng chung ba danh sách; DiffUtil so sánh cả tên và các thao tác được phép. */
public class ContactsAdapter extends ListAdapter<ContactsAdapter.Row,ContactsAdapter.Holder> {
    public record Row(String key,String name,String detail,String target,User user,List<String> actions) {
        public Row {
            // Giữ bản sao payload click; caller sửa DTO gốc không làm menu đang mở đổi mục tiêu.
            if(user!=null) { User copy=new User();copy.id=user.id;copy.displayName=user.displayName;copy.avatarUrl=user.avatarUrl;user=copy; }
            actions=List.copyOf(actions);
        }
    }
    public interface Action { void run(String action,String target,User user); }
    private final Action listener;
    private boolean actionsEnabled=true;
    public void setActionsEnabled(boolean value) {
        if(actionsEnabled==value) return;
        actionsEnabled=value;notifyItemRangeChanged(0,getItemCount(),"enabled");
    }
    public ContactsAdapter(Action listener) {
        super(new DiffUtil.ItemCallback<>() {
            public boolean areItemsTheSame(@NonNull Row a,@NonNull Row b) { return a.key().equals(b.key()); }
            public boolean areContentsTheSame(@NonNull Row a,@NonNull Row b) {
                return Objects.equals(a.name(),b.name())&&Objects.equals(a.detail(),b.detail())&&Objects.equals(a.target(),b.target())
                        &&Objects.equals(a.user()==null?null:a.user().id,b.user()==null?null:b.user().id)
                        &&Objects.equals(a.user()==null?null:a.user().avatarUrl,b.user()==null?null:b.user().avatarUrl)&&a.actions().equals(b.actions());
            }
        }); this.listener=listener;
    }
    public static int label(String action) {
        switch(action) {
            case "send": return R.string.contacts_send;
            case "accept": return R.string.contacts_accept;
            case "reject": return R.string.contacts_reject;
            case "cancel": return R.string.contacts_cancel;
            case "block": return R.string.contacts_block;
            case "unblock": return R.string.contacts_unblock;
            case "unfriend": return R.string.contacts_unfriend;
            default: throw new IllegalArgumentException("Thao tác không hợp lệ");
        }
    }
    static class Holder extends RecyclerView.ViewHolder {
        final ItemContactBinding b;
        Holder(ItemContactBinding b) { super(b.getRoot()); this.b=b; }
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type) {
        return new Holder(ItemContactBinding.inflate(LayoutInflater.from(parent.getContext()),parent,false));
    }
    @Override public void onBindViewHolder(@NonNull Holder h,int position) {
        Row r=getItem(position); h.b.name.setText(r.name()); h.b.detail.setText(r.detail());
        h.b.avatar.setText(r.name()==null||r.name().isBlank()?"?":r.name().substring(0,r.name().offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT));
        h.b.actions.setEnabled(actionsEnabled);
        h.b.actions.setContentDescription(h.b.getRoot().getContext().getString(R.string.contacts_actions_for,r.name()));
        h.b.actions.setOnClickListener(v->{
            if(!actionsEnabled) return;
            // RecyclerView hiện có dùng getAdapterPosition; luôn đọc lại sau mỗi diff trước khi gửi thao tác.
            int positionNow=h.getAdapterPosition();if(positionNow==RecyclerView.NO_POSITION) return;
            Row selected=getItem(positionNow);
            PopupMenu menu=new PopupMenu(v.getContext(),v);
            for(int i=0;i<selected.actions().size();i++) menu.getMenu().add(0,i,i,label(selected.actions().get(i)));
            menu.setOnMenuItemClickListener(item->{
                int current=h.getAdapterPosition();if(!actionsEnabled||current==RecyclerView.NO_POSITION) return false;
                Row latest=getItem(current);String action=selected.actions().get(item.getItemId());
                if(!selected.key().equals(latest.key())||!latest.actions().contains(action)) return false;
                listener.run(action,"block".equals(action)&&latest.user()!=null?latest.user().id:latest.target(),latest.user());return true;
            });
            menu.show();
        });
    }
}
