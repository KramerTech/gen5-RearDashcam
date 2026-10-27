package dev.skramer.dashcam;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import java.nio.ByteBuffer;

/**
 * Camera to hardware H.264 encoder to {@link SegmentWriter}. Everything here runs on the main
 * looper thread, where the camera delivers its callbacks.
 * <p>
 * Frames are taken as YUYV, the format the camera captures in, and converted to I420 here: the
 * HAL's own conversion to its other preview formats (yuv420p, yuv420sp) overruns its buffer and
 * crashes mediaserver.
 */
@SuppressWarnings("deprecation")
final class Recorder implements Camera.PreviewCallback, Camera.ErrorCallback {
    /** Gen5W's VPU H.264 encoder. It isn't in media_codecs.xml, but MediaCodec can open it by name. */
    private static final String ENCODER = "OMX.TCC.ENC.avc";
    private static final int CAMERA_ID = 0;
    private static final int CALLBACK_BUFFERS = 4;
    /** The camera's frame period (NTSC, 59.94 Hz). Timestamps snap to it: arrival times jitter. */
    private static final long TICK_US = 1_000_000L * 1001 / 60_000;

    private final Config config;
    private final Handler handler;
    private final SegmentWriter writer;
    private final int width, height, ySize, cSize, frameLength;
    private final byte[] i420;
    private final int ticksPerFrame;

