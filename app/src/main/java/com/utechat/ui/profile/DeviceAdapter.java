package com.utechat.ui.profile;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.utechat.R;
import com.utechat.data.repository.DeviceInfo;
import com.utechat.databinding.ItemDeviceBinding;

/** Danh sách thiết bị: ListAdapter + DiffUtil so theo id (slide Session 3), không dùng notifyDataSetChanged. */
public class DeviceAdapter extends ListAdapter<DeviceInfo, DeviceAdapter.Holder> {
    /** Người dùng bấm "Thu hồi" ở một dòng. */
    public interface OnRevokeListener { void onRevoke(DeviceInfo device); }

    private final OnRevokeListener listener;

    public DeviceAdapter(OnRevokeListener listener) {
        super(new DiffUtil.ItemCallback<DeviceInfo>() {
            @Override public boolean areItemsTheSame(@NonNull DeviceInfo a, @NonNull DeviceInfo b) { return a.id.equals(b.id); }
            @Override public boolean areContentsTheSame(@NonNull DeviceInfo a, @NonNull DeviceInfo b) {
                return a.name.equals(b.name) && a.osVersion.equals(b.osVersion)
                        && a.lastSeenAtMillis == b.lastSeenAtMillis && a.current == b.current;
            }
        });
        this.listener = listener;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemDeviceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        DeviceInfo device = getItem(position);
        ItemDeviceBinding b = holder.binding;
        b.tvName.setText(device.name);
        if (device.current) {
            // Thiết bị đang dùng: mô tả màu xanh lá, không có nút thu hồi.
            b.tvDetail.setText(b.getRoot().getContext().getString(R.string.profile_device_current, device.osVersion));
            b.tvDetail.setTextColor(ContextCompat.getColor(b.getRoot().getContext(), R.color.ute_success));
        } else {
            CharSequence ago = DateUtils.getRelativeTimeSpanString(device.lastSeenAtMillis,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            b.tvDetail.setText(b.getRoot().getContext().getString(R.string.profile_device_last_seen, device.osVersion, ago));
            // Trả về màu chữ phụ của theme (dòng có thể là view tái sử dụng từ thiết bị hiện tại màu xanh).
            b.tvDetail.setTextColor(MaterialColors.getColor(b.tvDetail, com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
        b.btnRevoke.setVisibility(device.current ? View.GONE : View.VISIBLE);
        b.btnRevoke.setOnClickListener(v -> listener.onRevoke(device));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemDeviceBinding binding;
        Holder(ItemDeviceBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
