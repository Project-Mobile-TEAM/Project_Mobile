package com.utechat.ui.media;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView thuần Java hỗ trợ phóng to bằng hai ngón (1x–4x), chạm đúp phóng/thu và kéo xem chi tiết ảnh (D-03).
 * Không cần phụ thuộc thư viện JitPack PhotoView.
 */
public class ZoomableImageView extends AppCompatImageView {
    private static final float MIN_SCALE = 1f;
    private static final float MID_SCALE = 2.5f;
    private static final float MAX_SCALE = 4f;

    private final Matrix drawMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float baseScale = 1f;
    private float currentZoom = 1f;

    public ZoomableImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        if (changed) {
            resetZoom();
        }
        return changed;
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetZoom);
    }

    /** Đưa ảnh về tỷ lệ vừa khít khung nhìn và căn giữa. */
    public void resetZoom() {
        Drawable d = getDrawable();
        int viewW = getWidth() - getPaddingLeft() - getPaddingRight();
        int viewH = getHeight() - getPaddingTop() - getPaddingBottom();
        if (d == null || viewW <= 0 || viewH <= 0) {
            return;
        }
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            return;
        }
        baseScale = Math.min((float) viewW / dw, (float) viewH / dh);
        currentZoom = MIN_SCALE;
        float dx = (viewW - dw * baseScale) * 0.5f;
        float dy = (viewH - dh * baseScale) * 0.5f;
        drawMatrix.reset();
        drawMatrix.postScale(baseScale, baseScale);
        drawMatrix.postTranslate(dx, dy);
        setImageMatrix(drawMatrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void applyScale(float targetZoom, float focusX, float focusY) {
        float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, targetZoom));
        float factor = clamped / currentZoom;
        currentZoom = clamped;
        drawMatrix.postScale(factor, factor, focusX, focusY);
        clampTranslation();
        setImageMatrix(drawMatrix);
    }

    private void clampTranslation() {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        RectF bounds = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        drawMatrix.mapRect(bounds);

        float viewW = getWidth() - getPaddingLeft() - getPaddingRight();
        float viewH = getHeight() - getPaddingTop() - getPaddingBottom();
        float dx = 0f;
        float dy = 0f;

        if (bounds.width() <= viewW) {
            dx = (viewW - bounds.width()) * 0.5f - bounds.left;
        } else if (bounds.left > 0f) {
            dx = -bounds.left;
        } else if (bounds.right < viewW) {
            dx = viewW - bounds.right;
        }

        if (bounds.height() <= viewH) {
            dy = (viewH - bounds.height()) * 0.5f - bounds.top;
        } else if (bounds.top > 0f) {
            dy = -bounds.top;
        } else if (bounds.bottom < viewH) {
            dy = viewH - bounds.bottom;
        }

        drawMatrix.postTranslate(dx, dy);
        drawMatrix.getValues(matrixValues);
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            applyScale(currentZoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            return true;
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            if (currentZoom > MIN_SCALE + 0.1f) {
                resetZoom();
            } else {
                applyScale(MID_SCALE, e.getX(), e.getY());
            }
            return true;
        }

        @Override
        public boolean onScroll(
                @Nullable MotionEvent e1,
                @NonNull MotionEvent e2,
                float distanceX,
                float distanceY) {
            if (currentZoom <= MIN_SCALE + 0.05f) {
                return false;
            }
            drawMatrix.postTranslate(-distanceX, -distanceY);
            clampTranslation();
            setImageMatrix(drawMatrix);
            return true;
        }
    }
}
