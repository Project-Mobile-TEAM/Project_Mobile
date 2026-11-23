package com.utechat.ui.media;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.utechat.data.media.MediaDownloadManager;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Bộ phát tin nhắn thoại dùng chung duy nhất 1 MediaPlayer cho toàn phòng chat (D-03):
 * - Bấm phát bong bóng mới sẽ tự dừng bong bóng cũ để tuyệt đối không phát 2 voice cùng lúc.
 * - Cập nhật thanh tua (SeekBar) mỗi 200ms trên main Looper và tự giải phóng tài nguyên khi rời phòng.
 */
public final class VoiceBubblePlayer {
    private static final long TICK_MS = 200L;

    /** Ảnh chụp trạng thái phát hiện hành để MessageAdapter vẽ nút Play/Pause & SeekBar. */
    public static final class PlaybackState {
        @Nullable public final String clientId;
        public final boolean playing;
        public final boolean loading;
        public final int positionMs;
        public final int durationMs;

        public PlaybackState(
                @Nullable String clientId,
                boolean playing,
                boolean loading,
                int positionMs,
                int durationMs) {
            this.clientId = clientId;
            this.playing = playing;
            this.loading = loading;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }

        @NonNull
        public static PlaybackState idle() {
            return new PlaybackState(null, false, false, 0, 0);
        }
    }

    public interface Listener {
        void onPlaybackChanged(@NonNull PlaybackState state);
    }

    private final MediaDownloadManager downloadManager;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private MediaPlayer mediaPlayer;
    @Nullable private String currentClientId;
    private boolean loading = false;
    private int currentDurationMs = 0;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            MediaPlayer mp = mediaPlayer;
            if (mp != null && currentClientId != null && mp.isPlaying()) {
                publish(new PlaybackState(currentClientId, true, false,
                        Math.max(0, mp.getCurrentPosition()),
                        Math.max(currentDurationMs, mp.getDuration())));
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    public VoiceBubblePlayer(@NonNull MediaDownloadManager downloadManager, @NonNull Listener listener) {
        this.downloadManager = downloadManager;
        this.listener = listener;
    }

    /** Phát, tạm dừng hoặc tiếp tục một bong bóng thoại; luôn dừng bong bóng cũ trước khi phát bong bóng mới. */
    public void togglePlay(
            @NonNull String clientId,
            @Nullable String localPath,
            @Nullable String attachmentId,
            @Nullable String mediaUrl,
            @Nullable String fileName,
            @Nullable String mime,
            @NonNull Consumer<String> onError) {
        if (clientId.equals(currentClientId) && mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                handler.removeCallbacks(tickRunnable);
                publish(new PlaybackState(clientId, false, false,
                        Math.max(0, mediaPlayer.getCurrentPosition()), currentDurationMs));
            } else {
                mediaPlayer.start();
                startTicker();
                publish(new PlaybackState(clientId, true, false,
                        Math.max(0, mediaPlayer.getCurrentPosition()), currentDurationMs));
            }
            return;
        }

        stopInternal();
        currentClientId = clientId;

        File localFile = localPath != null ? new File(localPath) : null;
        if (localFile != null && localFile.isFile()) {
            startFromFile(clientId, localFile, onError);
            return;
        }

        String resolvedId = ImageViewerFragment.resolveAttachmentId(attachmentId, mediaUrl);
        if (resolvedId == null || mediaUrl == null) {
            currentClientId = null;
            publish(PlaybackState.idle());
            onError.accept("NOT_FOUND");
            return;
        }

        loading = true;
        publish(new PlaybackState(clientId, false, true, 0, 0));
        downloadManager.downloadToCache(
                resolvedId, mediaUrl,
                fileName != null ? fileName : "voice.m4a",
                mime != null ? mime : "audio/mp4",
                new MediaDownloadManager.Callback() {
                    @Override
                    public void onSuccess(@NonNull File file) {
                        if (!clientId.equals(currentClientId)) {
                            return;
                        }
                        loading = false;
                        startFromFile(clientId, file, onError);
                    }

                    @Override
                    public void onError(@NonNull String code) {
                        if (!clientId.equals(currentClientId)) {
                            return;
                        }
                        loading = false;
                        currentClientId = null;
                        publish(PlaybackState.idle());
                        onError.accept(code);
                    }
                });
    }

    /** Tua vị trí phát trên bong bóng thoại đang hoạt động. */
    public void seekTo(@NonNull String clientId, int positionMs) {
        if (!clientId.equals(currentClientId) || mediaPlayer == null || loading) {
            return;
        }
        int clamped = Math.max(0, Math.min(positionMs, Math.max(0, currentDurationMs)));
        mediaPlayer.seekTo(clamped);
        publish(new PlaybackState(clientId, mediaPlayer.isPlaying(), false, clamped, currentDurationMs));
    }

    /** Dừng phát và giải phóng hoàn toàn MediaPlayer (gọi trong onStop / onDestroyView). */
    public void release() {
        stopInternal();
        publish(PlaybackState.idle());
    }

    private void startFromFile(@NonNull String clientId, @NonNull File file, @NonNull Consumer<String> onError) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(file.getAbsolutePath());
            mp.prepare();
            currentDurationMs = Math.max(0, mp.getDuration());
            mp.setOnCompletionListener(completed -> {
                handler.removeCallbacks(tickRunnable);
                int doneDur = currentDurationMs;
                stopInternal();
                publish(new PlaybackState(null, false, false, 0, doneDur));
            });
            mp.setOnErrorListener((m, what, extra) -> {
                stopInternal();
                publish(PlaybackState.idle());
                onError.accept("ERROR");
                return true;
            });
            mediaPlayer = mp;
            mp.start();
            startTicker();
            publish(new PlaybackState(clientId, true, false, 0, currentDurationMs));
        } catch (IOException | RuntimeException e) {
            mp.release();
            currentClientId = null;
            publish(PlaybackState.idle());
            onError.accept("ERROR");
        }
    }

    private void stopInternal() {
        handler.removeCallbacks(tickRunnable);
        loading = false;
        currentClientId = null;
        currentDurationMs = 0;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (RuntimeException ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (RuntimeException ignored) {
            }
            mediaPlayer = null;
        }
    }

    private void startTicker() {
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    private void publish(@NonNull PlaybackState state) {
        listener.onPlaybackChanged(state);
    }
}
