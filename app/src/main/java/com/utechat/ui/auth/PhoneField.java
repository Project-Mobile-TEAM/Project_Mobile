package com.utechat.ui.auth;

import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import androidx.core.content.ContextCompat;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.utechat.R;

/** Ô số điện thoại theo Figma: tiền tố "+84" đậm, vạch ngăn, và số tự nhóm 3-3-3 (912 345 678). */
public final class PhoneField {
    private PhoneField() { }

    public static void attach(TextInputLayout layout, TextInputEditText edit) {
        SpannableStringBuilder prefix = new SpannableStringBuilder("+84  |  ");
        prefix.setSpan(new ForegroundColorSpan(ContextCompat.getColor(layout.getContext(), R.color.ute_text)),
                0, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        prefix.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        prefix.setSpan(new ForegroundColorSpan(ContextCompat.getColor(layout.getContext(), R.color.ute_stroke)),
                5, 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        layout.setPrefixText(prefix);

        edit.addTextChangedListener(new TextWatcher() {
            private boolean busy; // Chặn vòng lặp khi tự sửa lại chữ.
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (busy) return;
                String digits = s.toString().replaceAll("\\D", "");
                StringBuilder grouped = new StringBuilder();
                for (int i = 0; i < digits.length(); i++) {
                    if (i > 0 && i % 3 == 0) grouped.append(' ');
                    grouped.append(digits.charAt(i));
                }
                if (grouped.toString().contentEquals(s)) return;
                busy = true;
                s.replace(0, s.length(), grouped);
                busy = false;
            }
        });
    }
}
