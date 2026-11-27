package com.utechat.ui.chat;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
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

/** Điều khiển khay nhãn dán và bảng chọn biểu tượng cảm xúc Unicode gắn dưới ô nhập. */
public final class StickerTrayView {
    private static final String EMOJI_TAB_ID = "__emoji__";
    private static final List<String> POPULAR_EMOJIS = List.of(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂",
            "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰",
            "😘", "😗", "😋", "😛", "😜", "🤪", "🤨",
            "🧐", "🤓", "😎", "🥳", "😏", "😒", "😞",
            "😔", "😟", "😕", "🙁", "😣", "😖", "😫",
            "😩", "🥺", "😢", "😭", "😤", "😠", "😡",
            "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰",
            "🤗", "🤔", "🤭", "🤫", "🤐", "😴", "🥱",
            "❤️", "🧡", "💛", "💚", "💙", "💜", "💔",
            "👍", "👎", "👏", "🙌", "🙏", "🤝", "👌",
            "✌️", "🤞", "💪", "🔥", "✨", "🎉", "💯",
            "☕", "📚", "💻", "🎓", "⏰", "☀️", "🌙"
    );

    private final ViewStickerTrayBinding binding;
    private final StickerGridAdapter adapter;
    private List<StickerPackEntity> packs = List.of();
    private List<StickerEntity> stickers = List.of();
    @Nullable private String selectedPackId;
    private boolean userSelectedEmojiTab;

    public StickerTrayView(ViewStickerTrayBinding binding, Consumer<StickerEntity> select, Runnable openStore) {
        this(binding, select, emoji -> { }, openStore);
    }

    public StickerTrayView(
            ViewStickerTrayBinding binding,
            Consumer<StickerEntity> select,
            Consumer<String> selectEmoji,
            Runnable openStore) {
        this.binding = binding;
        Context context = binding.getRoot().getContext();
        this.adapter = new StickerGridAdapter(select);
        binding.stickerGrid.setLayoutManager(new GridLayoutManager(context, 4));
        binding.stickerGrid.setAdapter(adapter);
        binding.emojiGrid.setLayoutManager(new GridLayoutManager(context, 7));
        binding.emojiGrid.setAdapter(new EmojiGridAdapter(POPULAR_EMOJIS, selectEmoji));
        binding.stickerStoreOpen.setOnClickListener(view -> openStore.run());
        setOpen(false);
        rebuildTabs();
        renderGrid();
    }

    public boolean isOpen() { return binding.getRoot().getVisibility() == View.VISIBLE; }

    public boolean isEmojiMode() { return EMOJI_TAB_ID.equals(selectedPackId); }

    public void setOpen(boolean open) {
        binding.getRoot().setVisibility(open ? View.VISIBLE : View.GONE);
    }

    /** Chuyển thẳng khay sang bảng chọn emoji Unicode. */
    public void selectEmojiTab() {
        userSelectedEmojiTab = true;
        selectedPackId = EMOJI_TAB_ID;
        rebuildTabs();
        renderGrid();
        binding.emojiGrid.scrollToPosition(0);
    }

    /** Chuyển khay sang bộ nhãn dán đã cài đặt. */
    public void selectStickerTab() {
        userSelectedEmojiTab = false;
        boolean validPack = packs.stream().anyMatch(pack -> pack.getPackId().equals(selectedPackId));
        if (!validPack) {
            selectedPackId = packs.isEmpty() ? null : packs.get(0).getPackId();
        }
        rebuildTabs();
        renderGrid();
        binding.stickerGrid.scrollToPosition(0);
    }

    public void setPacks(@Nullable List<StickerPackEntity> installedPacks) {
        packs = installedPacks == null ? List.of() : List.copyOf(installedPacks);
        if (userSelectedEmojiTab) {
            selectedPackId = EMOJI_TAB_ID;
        } else {
            boolean selected = packs.stream().anyMatch(pack -> pack.getPackId().equals(selectedPackId));
            if (!selected) {
                selectedPackId = packs.isEmpty() ? null : packs.get(0).getPackId();
            }
        }
        rebuildTabs();
        renderGrid();
    }

    private void rebuildTabs() {
        binding.stickerTabs.removeAllViews();
        Context context = binding.getRoot().getContext();
        String activeId = selectedPackId;

        Chip emojiChip = createTabChip(context, context.getString(R.string.emoji_tray_tab), EMOJI_TAB_ID.equals(activeId));
        emojiChip.setOnClickListener(view -> selectEmojiTab());
        binding.stickerTabs.addView(emojiChip);

        for (StickerPackEntity pack : packs) {
            Chip chip = createTabChip(context, pack.getName(), pack.getPackId().equals(activeId));
            chip.setOnClickListener(view -> {
                userSelectedEmojiTab = false;
                selectedPackId = pack.getPackId();
                rebuildTabs();
                renderGrid();
                binding.stickerGrid.scrollToPosition(0);
            });
            binding.stickerTabs.addView(chip);
        }
    }

    private Chip createTabChip(Context context, String label, boolean checked) {
        Chip chip = new Chip(context);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setCheckable(true);
        chip.setText(label);
        chip.setChecked(checked);
        return chip;
    }

    public void setStickers(@Nullable List<StickerEntity> installedStickers) {
        stickers = installedStickers == null ? List.of() : List.copyOf(installedStickers);
        renderGrid();
    }

    private void renderGrid() {
        if (EMOJI_TAB_ID.equals(selectedPackId)) {
            binding.emojiGrid.setVisibility(View.VISIBLE);
            binding.stickerGrid.setVisibility(View.GONE);
            binding.stickerTrayEmpty.setVisibility(View.GONE);
            return;
        }
        binding.emojiGrid.setVisibility(View.GONE);
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

    /** Lưới biểu tượng cảm xúc Unicode nhẹ dùng ngay trong khay. */
    private static final class EmojiGridAdapter extends RecyclerView.Adapter<EmojiHolder> {
        private final List<String> emojis;
        private final Consumer<String> selectEmoji;

        EmojiGridAdapter(List<String> emojis, Consumer<String> selectEmoji) {
            this.emojis = emojis;
            this.selectEmoji = selectEmoji;
        }

        @NonNull @Override public EmojiHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Context context = parent.getContext();
            TextView cell = new TextView(context);
            int minTouch = Math.round(48 * context.getResources().getDisplayMetrics().density);
            cell.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minTouch));
            cell.setMinWidth(minTouch);
            cell.setMinHeight(minTouch);
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            cell.setTextColor(ContextCompat.getColor(context, R.color.ute_text));
            TypedValue selectable = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, selectable, true)) {
                cell.setBackgroundResource(selectable.resourceId);
            }
            return new EmojiHolder(cell);
        }

        @Override public void onBindViewHolder(@NonNull EmojiHolder holder, int index) {
            String emoji = emojis.get(index);
            holder.text.setText(emoji);
            holder.text.setContentDescription(emoji);
            holder.text.setOnClickListener(view -> selectEmoji.accept(emoji));
        }

        @Override public int getItemCount() {
            return emojis.size();
        }
    }

    private static final class EmojiHolder extends RecyclerView.ViewHolder {
        final TextView text;

        EmojiHolder(TextView text) {
            super(text);
            this.text = text;
        }
    }
}
