package com.utechat.data.call;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.utechat.data.remote.model.CallDtos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

/**
 * Triển khai thật WebRtcCallEngine bằng io.getstream:stream-webrtc-android:1.3.10 (100% Java 17):
 * - Audio-only (VOICE_COMMUNICATION, khử vọng AEC & lọc ồn NS phần cứng khi thiết bị hỗ trợ).
 * - Đệm an toàn ứng viên ICE đến sớm (pendingRemoteIce) cho tới khi setRemoteDescription gọi onSetSuccess().
 * - Định tuyến âm thanh tương thích API 26–36 (setCommunicationDevice trên API 31+, fallback setSpeakerphoneOn API 26–30)
 *   và khôi phục trọn vẹn AudioFocus/mode khi đóng cuộc gọi.
 */
public final class StreamWebRtcCallEngine implements WebRtcCallEngine {
    private static final String TAG = "StreamWebRtcCallEngine";
    private static final String AUDIO_TRACK_ID = "UTEChat_AUDIO_0";
    private static final String STREAM_ID = "UTEChat_STREAM";

    private static final AtomicBoolean WEBRTC_GLOBAL_INITIALIZED = new AtomicBoolean(false);

    private final Context appContext;
    private final AudioManager audioManager;
    private final Object stateLock = new Object();
    private final List<IceCandidate> pendingRemoteIce = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Nullable private Listener listener;
    @Nullable private JavaAudioDeviceModule audioDeviceModule;
    @Nullable private PeerConnectionFactory factory;
    @Nullable private PeerConnection peerConnection;
    @Nullable private AudioSource audioSource;
    @Nullable private AudioTrack localAudioTrack;
    @Nullable private AudioFocusRequest audioFocusRequest;

    private boolean remoteDescriptionSet = false;
    private boolean micMuted = false;
    private boolean speakerEnabled = false;
    private boolean audioConfigured = false;
    private int previousAudioMode = AudioManager.MODE_NORMAL;

