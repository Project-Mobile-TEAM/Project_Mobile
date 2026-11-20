package com.utechat.ui.contacts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.utechat.R;

/** Vạch kẻ 1dp giữa các hàng, thụt 76dp (avatar 44dp + lề) và không vẽ sau hàng cuối, như Figma. */
final class RowDividerDecoration extends RecyclerView.ItemDecoration {
    private final Paint paint=new Paint();
    private final float inset,thickness;

    RowDividerDecoration(Context context) {
        float density=context.getResources().getDisplayMetrics().density;
        paint.setColor(ContextCompat.getColor(context,R.color.ute_stroke));
        inset=76*density;thickness=Math.max(1f,density);
    }

    // shortcut: vạch luôn thụt từ bên trái, chưa đảo chiều cho giao diện RTL; sửa khi app hỗ trợ RTL.
    @Override public void onDrawOver(@NonNull Canvas canvas,@NonNull RecyclerView parent,@NonNull RecyclerView.State state) {
        for(int i=0;i<parent.getChildCount();i++) {
            View child=parent.getChildAt(i);
            if(parent.getChildAdapterPosition(child)>=state.getItemCount()-1) continue;
            float y=child.getBottom()+child.getTranslationY();
            canvas.drawRect(child.getLeft()+inset,y-thickness,child.getRight(),y,paint);
        }
    }
}
