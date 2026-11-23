package com.utechat.ui.media;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.data.media.MediaDownloadManager;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.databinding.FragmentVideoPlayerBinding;
import com.utechat.di.Injection;
import com.utechat.ui.chat.ChatErrors;
import java.io.File;

/**
 * Trình phát video trong ứng dụng bằng Media3 ExoPlayer (D-03):
 * - Nếu có localPath (pending_media/) thì phát trực tiếp từ tệp cục bộ.
 * - Ngược lại stream HTTP Range 206 với Bearer header qua OkHttpDataSource.Factory(ApiClient.getMediaOkHttpClient()).
 * - Nút "Mở bằng app ngoài" mở qua FileProvider (trực tiếp với tệp cục bộ hoặc sau khi tải cache), không truyền JWT.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoPlayerFragment extends Fragment {
    public static final String ARG_MEDIA_URL = "mediaUrl";
    public static final String ARG_LOCAL_PATH = "localPath";
    public static final String ARG_ATTACHMENT_ID = "attachmentId";
    public static final String ARG_FILE_NAME = "fileName";
    public static final String ARG_MIME = "mime";

    @Nullable private FragmentVideoPlayerBinding binding;
    @Nullable private ExoPlayer player;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentVideoPlayerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (binding == null) {
            return;
        }
        Bundle args = getArguments();
        String localPath = args != null ? args.getString(ARG_LOCAL_PATH) : null;
        String mediaUrl = args != null ? args.getString(ARG_MEDIA_URL) : null;
        String attachmentId = args != null ? args.getString(ARG_ATTACHMENT_ID) : null;
        String fileName = args != null ? args.getString(ARG_FILE_NAME) : null;
        String mime = args != null ? args.getString(ARG_MIME) : "video/mp4";

        binding.toolbar.setTitle(fileName != null && !fileName.trim().isEmpty()
                ? fileName : getString(R.string.chat_type_video));
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.btnOpenExternal.setOnClickListener(v ->
                openExternally(localPath, attachmentId, mediaUrl, fileName, mime));
    }

    @Override
    public void onStart() {
        super.onStart();
        initializePlayer();
    }

    @Override
    public void onStop() {
        releasePlayer();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        releasePlayer();
        binding = null;
        super.onDestroyView();
    }

    private void initializePlayer() {
        if (binding == null || player != null || getContext() == null) {
            return;
        }
        Bundle args = getArguments();
        String localPath = args != null ? args.getString(ARG_LOCAL_PATH) : null;
        String mediaUrl = args != null ? args.getString(ARG_MEDIA_URL) : null;

        File localFile = localPath != null ? new File(localPath) : null;
        if (localFile != null && localFile.isFile()) {
            player = new ExoPlayer.Builder(requireContext()).build();
            binding.playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(localFile)));
            player.prepare();
            player.setPlayWhenReady(true);
            return;
        }

        if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
            Snackbar.make(binding.getRoot(), R.string.media_load_failed, Snackbar.LENGTH_LONG).show();
            return;
        }
        String resolvedUrl;
        try {
            resolvedUrl = ApiClient.resolveBackendMediaUrl(BuildConfig.BACKEND_BASE_URL, mediaUrl);
        } catch (IllegalArgumentException e) {
            Snackbar.make(binding.getRoot(), R.string.media_load_failed, Snackbar.LENGTH_LONG).show();
            return;
        }

        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(ApiClient.getMediaOkHttpClient());
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(httpFactory);
        player = new ExoPlayer.Builder(requireContext())
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        binding.playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.parse(resolvedUrl)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void releasePlayer() {
        if (binding != null) {
            binding.playerView.setPlayer(null);
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void openExternally(
            @Nullable String localPath,
            @Nullable String attachmentId,
            @Nullable String mediaUrl,
            @Nullable String fileName,
            @Nullable String mime) {
        if (binding == null) {
            return;
        }
        File localFile = localPath != null ? new File(localPath) : null;
        if (localFile != null && localFile.isFile()) {
            launchFileProviderView(localFile, mime);
            return;
        }
        String resolvedId = ImageViewerFragment.resolveAttachmentId(attachmentId, mediaUrl);
        if (resolvedId == null || mediaUrl == null) {
            Snackbar.make(binding.getRoot(), R.string.media_load_failed, Snackbar.LENGTH_SHORT).show();
            return;
        }
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnOpenExternal.setEnabled(false);
        Injection.provideMediaDownloadManager(requireContext()).downloadToCache(
                resolvedId, mediaUrl, fileName != null ? fileName : "video.mp4",
                mime != null ? mime : "video/mp4",
                new MediaDownloadManager.Callback() {
                    @Override
                    public void onSuccess(@NonNull File file) {
                        if (binding == null || !isAdded()) return;
                        binding.progressBar.setVisibility(View.GONE);
                        binding.btnOpenExternal.setEnabled(true);
                        launchFileProviderView(file, mime);
                    }

                    @Override
                    public void onError(@NonNull String code) {
                        if (binding == null || !isAdded()) return;
                        binding.progressBar.setVisibility(View.GONE);
                        binding.btnOpenExternal.setEnabled(true);
                        Snackbar.make(binding.getRoot(), ChatErrors.message(code), Snackbar.LENGTH_LONG).show();
                    }
                });
    }

    private void launchFileProviderView(@NonNull File file, @Nullable String mime) {
        try {
            Intent intent = MediaDownloadManager.buildExternalViewIntent(
                    requireContext(), file, mime != null ? mime : "video/*");
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.media_no_external_app, Snackbar.LENGTH_LONG).show();
            }
        }
    }
}
