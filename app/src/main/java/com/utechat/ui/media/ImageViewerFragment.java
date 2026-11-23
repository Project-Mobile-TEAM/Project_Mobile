package com.utechat.ui.media;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.data.media.MediaDownloadManager;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.databinding.FragmentImageViewerBinding;
import com.utechat.di.Injection;
import com.utechat.ui.chat.ChatErrors;
import java.io.File;

/**
 * Màn hình xem ảnh toàn màn hình với phóng to/thu nhỏ (D-03):
 * - Nạp từ localPath nếu tệp cục bộ tồn tại, ngược lại dùng Glide + OkHttp Bearer header.
 * - Nút "Mở bằng app ngoài": nếu tệp đã ở pending_media/ hoặc localPath thì mở thẳng qua FileProvider,
 *   nếu chưa tải thì tải qua MediaDownloadManager rồi mới chia sẻ URI content:// (không bao giờ truyền JWT).
 */
public class ImageViewerFragment extends Fragment {
    public static final String ARG_MEDIA_URL = "mediaUrl";
    public static final String ARG_LOCAL_PATH = "localPath";
    public static final String ARG_ATTACHMENT_ID = "attachmentId";
    public static final String ARG_FILE_NAME = "fileName";
    public static final String ARG_MIME = "mime";

    @Nullable private FragmentImageViewerBinding binding;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentImageViewerBinding.inflate(inflater, container, false);
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
        String mime = args != null ? args.getString(ARG_MIME) : "image/jpeg";

        binding.toolbar.setTitle(fileName != null && !fileName.trim().isEmpty()
                ? fileName : getString(R.string.chat_type_image));
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.btnOpenExternal.setOnClickListener(v ->
                openExternally(localPath, attachmentId, mediaUrl, fileName, mime));

        RequestListener<Drawable> errorListener = new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(
                    @Nullable GlideException e,
                    @Nullable Object model,
                    @NonNull Target<Drawable> target,
                    boolean isFirstResource) {
                if (binding != null && isAdded()) {
                    Snackbar.make(binding.getRoot(), R.string.media_load_failed, Snackbar.LENGTH_LONG).show();
                }
                return false;
            }

            @Override
            public boolean onResourceReady(
                    @NonNull Drawable resource,
                    @NonNull Object model,
                    Target<Drawable> target,
                    @NonNull DataSource dataSource,
                    boolean isFirstResource) {
                return false;
            }
        };

        File localFile = localPath != null ? new File(localPath) : null;
        if (localFile != null && localFile.isFile()) {
            Glide.with(this).load(localFile).listener(errorListener).into(binding.imageView);
        } else if (mediaUrl != null && !mediaUrl.trim().isEmpty()) {
            try {
                String resolved = ApiClient.resolveBackendMediaUrl(BuildConfig.BACKEND_BASE_URL, mediaUrl);
                Glide.with(this).load(resolved).listener(errorListener).into(binding.imageView);
            } catch (IllegalArgumentException e) {
                Snackbar.make(binding.getRoot(), R.string.media_load_failed, Snackbar.LENGTH_LONG).show();
            }
        } else {
            Snackbar.make(binding.getRoot(), R.string.media_load_failed, Snackbar.LENGTH_LONG).show();
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
        String resolvedId = resolveAttachmentId(attachmentId, mediaUrl);
        if (resolvedId == null || mediaUrl == null) {
            Snackbar.make(binding.getRoot(), R.string.media_load_failed, Snackbar.LENGTH_SHORT).show();
            return;
        }
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnOpenExternal.setEnabled(false);
        Injection.provideMediaDownloadManager(requireContext()).downloadToCache(
                resolvedId, mediaUrl, fileName != null ? fileName : "image.jpg",
                mime != null ? mime : "image/jpeg",
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
                    requireContext(), file, mime != null ? mime : "image/*");
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.media_no_external_app, Snackbar.LENGTH_LONG).show();
            }
        }
    }

    @Nullable
    static String resolveAttachmentId(@Nullable String explicitId, @Nullable String mediaUrl) {
        if (explicitId != null && !explicitId.trim().isEmpty()) {
            return explicitId.trim();
        }
        if (mediaUrl != null) {
            int slash = mediaUrl.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < mediaUrl.length()) {
                return mediaUrl.substring(slash + 1).trim();
            }
        }
        return null;
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
