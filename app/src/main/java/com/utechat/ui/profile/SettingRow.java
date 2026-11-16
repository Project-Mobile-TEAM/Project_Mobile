package com.utechat.ui.profile;

import com.utechat.R;

import android.view.View;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import com.utechat.databinding.ItemSettingRowBinding;

/** Cấu hình một hàng cài đặt dùng chung (item_setting_row): icon + tiêu đề + một thành phần bên phải. */
final class SettingRow {
    private SettingRow() { }

    /** Đặt icon và tiêu đề; icon = 0 thì ẩn vòng tròn icon. */
    static void base(ItemSettingRowBinding row, @DrawableRes int icon, CharSequence title) {
        row.title.setText(title);
        row.iconCircle.setVisibility(icon == 0 ? View.GONE : View.VISIBLE);
        if (icon != 0) row.icon.setImageResource(icon);
        // Không có icon (màn Ngôn ngữ) thì chữ sát lề trái thẻ như Figma, không chừa chỗ cho icon.
        ((android.view.ViewGroup.MarginLayoutParams) row.title.getLayoutParams()).setMarginStart(icon == 0 ? 0 : (int) (16 * row.getRoot().getResources().getDisplayMetrics().density));
    }

    /** Hàng gọn của màn Hồ sơ (Figma cập nhật): icon trong ô vuông bo 30dp, cách chữ 12dp, đệm dọc 10dp. */
    static void compact(ItemSettingRowBinding row) {
        float d = row.getRoot().getResources().getDisplayMetrics().density;
        androidx.constraintlayout.widget.ConstraintLayout root = row.getRoot();
        root.setMinHeight((int) (52 * d)); // ConstraintLayout có minHeight riêng, setMinimumHeight không có tác dụng.
        root.setPadding(root.getPaddingLeft(), (int) (10 * d), root.getPaddingRight(), (int) (10 * d));
        row.iconCircle.setBackgroundResource(R.drawable.bg_icon_tile);
        row.iconCircle.getLayoutParams().width = (int) (30 * d);
        row.iconCircle.getLayoutParams().height = (int) (30 * d);
        ((android.view.ViewGroup.MarginLayoutParams) row.title.getLayoutParams()).setMarginStart((int) (12 * d));
    }

    /** Hàng dẫn tới màn khác: có giá trị hiện tại (tuỳ chọn) và mũi tên. */
    static void chevron(ItemSettingRowBinding row, @Nullable CharSequence value, View.OnClickListener onClick) {
        row.value.setVisibility(value == null ? View.GONE : View.VISIBLE);
        row.value.setText(value);
        row.chevron.setVisibility(View.VISIBLE);
        row.getRoot().setOnClickListener(onClick);
    }

    /** Hàng có công tắc; bấm cả hàng cũng đổi công tắc để vùng chạm đủ lớn. */
    static void toggle(ItemSettingRowBinding row, boolean checked) {
        row.toggle.setVisibility(View.VISIBLE);
        row.toggle.setChecked(checked);
        row.getRoot().setOnClickListener(v -> row.toggle.toggle());
    }

    /** Hàng chọn một (radio): bấm hàng thì gọi onSelect; trạng thái chọn do màn hình tự cập nhật qua setChecked. */
    static void radio(ItemSettingRowBinding row, boolean checked, View.OnClickListener onSelect) {
        row.radio.setVisibility(View.VISIBLE);
        row.radio.setChecked(checked);
        row.getRoot().setOnClickListener(onSelect);
    }
}
