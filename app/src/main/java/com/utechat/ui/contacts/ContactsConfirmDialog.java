package com.utechat.ui.contacts;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.utechat.R;
import com.utechat.data.remote.model.ContactDtos.User;

/** DialogFragment giữ xác nhận qua recreate, không giữ binding hoặc callback vào View cũ. */
public class ContactsConfirmDialog extends DialogFragment {
    public static ContactsConfirmDialog create(String action,String id,User user) {
        Bundle args=new Bundle();args.putString("action",action);args.putString("id",id);args.putSerializable("user",user);
        ContactsConfirmDialog dialog=new ContactsConfirmDialog();dialog.setArguments(args);return dialog;
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        Bundle args=requireArguments();String action=args.getString("action"),id=args.getString("id");
        // Payload chỉ do Fragment trong app tạo, không đọc token hoặc nội dung từ Intent ngoài.
        User user=(User)args.getSerializable("user");
        String name=user==null||user.displayName==null?getString(R.string.contacts_unknown_name):user.displayName;
        return new MaterialAlertDialogBuilder(requireContext()).setTitle(ContactsAdapter.label(action))
                .setMessage(getString(R.string.contacts_confirm_person,name)).setNegativeButton(R.string.contacts_no,null)
                .setPositiveButton(ContactsAdapter.label(action),(dialog,which)->ContactsViewModel.of(this).act(action,id,user)).create();
    }
}
