package com.utechat.data.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.utechat.data.MainThreadExecutor;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lấy vị trí một lần bằng FusedLocationProviderClient và mở bản đồ ngoài bằng Intent geo: (D-02).
 * Cố ý KHÔNG dùng Maps SDK / SupportMapFragment để không cần API key và không tăng dung lượng APK.
 */
public final class LocationFetchHelper {
    private static final long TIMEOUT_MS = 15_000L;

    /** Kết quả trả về trên luồng chính. */
    public interface Callback {
        void onSuccess(double latitude, double longitude);

        void onError(@NonNull String reason);
    }

    /** Lấy vị trí hiện tại; trả lỗi PERMISSION_DENIED nếu chưa có quyền, LOCATION_UNAVAILABLE khi không định vị được. */
    @SuppressLint("MissingPermission")
    public void fetchCurrent(@NonNull Context context, @NonNull Callback callback) {
        Context appContext = context.getApplicationContext();
        boolean fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            MainThreadExecutor.INSTANCE.execute(() -> callback.onError("PERMISSION_DENIED"));
            return;
        }

        CancellationTokenSource cancellation = new CancellationTokenSource();
        AtomicBoolean finished = new AtomicBoolean(false);
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        Runnable timeout = cancellation::cancel;
        timeoutHandler.postDelayed(timeout, TIMEOUT_MS);

        int priority = fine ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        LocationServices.getFusedLocationProviderClient(appContext)
                .getCurrentLocation(priority, cancellation.getToken())
                .addOnSuccessListener(MainThreadExecutor.INSTANCE, location -> {
                    timeoutHandler.removeCallbacks(timeout);
                    if (!finished.compareAndSet(false, true)) return;
                    if (location == null) {
                        callback.onError("LOCATION_UNAVAILABLE");
                    } else {
                        callback.onSuccess(location.getLatitude(), location.getLongitude());
                    }
                })
                .addOnFailureListener(MainThreadExecutor.INSTANCE, e -> {
                    timeoutHandler.removeCallbacks(timeout);
                    if (finished.compareAndSet(false, true)) callback.onError("LOCATION_UNAVAILABLE");
                })
                .addOnCanceledListener(MainThreadExecutor.INSTANCE, () -> {
                    timeoutHandler.removeCallbacks(timeout);
                    if (finished.compareAndSet(false, true)) callback.onError("LOCATION_UNAVAILABLE");
                });
    }

    /** Intent mở ứng dụng bản đồ ngoài tại tọa độ cho trước; không chứa token hay dữ liệu nhạy cảm. */
    @NonNull
    public static Intent buildExternalGeoIntent(double lat, double lng, @Nullable String label) {
        String query = String.format(Locale.ROOT, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lng, lat, lng);
        if (label != null && !label.trim().isEmpty()) {
            query = query + "(" + Uri.encode(label.trim()) + ")";
        }
        return new Intent(Intent.ACTION_VIEW, Uri.parse(query));
    }
}
