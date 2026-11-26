package com.utechat.data.media;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import com.utechat.data.media.MediaPreparationHelper.PrepareException;
import com.utechat.data.media.MediaPreparationHelper.PreparedMedia;
import java.io.File;
import java.io.IOException;

/**
 * Ghi âm tin nhắn thoại AAC .m4a (D-02): tối đa 5 phút và 10MB, ghi thẳng vào filesDir/pending_media/&lt;clientId&gt;.m4a
 * để hàng đợi offline gửi lại được. Cần quyền RECORD_AUDIO đã được cấp trước khi gọi start().
 */
public final class VoiceRecorderHelper {
    public static final int MAX_DURATION_MS = 300_000;
    public static final long MAX_FILE_BYTES = 10_485_760L;
    private static final int MIN_DURATION_MS = 500;

    private final Context appContext;
    private MediaRecorder recorder;
    private File outputFile;
    private String activeClientId;
    private long startElapsedMs;
    private volatile boolean autoStoppedAtLimit = false;

    public VoiceRecorderHelper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Bắt đầu ghi âm vào pending_media/&lt;clientId&gt;.m4a; ném lỗi nếu đang ghi hoặc không khởi động được micro. */
    public synchronized void start(@NonNull String clientId) throws PrepareException {
        if (recorder != null) throw new PrepareException("VALIDATION");
        if (!clientId.matches("[A-Za-z0-9-]{1,64}")) throw new PrepareException("VALIDATION");
        File dir = MediaPreparationHelper.pendingMediaDir(appContext);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new PrepareException("IO_ERROR");

        File target = new File(dir, clientId + ".m4a");
        MediaRecorder created = createRecorder();
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC);
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            created.setAudioEncodingBitRate(64_000);
            created.setAudioSamplingRate(44_100);
            created.setMaxDuration(MAX_DURATION_MS);
            created.setMaxFileSize(MAX_FILE_BYTES);
            created.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    // Đánh dấu MediaRecorder đã tự dừng khi chạm trần 5 phút / 10MB để stopAndFinalize giữ lại bản ghi.
                    autoStoppedAtLimit = true;
                }
            });
            created.setOutputFile(target.getAbsolutePath());
            created.prepare();
            created.start();
        } catch (IOException | RuntimeException e) {
            releaseQuietly(created);
            deleteQuietly(target);
            throw new PrepareException("IO_ERROR", e);
        }
        recorder = created;
        outputFile = target;
        activeClientId = clientId;
        autoStoppedAtLimit = false;
        startElapsedMs = SystemClock.elapsedRealtime();
    }

    /** Dừng ghi âm và trả về mô tả tệp; quá ngắn (&lt; 0,5 giây) bị hủy với mã VALIDATION. */
    @NonNull
    public synchronized PreparedMedia stopAndFinalize() throws PrepareException {
        if (recorder == null || outputFile == null) throw new PrepareException("VALIDATION");
        MediaRecorder current = recorder;
        File file = outputFile;
        String clientId = activeClientId;
        boolean hitLimit = autoStoppedAtLimit;
        long elapsed = SystemClock.elapsedRealtime() - startElapsedMs;
        resetState();
        try {
            current.stop();
        } catch (RuntimeException e) {
            // Nếu MediaRecorder đã tự dừng đúng trần 5:00/10MB và tệp hợp lệ thì giữ lại, không xóa nhầm.
            if (!(hitLimit && file.isFile() && file.length() > 0L)) {
                releaseQuietly(current);
                deleteQuietly(file);
                throw new PrepareException("VALIDATION", e);
            }
        }
        releaseQuietly(current);

        int durationMs = (int) Math.min(elapsed, (long) MAX_DURATION_MS);
        if (durationMs < MIN_DURATION_MS || !file.isFile() || file.length() == 0L) {
            deleteQuietly(file);
            throw new PrepareException("VALIDATION");
        }
        if (file.length() > MAX_FILE_BYTES) {
            deleteQuietly(file);
            throw new PrepareException("FILE_TOO_LARGE");
        }
        String shortId = clientId.length() > 8 ? clientId.substring(0, 8) : clientId;
        return new PreparedMedia("AUDIO", file.getAbsolutePath(), "audio/mp4", file.length(),
                "voice_" + shortId + ".m4a", null, null, durationMs);
    }

    /** Hủy ghi âm đang chạy và xóa tệp dở dang. */
    public synchronized void cancelAndDelete() {
        MediaRecorder current = recorder;
        File file = outputFile;
        resetState();
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
                // Chưa có dữ liệu để dừng: bỏ qua.
            }
            releaseQuietly(current);
        }
        deleteQuietly(file);
    }

    public synchronized boolean isRecording() {
        return recorder != null;
    }

    /** Thời gian đã ghi (ms) để giao diện hiển thị đồng hồ; 0 nếu chưa ghi. */
    public synchronized long elapsedMs() {
        return recorder == null ? 0L : SystemClock.elapsedRealtime() - startElapsedMs;
    }

    private void resetState() {
        recorder = null;
        outputFile = null;
        activeClientId = null;
        autoStoppedAtLimit = false;
    }

    @SuppressWarnings("deprecation")
    private MediaRecorder createRecorder() {
        // Android 12+ yêu cầu truyền Context; bản cũ dùng constructor không tham số.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(appContext)
                : new MediaRecorder();
    }

    private static void releaseQuietly(MediaRecorder r) {
        try {
            r.release();
        } catch (RuntimeException ignored) {
            // Đã giải phóng trước đó.
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}
