package com.utechat.ui.contacts;

import android.text.*;
import android.view.View;
import androidx.fragment.app.Fragment;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.data.remote.model.ContactDtos.User;
import com.utechat.data.repository.ContactsResult;

/** Tiện ích giao diện chung: dịch lỗi, xác nhận thao tác và theo dõi ô nhập. */
final class ContactsUi {
    private ContactsUi() { }
    static TextWatcher watcher(java.util.function.Consumer<String> changed) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { changed.accept(s.toString()); }
            public void afterTextChanged(Editable e) { }
        };
    }
    static void act(Fragment f,ContactsViewModel vm,String action,String id,User profile) {
        if(!vm.canAct()) return;
        if(java.util.List.of("block","unblock","unfriend").contains(action)) {
            String tag="contacts.confirm";
            if(f.getChildFragmentManager().findFragmentByTag(tag)==null)
                ContactsConfirmDialog.create(action,id,profile).showNow(f.getChildFragmentManager(),tag);
        }
        else vm.act(action,id,profile);
    }
    static String message(Fragment f,ContactsResult<?> r) {
        int label=R.string.contacts_error;
        if(r.isSuccess()) label=r.refreshRequired?R.string.contacts_stale:R.string.contacts_done;
        else if(r.refreshRequired) label=R.string.contacts_uncertain;
        else switch(r.errorCode==null?"":r.errorCode) {
            case "NETWORK": label=R.string.contacts_network; break;
            case "PHONE_INVALID": label=R.string.contacts_invalid; break;
            case "SELF": label=R.string.contacts_self; break;
            case "NOT_FOUND": label=R.string.contacts_not_found; break;
            case "FORBIDDEN": case "BLOCKED": case "UNAUTHENTICATED": label=R.string.contacts_forbidden; break;
            case "REQUEST_ALREADY_PENDING": case "INVALID_STATE": case "ALREADY_FRIENDS": label=R.string.contacts_conflict; break;
            case "CACHE_WRITE_FAILED": case "STALE_RESULT": label=R.string.contacts_cache_error; break;
        }
        return "RATE_LIMITED".equals(r.errorCode)?f.getString(R.string.contacts_limited,r.retryAfterSeconds):f.getString(label);
    }
    static Snackbar notice(Fragment f,View root,ContactsResult<?> r,Runnable retry) {
        // Fragment có thể phát cache trước khi gắn đủ ancestor; content Activity nhận Snackbar, không phải FragmentContainerView.
        Snackbar bar=Snackbar.make(f.requireActivity().findViewById(android.R.id.content),message(f,r),Snackbar.LENGTH_LONG);
        if(r.refreshRequired||java.util.List.of("NETWORK","CACHE_WRITE_FAILED","STALE_RESULT","REQUEST_ALREADY_PENDING","INVALID_STATE","ALREADY_FRIENDS").contains(r.errorCode==null?"":r.errorCode))
            bar.setAction(R.string.contacts_retry,v->retry.run());
        bar.show();return bar;
    }
}