    private Camera camera;
    private SurfaceTexture texture;
    private MediaCodec encoder;
    private ByteBuffer[] inputs, outputs;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] codecConfig;
    private volatile boolean active;
    private long startNs, nextTick, lastPtsUs;
    private int dropped;
    private long statsStartMs, convertNs;
    private int encoded;

    Recorder(Config config, Handler handler) {
        this.config = config;
        this.handler = handler;
        width = config.width;
        height = config.height;
        ySize = width * height;
        cSize = ySize / 4;
        frameLength = ySize * 2;
        i420 = new byte[ySize + 2 * cSize];
        ticksPerFrame = Math.max(1, Math.round(60_000f / 1001 / config.fps));
        writer = new SegmentWriter(config, new Storage(config), new SegmentWriter.Failure() {
            @Override
            public void onWriteFailed() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        stop();
                    }
                });
            }
        });
        writer.start();
    }

    /** True while the camera is open. Read from other threads. */
    boolean isActive() {
        return active;
    }

    void start() {
        if (active) return;
        try {
            if (config.debugNoCamera) {
                startBlank();
                return;
            }
            if (!config.debugNoEncode) encoder = MediaCodec.createByCodecName(ENCODER);
            if (encoder != null) {
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", width, height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            f.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);  // segments start on keyframes
            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            inputs = encoder.getInputBuffers();
            outputs = encoder.getOutputBuffers();
            }

            camera = CameraOpener.open(CAMERA_ID);
            Camera.Parameters p = camera.getParameters();
            p.setPreviewSize(width, height);
            p.setPreviewFormat(ImageFormat.YUY2);
            camera.setParameters(p);
            camera.setErrorCallback(this);
            // The HAL needs a preview target before it delivers frames; nothing reads this one.
            texture = new SurfaceTexture(10);
            camera.setPreviewTexture(texture);
            camera.setPreviewCallbackWithBuffer(this);
            for (int i = 0; i < CALLBACK_BUFFERS; i++) camera.addCallbackBuffer(new byte[frameLength]);
            camera.startPreview();
            startNs = System.nanoTime();
            nextTick = 0;
            lastPtsUs = -1;
            statsStartMs = android.os.SystemClock.uptimeMillis();
            convertNs = 0;
            encoded = 0;
            active = true;
            Log.i("camera started");
        } catch (Throwable t) {
            Log.e("can't start recording", t);
            stop();
        }
    }

    /** Releases the camera first, so the stock reverse view can open it as soon as possible. */
    void stop() {
        boolean was = active;
        active = false;
        if (camera != null) {
            Camera c = camera;
            camera = null;
            try {
                c.setPreviewCallbackWithBuffer(null);
                c.stopPreview();
            } catch (Throwable ignored) {
                // Released below either way.
            }
            try {
                c.release();
            } catch (Throwable t) {
                Log.e("camera release failed", t);
            }
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Throwable ignored) {
                // Releasing anyway.
            }
            encoder.release();
            encoder = null;
        }
        if (texture != null) {
            texture.release();
            texture = null;
        }
        codecConfig = null;
        writer.end();
        if (was) Log.i("camera released");
    }

    @Override
    public void onError(int error, Camera c) {
        Log.w("camera error " + error);
        if (c == camera) stop();  // the guard restarts it
    }

    /**
     * Keeps every n-th camera frame on the camera's 59.94 Hz tick grid. Frames are placed on the
     * tick nearest their arrival; the next frame kept is the first at or past the target tick, and
     * the target advances by exactly n, so the cadence stays even when callbacks arrive late.
     */
    @Override
    public void onPreviewFrame(byte[] data, Camera c) {
        if (!active || c != camera || data == null) return;
        long tick = ((System.nanoTime() - startNs) / 1000 + TICK_US / 2) / TICK_US;
        if (tick >= nextTick) {
            // Fell more than a frame behind (a stall): restart the cadence here.
            nextTick = tick - nextTick >= ticksPerFrame ? tick + ticksPerFrame : nextTick + ticksPerFrame;
            long pts = tick * TICK_US;
            if (pts <= lastPtsUs) pts = lastPtsUs + TICK_US;
            lastPtsUs = pts;
            if (encoder != null) {
                encode(data, pts);
                drain();
                logStats();
            }
        }
        c.addCallbackBuffer(data);
    }

    /** Converts a YUYV frame to I420 (chroma from even rows) and queues it to the encoder. */
    private void encode(byte[] yuyv, long ptsUs) {
        int i = encoder.dequeueInputBuffer(0);
        if (i < 0) {
            if (dropped++ % 100 == 0) Log.w("encoder busy; dropped " + dropped + " frames so far");
            return;
        }
        long t0 = System.nanoTime();
        byte[] out = i420;
        int w = width, y = 0;
        int u = ySize, v = ySize + cSize;
        if (config.swapUv) {
            u = ySize + cSize;
            v = ySize;
        }
        for (int row = 0, s = 0; row < height; row++) {
            int end = s + w * 2;
            if ((row & 1) == 0) {
                for (; s < end; s += 4) {
                    out[y++] = yuyv[s];
                    out[u++] = yuyv[s + 1];
                    out[y++] = yuyv[s + 2];
                    out[v++] = yuyv[s + 3];
                }
            } else {
                for (; s < end; s += 4) {
                    out[y++] = yuyv[s];
                    out[y++] = yuyv[s + 2];
                }
            }
        }
        ByteBuffer in = inputs[i];
        in.clear();
        in.put(out);
        convertNs += System.nanoTime() - t0;
        encoder.queueInputBuffer(i, 0, out.length, ptsUs, 0);
        encoded++;
    }

    /** Debugging: the encoder alone, fed blank frames at the configured rate. */
    private void startBlank() throws java.io.IOException {
        encoder = MediaCodec.createByCodecName(ENCODER);
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", width, height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        f.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        inputs = encoder.getInputBuffers();
        outputs = encoder.getOutputBuffers();
        startNs = System.nanoTime();
        lastPtsUs = -1;
        statsStartMs = android.os.SystemClock.uptimeMillis();
        active = true;
        final byte[] blank = new byte[frameLength];
        for (int i = 0; i < blank.length; i += 2) blank[i + 1] = (byte) 128;
        Log.i("debug: encoder only, blank frames");
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                long pts = (System.nanoTime() - startNs) / 1000;
                encode(blank, pts);
                drain();
                logStats();
                handler.postDelayed(this, 1000 / config.fps);
            }
        });
    }

    /** Once a minute: frame rate and conversion cost, to watch the CPU load. */
    private void logStats() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - statsStartMs < 60_000 || encoded == 0) return;
        Log.i(String.format(java.util.Locale.US, "%.1f fps, conversion %.1f ms/frame, %d dropped",
                encoded * 1000f / (now - statsStartMs), convertNs / 1e6 / encoded, dropped));
        statsStartMs = now;
        convertNs = 0;
        encoded = 0;
    }

    /** Hands finished frames to the writer without blocking. */
    private void drain() {
        while (true) {
            int o = encoder.dequeueOutputBuffer(info, 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputs = encoder.getOutputBuffers();
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = encoder.getOutputFormat();
                if (f.containsKey("csd-0") && f.containsKey("csd-1")) {
                    codecConfig = concat(bytes(f.getByteBuffer("csd-0")), bytes(f.getByteBuffer("csd-1")));
                }
            } else if (o >= 0) {
                ByteBuffer b = outputs[o];
                b.position(info.offset);
                b.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                b.get(data);
                encoder.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codecConfig = data;
                } else if (data.length > 0) {
                    writer.frame(data, info.presentationTimeUs,
                            (info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0, codecConfig);
                }
            }
        }
    }

    private static byte[] bytes(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        d.position(0);
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
