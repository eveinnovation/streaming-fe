package com.eveinnovation.streamingplatform.handler;

import bbm.webrtc.rtc4j.core.DataChannel;
import bbm.webrtc.rtc4j.core.RTC;
import bbm.webrtc.rtc4j.core.audio.AudioCapturer;
import bbm.webrtc.rtc4j.core.observer.PeerConnectionObserver;
import bbm.webrtc.rtc4j.core.video.VideoCapturer;
import bbm.webrtc.rtc4j.model.*;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.eveinnovation.streamingplatform.common.Constants;
import com.eveinnovation.streamingplatform.common.NamedThreadFactory;
import com.eveinnovation.streamingplatform.config.WebRtcTurnConfig;
import com.eveinnovation.streamingplatform.use.UseContext;
import com.eveinnovation.streamingplatform.util.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author bbm
 */
@SuppressWarnings("unused")
@Component
@Slf4j
public class MessageHandler {

    private static final int THREAD_SIZE = 10;
    //aici white list
    private static final String WHITE_PRIVATE_IP_PREFIX = "19";
    private static final int MIN_PORT = 50000;
    private static final int MAX_PORT = 51000;
    private static final boolean HARDWARE_ACCELERATE = true;
    private static final int MAX_BIT_RATE = 2 * 1024 * 1024;
    private static int f_idx = 0;

    @Autowired
    private WebRtcTurnConfig webRtcTurnConfig;

