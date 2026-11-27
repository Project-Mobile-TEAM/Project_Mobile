package com.utechat.ui.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.chip.Chip;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.databinding.ItemStickerGridBinding;
import com.utechat.databinding.ViewStickerTrayBinding;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Điều khiển khay nhãn dán bằng dữ liệu từ Room và ảnh đã tải trong bộ nhớ riêng của app. */
public final class StickerTrayView {
    private final ViewStickerTrayBinding binding;
    private final StickerGridAdapter adapter;
    private List<StickerPackEntity> packs = List.of();
    private List<StickerEntity> stickers = List.of();
    @Nullable private String selectedPackId;

    public StickerTrayView(ViewStickerTrayBinding binding, Consumer<StickerEntity> select, Runnable openStore) {
        this.binding = binding;
        this.adapter = new StickerGridAdapter(select);
        binding.stickerGrid.setLayoutManager(new GridLayoutManager(binding.getRoot().getContext(), 4));
        binding.stickerGrid.setAdapter(adapter);
        binding.stickerStoreOpen.setOnClickListener(view -> openStore.run());
        setOpen(false);
        renderGrid();
    }

    public boolean isOpen() { return binding.getRoot().getVisibility() == View.VISIBLE; }

    public void setOpen(boolean open) {
        binding.getRoot().setVisibility(open ? View.VISIBLE : View.GONE);
    }

    public void setPacks(@Nullable List<StickerPackEntity> installedPacks) {
        packs = installedPacks == null ? List.of() : List.copyOf(installedPacks);
        boolean selected = packs.stream().anyMatch(pack -> pack.getPackId().equals(selectedPackId));
        if (!selected) selectedPackId = packs.isEmpty() ? null : packs.get(0).getPackId();

        binding.stickerTabs.removeAllViews();
        for (StickerPackEntity pack : packs) {
            Chip chip = new Chip(binding.getRoot().getContext());
            chip.setEnsureMinTouchTargetSize(true);
            chip.setCheckable(true);
            chip.setText(pack.getName());
            chip.setChecked(pack.getPackId().equals(selectedPackId));
            chip.setOnClickListener(view -> {
                selectedPackId = pack.getPackId();
                for (int i = 0; i < binding.stickerTabs.getChildCount(); i++) {
                    Chip candidate = (Chip) binding.stickerTabs.getChildAt(i);
                    candidate.setChecked(candidate == chip);
                }
                renderGrid();
                binding.stickerGrid.scrollToPosition(0);
            });
            binding.stickerTabs.addView(chip);
        }
        renderGrid();
    }

    public void setStickers(@Nullable List<StickerEntity> installedStickers) {
        stickers = installedStickers == null ? List.of() : List.copyOf(installedStickers);
        renderGrid();
    }

    private void renderGrid() {
        List<StickerEntity> selected = new ArrayList<>();
        for (StickerEntity sticker : stickers) {
            if (sticker.getPackId().equals(selectedPackId)) selected.add(sticker);
        }
        adapter.submitList(selected);
        binding.stickerTrayEmpty.setVisibility(packs.isEmpty() ? View.VISIBLE : View.GONE);
        binding.stickerGrid.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Ưu tiên ảnh trong thư mục sticker riêng, kế đến là đường dẫn đã được kiểm tra cùng máy chủ. */
    static void loadStickerImage(ImageView image, @Nullable String localPath, @Nullable String relativeUrl) {
        Context context = image.getContext();
        File localFile = cachedStickerFile(context, localPath);
        String remote = relativeUrl == null ? null
                : ApiClient.resolveBackendStickerUrl(BuildConfig.BACKEND_BASE_URL, relativeUrl);
        Object model = localFile == null ? remote : localFile;
        Glide.with(image).load(model)
                .placeholder(R.drawable.ic_sticker).fallback(R.drawable.ic_sticker).error(R.drawable.ic_sticker)
                .into(image);
    }

    @Nullable static File cachedStickerFile(Context context, @Nullable String localPath) {
        if (localPath == null) return null;
        try {
            File directory = new File(context.getFilesDir(), "stickers").getCanonicalFile();
            File file = new File(localPath).getCanonicalFile();
            return file.toPath().startsWith(directory.toPath()) && file.isFile() && file.length() > 0 ? file : null;
        } catch (IOException inaccessible) {
            return null;
        }
    }

    private static final class StickerGridAdapter extends ListAdapter<StickerEntity, StickerHolder> {
        private final Consumer<StickerEntity> select;

        StickerGridAdapter(Consumer<StickerEntity> select) {
            super(new DiffUtil.ItemCallback<>() {
                @Override public boolean areItemsTheSame(@NonNull StickerEntity a, @NonNull StickerEntity b) {
                    return a.getStickerId().equals(b.getStickerId());
                }
                @Override public boolean areContentsTheSame(@NonNull StickerEntity a, @NonNull StickerEntity b) {
                    return a.getLabel().equals(b.getLabel()) && a.getImageUrl().equals(b.getImageUrl())
                            && Objects.equals(a.getLocalPath(), b.getLocalPath());
                }
            });
            this.select = select;
        }

        @NonNull @Override public StickerHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new StickerHolder(ItemStickerGridBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override public void onBindViewHolder(@NonNull StickerHolder holder, int index) {
            StickerEntity sticker = getItem(index);
            holder.image.setContentDescription(sticker.getLabel());
            loadStickerImage(holder.image, sticker.getLocalPath(), sticker.getImageUrl());
            holder.image.setOnClickListener(view -> select.accept(sticker));
        }

        @Override public void onViewRecycled(@NonNull StickerHolder holder) {
            // Dùng context ứng dụng vì RecyclerView có thể thu hồi view khi Activity đã đóng.
            Glide.with(holder.image.getContext().getApplicationContext()).clear(holder.image);
            holder.image.setOnClickListener(null);
            super.onViewRecycled(holder);
        }
    }

    private static final class StickerHolder extends RecyclerView.ViewHolder {
        final ImageView image;

        StickerHolder(ItemStickerGridBinding binding) {
            super(binding.getRoot());
            image = binding.getRoot();
        }
    }
}
