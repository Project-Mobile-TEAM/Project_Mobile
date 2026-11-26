package com.utechat.data.call;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.utechat.data.remote.model.CallDtos;
import java.util.List;

/**
 * Giao diện trừu tượng (seam D-12) tách rời hoàn toàn thư viện native org.webrtc.* khỏi máy trạng thái cuộc gọi.
 * Giúp CallSessionCoordinator được kiểm thử 100% trên JVM bằng bản giả mà không cần phần cứng âm thanh hay JNI.
 */
public interface WebRtcCallEngine {

    /** Bộ lắng nghe sự kiện SDP cục bộ, ứng viên ICE cục bộ và trạng thái kết nối ICE. */
    interface Listener {
        void onLocalOffer(@NonNull String sdp);

        void onLocalAnswer(@NonNull String sdp);

        void onLocalIceCandidate(@NonNull String candidate, @Nullable String sdpMid, int sdpMLineIndex);

        void onIceConnected();

        void onIceDisconnectedOrFailed(@NonNull String reason);
    }

    /** Khởi tạo PeerConnection audio-only với danh sách STUN/TURN do server cấp. */
    void initialize(@NonNull List<CallDtos.IceServerDto> iceServers, @NonNull Listener listener);

    /** Phía người gọi tạo SDP Offer và gửi qua onLocalOffer. */
    void startOffer();

    /** Phía người nhận nạp SDP Offer từ xa, xả ICE đệm và tạo SDP Answer gửi qua onLocalAnswer. */
    void acceptOffer(@NonNull String remoteOfferSdp);

    /** Phía người gọi nạp SDP Answer từ xa và xả toàn bộ ICE ứng viên đến sớm. */
    void applyAnswer(@NonNull String remoteAnswerSdp);

    /** Thêm ứng viên ICE từ xa (tự đệm lại nếu remote description chưa hoàn tất). */
    void addRemoteIceCandidate(@NonNull String candidate, @Nullable String sdpMid, int sdpMLineIndex);

    /** Bật/tắt micro của luồng âm thanh cục bộ. */
    void setMicMuted(boolean muted);

    /** Chuyển đổi định tuyến âm thanh giữa loa ngoài và loa trong/tai nghe. */
    void setSpeakerEnabled(boolean enabled);

    /** Giải phóng tuyệt đối PeerConnection, AudioTrack, AudioFocus và tài nguyên native (idempotent). */
    void close();
}