    public StreamWebRtcCallEngine(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) this.appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void initialize(@NonNull List<CallDtos.IceServerDto> iceServers, @NonNull Listener listener) {
        if (closed.get()) {
            return;
        }
        synchronized (stateLock) {
            if (closed.get()) {
                return;
            }
            this.listener = listener;
            ensureGlobalWebRtcInitialized();
            configureAudioForCall();

            JavaAudioDeviceModule.Builder admBuilder = JavaAudioDeviceModule.builder(appContext)
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                    .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported());
            this.audioDeviceModule = admBuilder.createAudioDeviceModule();

            this.factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(this.audioDeviceModule)
                    .createPeerConnectionFactory();

            PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(toWebRtcIceServers(iceServers));
            rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

            this.peerConnection = this.factory.createPeerConnection(rtcConfig, new PeerConnectionObserver());
            if (this.peerConnection == null) {
                notifyDisconnected("PEER_CONNECTION_INIT_FAILED");
                return;
            }

            MediaConstraints audioConstraints = new MediaConstraints();
            this.audioSource = this.factory.createAudioSource(audioConstraints);
            this.localAudioTrack = this.factory.createAudioTrack(AUDIO_TRACK_ID, this.audioSource);
            this.localAudioTrack.setEnabled(!micMuted);
            this.peerConnection.addTrack(this.localAudioTrack, Collections.singletonList(STREAM_ID));
            applySpeakerRoutingLocked(speakerEnabled);
        }
    }

    @Override
    public void startOffer() {
        PeerConnection pc;
        synchronized (stateLock) {
            if (closed.get() || peerConnection == null) {
                return;
            }
            pc = peerConnection;
        }
        pc.createOffer(new SimpleSdpObserver("createOffer") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                if (closed.get() || sdp == null) {
                    return;
                }
                pc.setLocalDescription(new SimpleSdpObserver("setLocalOffer") {
                    @Override
                    public void onSetSuccess() {
                        Listener l = listener;
                        if (!closed.get() && l != null) {
                            l.onLocalOffer(sdp.description);
                        }
                    }

                    @Override
                    public void onSetFailure(String error) {
                        notifyDisconnected("SET_LOCAL_OFFER_FAILED");
                    }
                }, sdp);
            }

            @Override
            public void onCreateFailure(String error) {
                notifyDisconnected("CREATE_OFFER_FAILED");
            }
        }, audioOnlyConstraints());
    }

    @Override
    public void acceptOffer(@NonNull String remoteOfferSdp) {
        PeerConnection pc;
        synchronized (stateLock) {
            if (closed.get() || peerConnection == null) {
                return;
            }
            pc = peerConnection;
        }
        SessionDescription remoteOffer = new SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp);
        pc.setRemoteDescription(new SimpleSdpObserver("setRemoteOffer") {
            @Override
            public void onSetSuccess() {
                if (closed.get()) {
                    return;
                }
                drainPendingRemoteIce(pc);
                pc.createAnswer(new SimpleSdpObserver("createAnswer") {
                    @Override
                    public void onCreateSuccess(SessionDescription answerSdp) {
                        if (closed.get() || answerSdp == null) {
                            return;
                        }
                        pc.setLocalDescription(new SimpleSdpObserver("setLocalAnswer") {
                            @Override
                            public void onSetSuccess() {
                                Listener l = listener;
                                if (!closed.get() && l != null) {
                                    l.onLocalAnswer(answerSdp.description);
                                }
                            }

                            @Override
                            public void onSetFailure(String error) {
                                notifyDisconnected("SET_LOCAL_ANSWER_FAILED");
                            }
                        }, answerSdp);
                    }

                    @Override
                    public void onCreateFailure(String error) {
                        notifyDisconnected("CREATE_ANSWER_FAILED");
                    }
                }, audioOnlyConstraints());
            }

            @Override
            public void onSetFailure(String error) {
                notifyDisconnected("SET_REMOTE_OFFER_FAILED");
            }
        }, remoteOffer);
    }

    @Override
    public void applyAnswer(@NonNull String remoteAnswerSdp) {
        PeerConnection pc;
        synchronized (stateLock) {
            if (closed.get() || peerConnection == null) {
                return;
            }
            pc = peerConnection;
        }
        SessionDescription remoteAnswer = new SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp);
        pc.setRemoteDescription(new SimpleSdpObserver("setRemoteAnswer") {
            @Override
            public void onSetSuccess() {
                if (!closed.get()) {
                    drainPendingRemoteIce(pc);
                }
            }

            @Override
            public void onSetFailure(String error) {
                notifyDisconnected("SET_REMOTE_ANSWER_FAILED");
            }
        }, remoteAnswer);
    }

    @Override
    public void addRemoteIceCandidate(@NonNull String candidate, @Nullable String sdpMid, int sdpMLineIndex) {
        IceCandidate ice = new IceCandidate(sdpMid != null ? sdpMid : "", sdpMLineIndex, candidate);
        PeerConnection pcToAdd = null;
        synchronized (stateLock) {
            if (closed.get()) {
                return;
            }
            if (!remoteDescriptionSet || peerConnection == null) {
                // Đệm lại ICE đến sớm trước khi setRemoteDescription hoàn tất (tránh rơi kết nối).
                pendingRemoteIce.add(ice);
            } else {
                pcToAdd = peerConnection;
            }
        }
        if (pcToAdd != null) {
            pcToAdd.addIceCandidate(ice);
        }
    }

    @Override
    public void setMicMuted(boolean muted) {
        synchronized (stateLock) {
            micMuted = muted;
            if (localAudioTrack != null && !closed.get()) {
                localAudioTrack.setEnabled(!muted);
            }
        }
    }

    @Override
    public void setSpeakerEnabled(boolean enabled) {
        synchronized (stateLock) {
            speakerEnabled = enabled;
            if (!closed.get() && audioConfigured) {
                applySpeakerRoutingLocked(enabled);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        PeerConnection pcToClose;
        AudioTrack trackToDispose;
        AudioSource sourceToDispose;
        PeerConnectionFactory factoryToDispose;
        JavaAudioDeviceModule admToRelease;
        synchronized (stateLock) {
            pendingRemoteIce.clear();
            remoteDescriptionSet = false;
            listener = null;
            pcToClose = peerConnection;
            peerConnection = null;
            trackToDispose = localAudioTrack;
            localAudioTrack = null;
            sourceToDispose = audioSource;
            audioSource = null;
            factoryToDispose = factory;
            factory = null;
            admToRelease = audioDeviceModule;
            audioDeviceModule = null;
            restoreAudioAfterCallLocked();
        }
        closeQuietly(() -> {
            if (pcToClose != null) {
                pcToClose.close();
                pcToClose.dispose();
            }
        });
        closeQuietly(() -> {
            if (trackToDispose != null) {
                trackToDispose.setEnabled(false);
                trackToDispose.dispose();
            }
        });
        closeQuietly(() -> {
            if (sourceToDispose != null) {
                sourceToDispose.dispose();
            }
        });
        closeQuietly(() -> {
            if (factoryToDispose != null) {
                factoryToDispose.dispose();
            }
        });
        closeQuietly(() -> {
            if (admToRelease != null) {
                admToRelease.release();
            }
        });
    }

    // ======================== HỖ TRỢ NỘI BỘ ========================

    private void ensureGlobalWebRtcInitialized() {
        if (WEBRTC_GLOBAL_INITIALIZED.compareAndSet(false, true)) {
            PeerConnectionFactory.InitializationOptions options =
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(options);
        }
    }

    private void drainPendingRemoteIce(@NonNull PeerConnection pc) {
        List<IceCandidate> toDrain;
        synchronized (stateLock) {
            remoteDescriptionSet = true;
            if (pendingRemoteIce.isEmpty()) {
                return;
            }
            toDrain = new ArrayList<>(pendingRemoteIce);
            pendingRemoteIce.clear();
        }
        for (IceCandidate ice : toDrain) {
            if (closed.get()) {
                return;
            }
            pc.addIceCandidate(ice);
        }
    }

    private void configureAudioForCall() {
        if (audioManager == null || audioConfigured) {
            return;
        }
        previousAudioMode = audioManager.getMode();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChange -> { })
                .build();
        audioManager.requestAudioFocus(audioFocusRequest);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioConfigured = true;
    }

    private void applySpeakerRoutingLocked(boolean enabled) {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            AudioDeviceInfo chosen = null;
            if (enabled) {
                for (AudioDeviceInfo dev : devices) {
                    if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        chosen = dev;
                        break;
                    }
                }
            } else {
                // Ưu tiên tai nghe Bluetooth/dây nếu đang cắm, sau đó mới rơi về loa trong earpiece.
                for (AudioDeviceInfo dev : devices) {
                    int type = dev.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        chosen = dev;
                        break;
                    }
                }
                if (chosen == null) {
                    for (AudioDeviceInfo dev : devices) {
                        if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                            chosen = dev;
                            break;
                        }
                    }
                }
            }
            if (chosen != null) {
                audioManager.setCommunicationDevice(chosen);
            } else {
                audioManager.clearCommunicationDevice();
            }
        } else {
            setSpeakerphoneLegacy(enabled);
        }
    }

    private void restoreAudioAfterCallLocked() {
        if (audioManager == null || !audioConfigured) {
            return;
        }
        audioConfigured = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            closeQuietly(audioManager::clearCommunicationDevice);
        } else {
            closeQuietly(() -> setSpeakerphoneLegacy(false));
        }
        closeQuietly(() -> audioManager.setMode(previousAudioMode));
        if (audioFocusRequest != null) {
            AudioFocusRequest req = audioFocusRequest;
            audioFocusRequest = null;
            closeQuietly(() -> audioManager.abandonAudioFocusRequest(req));
        }
    }

    @SuppressWarnings("deprecation")
    private void setSpeakerphoneLegacy(boolean enabled) {
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(enabled);
        }
    }

    private void notifyDisconnected(@NonNull String reason) {
        Listener l = listener;
        if (!closed.get() && l != null) {
            l.onIceDisconnectedOrFailed(reason);
        }
    }

    private static MediaConstraints audioOnlyConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        return constraints;
    }

    private static List<PeerConnection.IceServer> toWebRtcIceServers(@NonNull List<CallDtos.IceServerDto> dtos) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        for (CallDtos.IceServerDto dto : dtos) {
            if (dto == null || dto.urls == null || dto.urls.isEmpty()) {
                continue;
            }
            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(dto.urls);
            if (dto.username != null && !dto.username.isEmpty()) {
                builder.setUsername(dto.username);
            }
            if (dto.credential != null && !dto.credential.isEmpty()) {
                builder.setPassword(dto.credential);
            }
            result.add(builder.createIceServer());
        }
        return result;
    }

    private static void closeQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "Bỏ qua lỗi giải phóng tài nguyên WebRTC/Audio: " + e.getMessage());
        }
    }

    /** Observer chuyển tiếp ICE candidate và trạng thái kết nối ICE lên CallSessionCoordinator. */
    private final class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState newState) { }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Listener l = listener;
            if (closed.get() || l == null || newState == null) {
                return;
            }
            switch (newState) {
                case CONNECTED:
                case COMPLETED:
                    l.onIceConnected();
                    break;
                case DISCONNECTED:
                    l.onIceDisconnectedOrFailed("DISCONNECTED");
                    break;
                case FAILED:
                    l.onIceDisconnectedOrFailed("FAILED");
                    break;
                case CLOSED:
                    l.onIceDisconnectedOrFailed("CLOSED");
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) { }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) { }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Listener l = listener;
            if (!closed.get() && l != null && iceCandidate != null) {
                l.onLocalIceCandidate(iceCandidate.sdp, iceCandidate.sdpMid, iceCandidate.sdpMLineIndex);
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) { }

        @Override
        public void onAddStream(MediaStream mediaStream) { }

        @Override
        public void onRemoveStream(MediaStream mediaStream) { }

        @Override
        public void onDataChannel(DataChannel dataChannel) { }

        @Override
        public void onRenegotiationNeeded() { }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) { }

        @Override
        public void onTrack(RtpTransceiver transceiver) { }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        private final String tag;

        SimpleSdpObserver(String tag) {
            this.tag = tag;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) { }

        @Override
        public void onSetSuccess() { }

        @Override
        public void onCreateFailure(String error) {
            Log.w(TAG, tag + " createFailure: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.w(TAG, tag + " setFailure: " + error);
        }
    }
}