    private final Map<UUID, UseContext> useContextMap = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    public MessageHandler() {
        executor = new ThreadPoolExecutor(
                THREAD_SIZE, THREAD_SIZE,
                Integer.MAX_VALUE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new NamedThreadFactory("Message", THREAD_SIZE));
    }

    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        stopUse(client);
    }

    @SuppressWarnings("WeakerAccess")
    @OnEvent(Constants.STOP_USE)
    public void stopUse(SocketIOClient client) {
        synchronized (useContextMap) {
            UseContext previousContext = useContextMap.remove(client.getSessionId());
            if (!Objects.isNull(previousContext)) {
                stop(previousContext);
            }
        }
    }

    @OnEvent(Constants.BEGIN_USE)
    public void beginUse(SocketIOClient client) {
        synchronized (useContextMap) {
            UseContext newUseContext = new UseContext(client);
            UseContext previousContext = useContextMap.put(client.getSessionId(), newUseContext);
            if (!Objects.isNull(previousContext)) {
                stop(previousContext);
            }
            start(newUseContext);
        }
    }

    @OnEvent(Constants.BEGIN_WEB_RTC)
    public void beginWebRtc(SocketIOClient client) {
        getContextAndRunAsync(client.getSessionId(), context ->
                context.executeInLock(() -> {
                    log.info("Create rtc core...");
//                    ffmpeg -fflags discardcorrupt -i rtp://192.168.1.191:1240 -an -c:v copy -f rtp -sdp_file video.sdp "rtp://192.168.1.191:5004"
//                    FFmpegFrameGrabber frameGrabber = getfFmpegFrameGrabber();


                    context.setRtc(new RTC(new AudioCapturer() {

                        private volatile InputStream testAudio;
                        private volatile ByteBuffer directByteBuffer;

                        @Override
                        public void close() {

                        }

                        @Override
                        public int samplingFrequency() {
                            return 44100;
                        }

                        @Override
                        public ByteBuffer capture(int size) {
                            if (directByteBuffer == null) {
                                directByteBuffer = ByteBuffer.allocateDirect(size);
                            }
                            directByteBuffer.clear();
                            try {
                                byte[] data = new byte[size];
                                while (directByteBuffer.hasRemaining()) {
                                    if (testAudio == null) {
                                        // 16-bit 44100Hz mono
                                        testAudio = this.getClass().getResourceAsStream("/gong.wav");
                                    }
                                    try {
                                        int length = testAudio.read(data, 0, directByteBuffer.remaining());
                                        directByteBuffer.put(data, 0, length);
                                        if (testAudio.available() == 0) {
                                            testAudio.close();
                                            testAudio = null;
                                        }
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            return directByteBuffer;
                        }
                    }, new VideoCapturer() {


                        private volatile ByteBuffer sourceBuffer;
                        private volatile int totalSize;
                        private volatile ByteBuffer sourceBuffer2;
                        private volatile int totalSize2;

                        @Override
                        public void close() {

                        }

                        @Override
                        public int getWidth() {
                            return 1920;
                        }

                        @Override
                        public int getHeight() {
                            return 1080;
                        }

                        @Override
                        public int getFps() {
                            return 24;
                        }

                        @Override
                        public VideoFrame capture() {

//                            Frame frame;
//
//                            try {
//
//                                frame = frameGrabber.grabImage();
//                                BufferedImage bi = new Java2DFrameConverter().convert(frame);
//
//                                byte[] bytes = ImageUtils.toByteArray(bi, "jpg");
//
//
//                                try (InputStream imageStream = new ByteArrayInputStream(bytes)) {
//                                    sourceBuffer = ByteBuffer.allocateDirect(imageStream.available());
//                                    totalSize = 0;
//                                    byte[] tmp = new byte[1024];
//                                    while (imageStream.available() > 0) {
//                                        int readSize = imageStream.read(tmp);
//                                        if (readSize <= 0) {
//                                            break;
//                                        }
//                                        totalSize += readSize;
//                                        sourceBuffer.put(tmp, 0, readSize);
//                                    }
//                                } catch (Exception e) {
//                                    e.printStackTrace();
//                                }
//
//                                return new VideoFrame(0, System.currentTimeMillis(), sourceBuffer, totalSize);
//                            } catch (IOException e) {
//                                e.printStackTrace();
//                            }

                            if (sourceBuffer == null) {
                                try (InputStream imageStream = this.getClass().getResourceAsStream("/test.jpg")) {
                                    sourceBuffer = ByteBuffer.allocateDirect(imageStream.available());
                                    totalSize = 0;
                                    byte[] tmp = new byte[1024];
                                    while (imageStream.available() > 0) {
                                        int readSize = imageStream.read(tmp);
                                        if (readSize <= 0) {
                                            break;
                                        }
                                        totalSize += readSize;
                                        sourceBuffer.put(tmp, 0, readSize);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                            return new VideoFrame(0, System.currentTimeMillis(), sourceBuffer, totalSize);

//                            return getFrame(f_idx);
                        }



                        private VideoFrame getFrame(int index) {
                            System.out.println(index);
                            Frame frame;
                            if (sourceBuffer == null) {
                                try (InputStream imageStream = new FileInputStream("/home/ovidiu/proiecte/webrtc-streaming/streaming-platform/frame"+index+"_.jpg")) {
                                    sourceBuffer = ByteBuffer.allocateDirect(imageStream.available());
                                    totalSize = 0;
                                    byte[] tmp = new byte[1024];
                                    while (imageStream.available() > 0) {
                                        int readSize = imageStream.read(tmp);
                                        if (readSize <= 0) {
                                            break;
                                        }
                                        totalSize += readSize;
                                        sourceBuffer.put(tmp, 0, readSize);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                            return new VideoFrame(0, System.currentTimeMillis(), sourceBuffer, totalSize);
                        }

                    }, WHITE_PRIVATE_IP_PREFIX,
                            MIN_PORT, MAX_PORT, HARDWARE_ACCELERATE, client.getSessionId().toString()));
                    log.info("Create peer connection...");
                    context.setPeerConnection(context.getRtc().createPeerConnection(new PeerConnectionObserver() {
                        @Override
                        public void onIceCandidate(IceCandidate iceCandidate) {
                            log.info("on ice candidate, {}", iceCandidate);
                            executor.submit(() -> client.sendEvent(Constants.ON_CANDIDATE, iceCandidate));
                        }

                        @Override
                        public void onSignalingChange(int state) {
                            log.info("On signaling change, {}", SignalingState.getByIndex(state));
                        }

                        @Override
                        public void onDataChannel(DataChannel dataChannel) {
                            context.setDataChannel(dataChannel);
                            log.info("On data channel, {}", dataChannel);
                        }

                        @Override
                        public void onRenegotiationNeeded() {
                            log.info("On renegotiation needed");
                        }
                    }, webRtcTurnConfig.getTurnServers(), MAX_BIT_RATE));
                    log.info("Start peer connection transport...");
                    context.getPeerConnection().createDataChannel("test", new DataChannelConfig(),
                            dataBuffer -> {
                                log.info("Received data channel data, {}", dataBuffer);
                                context.getDataChannel().send(new DataBuffer("pong".getBytes(), false));
                            });
                    context.getPeerConnection().startTransport();
                    log.info("Create peer connection offer...");
                    context.getPeerConnection().createOffer(sdp ->
                            executor.submit(() -> {
                                try {
                                    context.getPeerConnection().setLocalDescription(sdp);
                                    client.sendEvent(Constants.OFFER_SDP, sdp);
                                } catch (Exception e) {
                                    log.error("Handle create offer error", e);
                                }
                            }));
                }));
    }



    @OnEvent(Constants.BANDWIDTH)
    public void changeBandwidth(SocketIOClient client, int newBandwidth) {
        getContextAndRunAsync(client.getSessionId(), context ->
                context.executeInLock(() -> {
                    try {
                        context.getPeerConnection().changeBitrate(newBandwidth);
                    } catch (Exception e) {
                        log.error("Handle change bandwidth error", e);
                    }
                }));
    }

    @OnEvent(Constants.ANSWER_SDP)
    public void answerSdp(SocketIOClient client, SessionDescription sessionDescription) {
        log.info("Received a answer sdp, {}", sessionDescription.getType());
        getContextAndRunAsync(client.getSessionId(), context ->
                context.executeInLock(() -> {
                    try {
                        context.getPeerConnection().setRemoteDescription(sessionDescription);
                    } catch (Exception e) {
                        log.error("Handle answer sdp error", e);
                    }
                }));
    }

    @OnEvent(Constants.ON_CANDIDATE)
    public void onCandidate(SocketIOClient client, IceCandidate iceCandidate) {
        log.info("Received a ice candidate, {}", iceCandidate);
        getContextAndRunAsync(client.getSessionId(), context ->
                context.executeInLock(() -> {
                    try {
                        context.getPeerConnection().addIceCandidate(iceCandidate);
                    } catch (Exception e) {
                        log.error("Handle ice candidate error", e);
                    }
                }));
    }

    private void start(UseContext context) {
        context.executeInLock(() -> {
            log.info("Begin use, {}", context.getClient().getSessionId());
            context.getClient().sendEvent(Constants.STATUS, true);
        });
    }

    private void stop(UseContext context) {
        context.executeInLock(() -> {
            log.info("Stop use, {}", context.getClient().getSessionId());
            context.releaseRtc();
        });
    }

    private void getContextAndRunAsync(UUID uuid, Function function) {
        synchronized (useContextMap) {
            UseContext context = useContextMap.get(uuid);
            if (!Objects.isNull(context)) {
                executor.submit(() -> function.apply(context));
            }
        }
    }

    interface Function {
        void apply(UseContext useContext);
    }


    private FFmpegFrameGrabber getfFmpegFrameGrabber() {
        FFmpegFrameGrabber frameGrabber = new FFmpegFrameGrabber("http://localhost/1883.S01E03.River.2160p.WEB-DL.DDP5.1.H.265-NTb.mkv");
        frameGrabber.setFormat("matroska");
        frameGrabber.setFrameRate(30);

        try {
            frameGrabber.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            e.printStackTrace();
        }
        return frameGrabber;
    }




}
