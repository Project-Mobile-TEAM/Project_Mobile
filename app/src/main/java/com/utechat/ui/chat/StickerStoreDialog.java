package com.utechat.ui.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.utechat.R;
import com.utechat.data.local.entity.StickerPackEntity;
import com.utechat.databinding.DialogStickerStoreBinding;
import com.utechat.databinding.ItemStickerStorePackBinding;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Cửa hàng cập nhật theo trạng thái bộ nhãn dán của tài khoản hiện tại. */
public final class StickerStoreDialog {
    private StickerStoreDialog() {}

    public static BottomSheetDialog show(
            Context context, LifecycleOwner owner, LiveData<List<StickerPackEntity>> packs,
            Consumer<String> install, Consumer<String> uninstall) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        DialogStickerStoreBinding binding = DialogStickerStoreBinding.inflate(
                LayoutInflater.from(dialog.getContext()));
        dialog.setContentView(binding.getRoot());
        PacksAdapter adapter = new PacksAdapter(install, uninstall);
        binding.stickerStoreList.setLayoutManager(new LinearLayoutManager(context));
        binding.stickerStoreList.setAdapter(adapter);

        // Tháo observer khi đóng cửa hàng để mỗi lần mở chỉ có một danh sách nhận cập nhật.
        Observer<List<StickerPackEntity>> observer = rows -> {
            List<StickerPackEntity> snapshot = rows == null ? List.of() : List.copyOf(rows);
            binding.stickerStoreEmpty.setVisibility(snapshot.isEmpty() ? View.VISIBLE : View.GONE);
            binding.stickerStoreList.setVisibility(snapshot.isEmpty() ? View.GONE : View.VISIBLE);
            adapter.submitList(snapshot);
        };
        packs.observe(owner, observer);
        dialog.setOnDismissListener(ignored -> packs.removeObserver(observer));
        dialog.show();
        return dialog;
    }

    private static final class PacksAdapter extends ListAdapter<StickerPackEntity, PackHolder> {
        private final Consumer<String> install;
        private final Consumer<String> uninstall;

        PacksAdapter(Consumer<String> install, Consumer<String> uninstall) {
            super(new DiffUtil.ItemCallback<>() {
                @Override public boolean areItemsTheSame(@NonNull StickerPackEntity a, @NonNull StickerPackEntity b) {
                    return a.getPackId().equals(b.getPackId());
                }
                @Override public boolean areContentsTheSame(@NonNull StickerPackEntity a, @NonNull StickerPackEntity b) {
                    return a.getInstalled() == b.getInstalled() && a.getName().equals(b.getName())
                            && a.getThumbnailUrl().equals(b.getThumbnailUrl())
                            && Objects.equals(a.getDescription(), b.getDescription());
                }
            });
            this.install = install;
            this.uninstall = uninstall;
        }

        @NonNull @Override public PackHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new PackHolder(ItemStickerStorePackBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override public void onBindViewHolder(@NonNull PackHolder holder, int index) {
            StickerPackEntity pack = getItem(index);
            ItemStickerStorePackBinding binding = holder.binding;
            binding.packName.setText(pack.getName());
            String description = pack.getDescription();
            binding.packDescription.setText(description);
            binding.packDescription.setVisibility(description == null || description.isBlank() ? View.GONE : View.VISIBLE);
            binding.packInstalled.setVisibility(pack.getInstalled() ? View.VISIBLE : View.GONE);
            StickerTrayView.loadStickerImage(binding.packThumbnail, null, pack.getThumbnailUrl());
            binding.packAction.setText(pack.getInstalled() ? R.string.sticker_store_uninstall : R.string.sticker_store_install);
            binding.packAction.setOnClickListener(view -> {
                Consumer<String> action = pack.getInstalled() ? uninstall : install;
                action.accept(pack.getPackId());
            });
        }

        @Override public void onViewRecycled(@NonNull PackHolder holder) {
            Glide.with(holder.itemView.getContext().getApplicationContext()).clear(holder.binding.packThumbnail);
            super.onViewRecycled(holder);
        }
    }

    private static final class PackHolder extends RecyclerView.ViewHolder {
        final ItemStickerStorePackBinding binding;

        PackHolder(ItemStickerStorePackBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
