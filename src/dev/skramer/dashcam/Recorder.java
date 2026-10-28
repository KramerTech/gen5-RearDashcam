package dev.skramer.dashcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Camera to hardware H.264 encoder to {@link SegmentWriter}.
 * <p>
 * The camera runs as it does for the reverse view ({@link RearCam}), and its hardware writes
 * each frame into a ring of four buffers in RAM ({@link CameraRam}), already scaled to the
 * recording size. Nothing says when a frame is complete, so a watcher thread polls a strip near
 * the top of each buffer: when one changes, the hardware has started writing that buffer, so the
 * one it wrote before is complete, and stays untouched while the next three are written (about
 * 50 ms). A copier thread copies out the frames kept for the frame rate; the main looper thread
 * converts them to I420 and encodes them. Starting and stopping also happen on the main thread.
 * <p>
 * For reverse ({@link #handOff}) it lets go of the camera but keeps recording: the stock reverse
 * view's own camera session writes its frames to the same ring, at its own size, which is worked
 * out from which parts of the memory change ({@link #findStockSize}); its frames are scaled to
 * the recording size as they're copied. Once the reverse view is done, {@link #takeBack} opens
 * the camera again, into the same recording.
 * <p>
 * For the app's camera debug screen it can also run a debug session: the camera and the feed at
 * a size the screen asks for, checked but not recorded, with {@link #debugStatus} for the screen.
 */
@SuppressWarnings("deprecation")
final class Recorder {
    /** Gen5W's VPU H.264 encoder. It isn't in media_codecs.xml, but MediaCodec can open it by name. */
    private static final String ENCODER = "OMX.TCC.ENC.avc";
    /** Frames copied out and waiting for the encoder, at most. */
    private static final int FRAME_BUFFERS = 2;
    /** The camera's frame period (NTSC, 59.94 Hz). Timestamps snap to it: arrival times jitter. */
    private static final long TICK_US = 1_000_000L * 1001 / 60_000;
    /**
     * How often the watcher looks for a buffer being written; a frame takes about 16.7 ms, so
     * after it sees one start, it can wait WATCH_AFTER_START_MS before looking for the next.
     */
    private static final long WATCH_MS = 2, WATCH_AFTER_START_MS = 12;
    /** No new frame for this long: the camera has stopped sending. After STALL_RESTART_MS, start it again. */
    private static final long STALL_MS = 2_000, STALL_RESTART_MS = 60_000;
    /** How long stopping waits for the feed's threads, which only ever block for one frame copy. */
    private static final long JOIN_MS = 200;
    /** The camera still draws its guide lines for a couple of frames after they're turned off. */
    private static final long GUIDE_LINES_SETTLE_NS = 300_000_000L;
    private static final long STATS_MS = 30_000;
    /**
     * Frame sizes the stock view might use (the analog camera's is 1140x720), and how the camera
     * memory is sampled to find out which: 16 pixels from the middle of every 16 KiB, read
     * FIND_PASSES times FIND_PASS_MS apart.
     */
    private static final int[][] STOCK_SIZES = {{1140, 720}, {1280, 720}, {1920, 720}, {1780, 720}, {1420, 538},
            {1280, 538}, {1280, 480}, {960, 720}, {760, 480}, {720, 480}};
    private static final int FIND_BLOCK = 16384, FIND_PROBE = 16, FIND_PASSES = 6;
    private static final long FIND_PASS_MS = 60;

    private final Config config;
    private final Handler handler;
    private final SegmentWriter writer;
    private final GuideLines guideLines;
    private final int ticksPerFrame;
    /** This session's size, set on start. */
    private int width, height, ySize, cSize;
    private byte[] i420;

    /** Mapped on first use, never unmapped. */
    private CameraRam ram;
    private RearCam camera;
    private Feed feed;
    private MediaCodec encoder;
    private ByteBuffer[] inputs, outputs;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] codecConfig;
    private volatile boolean active;
    private long startNs, lastPtsUs, skipUntilNs;
    private int dropped;
    private String noPicture;
    private long statsStartMs, convertNs, copiesAtStats, copyNsAtStats;
    private int encoded;
    private final Perf perf = new Perf();
    /** Recording the stock reverse view's frames (we don't have the camera). */
    private volatile boolean passive;
    /** The thread looking for the stock view's frames, while passive and there's no feed. */
    private Thread finder;
    /** This reverse period, for its summary: when it started, frames encoded, the size found. */
    private long passiveSinceMs, passiveFirstFrameMs;
    private int passiveEncoded;
    private String passiveSize;
    /** Copies by feeds that have ended, for the stats. */
    private long endedCopies, endedCopyNs;
    /** The session: debug (not recording), camera and size. Read from other threads. */
    private volatile boolean debug;
    private volatile int cameraId = -1;
    private volatile String size = "";
    /** Debug sessions: the camera's video signal, the last frame's verdict and brightness, frames checked. */
    private volatile int signal = Integer.MIN_VALUE;
    private volatile String picture = "";
    private volatile int luma, checked;
    /** While the guide lines are off for this session: checks every 2 s that they stay off. */
    private final Runnable keepLinesOff = new Runnable() {
        @Override
        public void run() {
            if (!active || passive || guideLines == null) return;
            if (guideLines.keepOff() && ++linesReasked >= 5) {
                Log.w("the guide lines keep coming back on; giving up for this session");
                return;
            }
            handler.postDelayed(this, 2000);
        }
    };
    private int linesReasked;

    /** Debug sessions: the feed's numbers and CPU use every 5 s. */
    private final Runnable feedStats = new Runnable() {
        private long at, frames, writes, torn, busy, missed, copies, copyNs;
        private Feed of;
        private final Perf cpu = new Perf();

        @Override
        public void run() {
            Feed f = feed;
            if (!active || !debug || f == null) return;
            long now = SystemClock.uptimeMillis();
            long[] n = f.counts();
            String load = cpu.sample();
            if (f == of) {
                float secs = (now - at) / 1000f;
                long c = n[5] - copies;
                Log.i(String.format(Locale.US,
                        "feed: %.1f writes/s, %.1f frames/s, %d copied (%.1f ms each), %d torn, %d busy, %d missed; %d checked, %s; CPU: %s",
                        (n[1] - writes) / secs, (n[0] - frames) / secs, c, c == 0 ? 0 : (n[6] - copyNs) / 1e6 / c,
                        n[2] - torn, n[3] - busy, n[4] - missed, checked, picture, load));
            }
            of = f;
            at = now;
            frames = n[0];
            writes = n[1];
            torn = n[2];
            busy = n[3];
            missed = n[4];
            copies = n[5];
            copyNs = n[6];
            handler.postDelayed(this, 5000);
        }
    };
    private final Runnable checkSignal = new Runnable() {
        @Override
        public void run() {
            if (!active || !debug || camera == null) return;
            try {
                int s = camera.signal();
                if (s != signal) Log.i("video signal " + s);
                signal = s;
            } catch (Throwable t) {
                if (signal != Integer.MAX_VALUE) Log.w("can't check the video signal: " + t);
                signal = Integer.MAX_VALUE;
            }
            handler.postDelayed(this, 1000);
        }
    };

    Recorder(Config config, Handler handler) {
        this.config = config;
        this.handler = handler;
        ticksPerFrame = Math.max(1, Math.round(60_000f / 1001 / config.fps));
        guideLines = config.hideGuideLines ? new GuideLines(config) : null;
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

    /** Saves the last few minutes, from any thread. */
    void saveRecent() {
        writer.saveRecent();
    }

    /** True while recording (or running a debug session), with the camera or from the stock view. Read from other threads. */
    boolean isActive() {
        return active;
    }

    /** True while recording the stock reverse view's frames, without the camera. */
    boolean isPassive() {
        return passive;
    }

    /** True while running a debug session (if active). */
    boolean isDebug() {
        return debug;
    }

    /** The session's camera. */
    int cameraId() {
        return cameraId;
    }

    /** The session's camera size, "WxH". */
    String size() {
        return size;
    }

    /** Starts recording, from the camera the stock view would open. */
    void start() {
        start(false, RearCam.stockId(), config.width, config.height);
    }

    /** Starts a debug session: the camera and the feed, checked but not recorded. */
    void startDebug(int cameraId, int width, int height) {
        start(true, cameraId, width, height);
    }

    private void start(boolean debug, int cameraId, int width, int height) {
        if (active) return;
        this.debug = debug;
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        size = width + "x" + height;
        ySize = width * height;
        cSize = ySize / 4;
        signal = Integer.MIN_VALUE;
        picture = "";
        checked = 0;
        noPicture = null;
        passive = false;
        try {
            if (config.debugNoCamera && !debug) {
                startBlank();
                return;
            }
            if (ram == null) ram = CameraRam.map();
            if (!ram.fits(width, height)) throw new IllegalStateException(CameraRam.SLOTS + " " + size + " frames don't fit");
            if (!config.debugNoEncode && !debug) {
                if (i420 == null || i420.length != ySize + 2 * cSize) i420 = new byte[ySize + 2 * cSize];
                encoder = MediaCodec.createByCodecName(ENCODER);
                configureEncoder();
            }
            Log.i((debug ? "debug session: " : "") + "camera " + cameraId + " at " + size + ", camera type " + RearCam.type());
            startNs = System.nanoTime();
            lastPtsUs = -1;
            statsStartMs = SystemClock.uptimeMillis();
            convertNs = 0;
            encoded = 0;
            endedCopies = endedCopyNs = copiesAtStats = copyNsAtStats = 0;
            perf.sample();
            openCamera();
            active = true;
            feed.start();
            if (debug) {
                try {
                    Log.i("camera parameters: " + camera.parameters());
                } catch (Throwable t) {
                    Log.w("can't read the camera parameters: " + t);
                }
                handler.post(checkSignal);
                handler.post(feedStats);
            }
        } catch (Throwable t) {
            Log.e(debug ? "can't start the debug session" : "can't start recording", t);
            stop();
        }
    }

    /** Opens the camera at the session's size, with its feed (not started), guide lines off. */
    private void openCamera() throws Exception {
        boolean hidLines = guideLines != null && guideLines.hide();
        final RearCam[] opened = new RearCam[1];
        opened[0] = camera = RearCam.open(cameraId, new Runnable() {
            @Override
            public void run() {
                if (camera != null && camera == opened[0]) stop();  // the guard restarts it
            }
        });
        feed = new Feed(width, height, false);
        camera.start(width, height);
        skipUntilNs = hidLines ? System.nanoTime() + GUIDE_LINES_SETTLE_NS : 0;
        Log.i("camera started");
        if (hidLines) {
            linesReasked = 0;
            handler.postDelayed(keepLinesOff, 2000);
        }
    }

    /** Releases the camera first, so the stock reverse view can open it as soon as possible. */
    void stop() {
        boolean was = active;
        active = false;
        endPassive();
        if (feed != null) feed.running = false;
        if (camera != null) {
            camera.release();
            camera = null;
        }
        if (guideLines != null) guideLines.restore();  // for the reverse view
        endFeed();
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Throwable ignored) {
                // Releasing anyway.
            }
            encoder.release();
            encoder = null;
        }
        codecConfig = null;
        writer.end();
        handler.removeCallbacks(checkSignal);
        handler.removeCallbacks(keepLinesOff);
        handler.removeCallbacks(feedStats);
        if (was) Log.i("camera released");
    }

    /**
     * For reverse: lets go of the camera (released runs as soon as it has), turns the guide lines
     * back on, and goes on recording the stock view's frames. A debug session just stops.
     */
    void handOff(Runnable released) {
        if (!active || debug || passive || camera == null) {
            stop();
            released.run();
            return;
        }
        if (feed != null) feed.running = false;
        camera.release();
        camera = null;
        released.run();
        handler.removeCallbacks(keepLinesOff);
        if (guideLines != null) guideLines.restore();
        endFeed();
        passive = true;
        passiveSinceMs = SystemClock.uptimeMillis();
        passiveFirstFrameMs = 0;
        passiveEncoded = 0;
        passiveSize = null;
        Log.i("camera released; recording the reverse view: looking for its frames");
        findStockFrames();
    }

    /** After reverse: opens the camera again, into the same recording. If it can't, stops. */
    void takeBack() {
        if (!active || !passive) return;
        endPassive();
        endFeed();
        try {
            openCamera();
            feed.start();
        } catch (Throwable t) {
            Log.e("can't take the camera back", t);
            stop();
        }
    }

    /** Ends recording the reverse view, with a summary of it. */
    private void endPassive() {
        if (!passive) return;
        passive = false;
        finder = null;  // it checks
        long secs = (SystemClock.uptimeMillis() - passiveSinceMs) / 1000;
        Log.i(String.format(Locale.US, "reverse view: %d s, %s, %d frames recorded (%.1f fps)%s", secs,
                passiveSize != null ? "its frames " + passiveSize : "no frames found", passiveEncoded,
                secs > 0 ? passiveEncoded / (float) secs : 0f,
                passiveFirstFrameMs > 0 ? ", first after " + (passiveFirstFrameMs - passiveSinceMs) + " ms" : ""));
    }

    private void endFeed() {
        if (feed == null) return;
        feed.end();
        endedCopies += feed.copies;
        endedCopyNs += feed.copyNs;
        feed = null;
    }

    /** Starts looking for the stock view's frames; once found, they're recorded. */
    private void findStockFrames() {
        final Thread t = new Thread("camera find") {
            @Override
            public void run() {
                boolean reported = false;
                while (passive && finder == this) {
                    try {
                        final int[] found = findStockSize();
                        if (found != null && found[0] > 0) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    recordStock(found[0], found[1]);
                                }
                            });
                            return;
                        }
                        if (found != null && !reported) {
                            Log.w(String.format(Locale.US, "the reverse view writes frames, but no usual size fits (changing up to 0x%x)", -found[0]));
                            reported = true;
                        }
                    } catch (Throwable e) {
                        Log.e("looking for the reverse view's frames", e);
                        return;
                    }
                    SystemClock.sleep(250);
                }
            }
        };
        t.setDaemon(true);
        finder = t;
        t.start();
    }

    /** On the main thread: records the stock view's width × height frames, scaled. */
    private void recordStock(int w, int h) {
        if (!active || !passive || feed != null) return;
        finder = null;
        passiveSize = w + "x" + h;
        Log.i("recording the reverse view's " + passiveSize + " frames, scaled to " + size);
        feed = new Feed(w, h, true);
        feed.start();
    }

    /**
     * The stock view's frame size, from about a third of a second of which parts of the camera
     * memory change: the hardware writes a ring of four frames from the start, so the size is the
     * one for which every change has its counterparts in the other three frames, and nothing
     * changes past the fourth. Null if nothing changes; {-reach, 0} if something does but no
     * size fits.
     */
    private int[] findStockSize() {
        int blocks = (int) (ram.length / FIND_BLOCK);
        int[] probe = new int[FIND_PROBE], hashes = new int[blocks];
        boolean[] changed = new boolean[blocks];
        int last = -1;
        for (int pass = 0; pass < FIND_PASSES; pass++) {
            for (int b = 0; b < blocks; b++) {
                ram.read((long) b * FIND_BLOCK + FIND_BLOCK / 2, probe, 0, FIND_PROBE);
                int x = 1;
                for (int p : probe) x = 31 * x + p;
                if (pass > 0 && x != hashes[b]) {
                    changed[b] = true;
                    last = Math.max(last, b);
                }
                hashes[b] = x;
            }
            SystemClock.sleep(FIND_PASS_MS);
        }
        if (last < 0) return null;
        int[] best = null;
        double bestScore = 0.8;  // at least this many of the changes accounted for
        for (int[] s : STOCK_SIZES) {
            long frame = (long) s[0] * s[1] * 4;
            if (frame * CameraRam.SLOTS > ram.length || (long) last * FIND_BLOCK > frame * CameraRam.SLOTS + FIND_BLOCK) continue;
            int counted = 0, consistent = 0;
            for (int b = 0; b <= last; b++) {
                if (!changed[b]) continue;
                counted++;
                long rel = ((long) b * FIND_BLOCK + FIND_BLOCK / 2) % frame;
                boolean all = true;
                for (int k = 0; k < CameraRam.SLOTS && all; k++) {
                    int c = (int) ((k * frame + rel) / FIND_BLOCK);
                    all = (c < blocks && changed[c]) || (c > 0 && changed[c - 1]) || (c + 1 < blocks && changed[c + 1]);
                }
                if (all) consistent++;
            }
            double score = counted == 0 ? 0 : consistent / (double) counted;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : new int[] {-(last + 1) * FIND_BLOCK, 0};
    }

    /** A debug session as key=value lines, for the app's camera debug screen. */
    String debugStatus() {
        Feed f = feed;
        StringBuilder b = new StringBuilder();
        b.append("camera=").append(cameraId).append("\nsize=").append(size).append("\nmirror=").append(config.mirror)
                .append("\nsignal=").append(signal == Integer.MIN_VALUE ? "" : signal == Integer.MAX_VALUE ? "error" : String.valueOf(signal))
                .append("\npicture=").append(picture).append("\nluma=").append(luma).append("\nchecked=").append(checked).append('\n');
        if (f != null) b.append(f.debugStatus());
        return b.toString();
    }

    private void configureEncoder() {
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

    /** A frame copied out by the feed, on the main thread. Gives its buffer back either way. */
    private void onFrame(Feed from, int[] argb, long ptsUs) {
        try {
            if (!active || from != feed || (!from.stock && System.nanoTime() < skipUntilNs)) return;
            String why = noPicture(argb);
            if (debug) {
                picture = why == null ? "ok" : why;
                luma = luma(argb);
                checked++;
            }
            if (why == null ? noPicture != null : !why.equals(noPicture)) {
                Log.i(why != null ? why + "; not recording until there is one" : "the camera's picture is back");
                noPicture = why;
            }
            if (encoder != null && why == null && !debug) {
                encode(argb, ptsUs);
                drain();
                if (from.stock) {
                    if (passiveEncoded++ == 0) passiveFirstFrameMs = SystemClock.uptimeMillis();
                }
                logStats();
            }
        } finally {
            from.free.offer(argb);
        }
    }

    /** Average brightness (0-255) of the noPicture grid. */
    private int luma(int[] argb) {
        long sum = 0;
        int n = 0;
        for (int row = height / 16; row < height; row += height / 8) {
            for (int col = width / 24; col < width; col += width / 12) {
                int p = argb[row * width + col];
                sum += (77 * ((p >> 16) & 0xff) + 150 * ((p >> 8) & 0xff) + 29 * (p & 0xff)) >> 8;
                n++;
            }
        }
        return (int) (sum / n);
    }

    /**
     * Why the frame isn't a picture, or null if it is. With no signal (for about 25 s after the
     * head unit starts, and while the car is off) the buffers stay flat, every pixel exactly one
     * color, which real video, with its noise, never is, not even in the dark. Every pixel the
     * hardware writes is opaque, so anything else means the frames aren't laid out as expected.
     * Checks a grid of pixels.
     */
    private String noPicture(int[] argb) {
        int first = argb[(height / 16) * width + width / 24];
        boolean flat = true;
        for (int row = height / 16; row < height; row += height / 8) {
            for (int col = width / 24; col < width; col += width / 12) {
                int p = argb[row * width + col];
                if (p >>> 24 != 0xff) return "the camera's frames aren't " + width + "x" + height + " BGRA";
                if (p != first) flat = false;
            }
        }
        return flat ? "no picture from the camera" : null;
    }

    /**
     * Converts a frame to I420 (BT.601, video range; chroma from the top left pixel of each 2×2),
     * flipped if Config.mirror, and queues it to the encoder: in C (Native) straight into the
     * encoder's buffer if it's loaded, else here.
     */
    private void encode(int[] argb, long ptsUs) {
        int i = encoder.dequeueInputBuffer(0);
        if (i < 0) {
            if (dropped++ % 100 == 0) Log.w("encoder busy; dropped " + dropped + " frames so far");
            return;
        }
        long t0 = System.nanoTime();
        byte[] out = i420;
        int w = width, half = width / 2;
        int u = ySize, v = ySize + cSize;
        if (config.swapUv) {
            u = ySize + cSize;
            v = ySize;
        }
        ByteBuffer in = inputs[i];
        if (Native.loaded && in.isDirect() && in.capacity() >= out.length) {
            // Straight into the encoder's buffer.
            Native.toI420(argb, width, height, in, u, v, config.mirror);
            convertNs += System.nanoTime() - t0;
            encoder.queueInputBuffer(i, 0, out.length, ptsUs, 0);
            encoded++;
            return;
        }
        // Mirrored, each row is written right to left, which puts it the right way round at no extra cost.
        int step = config.mirror ? -1 : 1;
        for (int row = 0, s = 0; row < height; row++) {
            int end = s + w, y = config.mirror ? s + w - 1 : s;
            if ((row & 1) == 0) {
                for (int c = (row >> 1) * half + (config.mirror ? half - 1 : 0); s < end; s += 2, c += step) {
                    int p = argb[s], r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                    out[y] = (byte) ((66 * r + 129 * g + 25 * b + 4224) >> 8);
                    out[u + c] = (byte) ((-38 * r - 74 * g + 112 * b + 32896) >> 8);
                    out[v + c] = (byte) ((112 * r - 94 * g - 18 * b + 32896) >> 8);
                    int q = argb[s + 1];
                    out[y + step] = (byte) ((66 * ((q >> 16) & 0xff) + 129 * ((q >> 8) & 0xff) + 25 * (q & 0xff) + 4224) >> 8);
                    y += 2 * step;
                }
            } else {
                for (; s < end; s++, y += step) {
                    int p = argb[s];
                    out[y] = (byte) ((66 * ((p >> 16) & 0xff) + 129 * ((p >> 8) & 0xff) + 25 * (p & 0xff) + 4224) >> 8);
                }
            }
        }
        in.clear();
        in.put(out);
        convertNs += System.nanoTime() - t0;
        encoder.queueInputBuffer(i, 0, out.length, ptsUs, 0);
        encoded++;
    }

    /** Debugging: the encoder alone, fed grey frames at the configured rate. */
    private void startBlank() throws java.io.IOException {
        i420 = new byte[ySize + 2 * cSize];
        encoder = MediaCodec.createByCodecName(ENCODER);
        configureEncoder();
        startNs = System.nanoTime();
        lastPtsUs = -1;
        statsStartMs = SystemClock.uptimeMillis();
        active = true;
        final int[] grey = new int[ySize];
        java.util.Arrays.fill(grey, 0xff808080);
        Log.i("debug: encoder only, grey frames");
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                long pts = (System.nanoTime() - startNs) / 1000;
                encode(grey, pts);
                drain();
                logStats();
                handler.postDelayed(this, 1000 / config.fps);
            }
        });
    }

    /**
     * Every STATS_MS: frame rate, the time each frame takes to copy out and convert, frames lost,
     * and CPU use (see {@link Perf}), to watch how heavy recording is.
     */
    private void logStats() {
        long now = SystemClock.uptimeMillis();
        if (now - statsStartMs < STATS_MS || encoded == 0) return;
        Feed f = feed;
        long copies = endedCopies + (f != null ? f.copies : 0), copyNs = endedCopyNs + (f != null ? f.copyNs : 0);
        long c = copies - copiesAtStats;
        String cpu = perf.sample();
        Log.i(String.format(Locale.US, "%.1f fps%s, copy %.1f ms/frame, conversion %.1f ms/frame, %d dropped%s%s",
                encoded * 1000f / (now - statsStartMs), passive ? " (reverse view)" : "",
                c == 0 ? 0 : (copyNs - copyNsAtStats) / 1e6 / c, convertNs / 1e6 / encoded, dropped,
                f != null ? ", " + f.stats() : "", cpu != null ? "; CPU: " + cpu : ""));
        statsStartMs = now;
        convertNs = 0;
        encoded = 0;
        copiesAtStats = copies;
        copyNsAtStats = copyNs;
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

    /**
     * One camera session's frames out of RAM: the watcher finds completed buffers, the copier
     * copies out the ones kept and posts them to the main thread. The ring is of srcWidth ×
     * srcHeight frames; frames come out at the session's size, scaled (nearest pixel) if
     * they're another size, reading only the rows needed.
     */
    private final class Feed {
        private final int srcWidth, srcHeight;
        private final long frameBytes;
        /** The stock reverse view's frames, not ours. */
        final boolean stock;
        /** Source column and row of each output pixel, if scaled; else null. */
        private final int[] xs, ys;
        final ArrayBlockingQueue<int[]> free = new ArrayBlockingQueue<>(FRAME_BUFFERS);
        private final Thread watcher, copier;
        volatile boolean running = true;
        /** Guarded by this: the last completed buffer, when, and how many so far; how often each buffer started. */
        private int completedSlot;
        private long completedNs, completions;
        private final long[] starts = new long[CameraRam.SLOTS];
        /** Copier only. */
        private long nextTick;
        /** For the stats: torn frames, frames with no free buffer, and frames the watcher couldn't place. */
        private volatile int torn, busy, missed;
        /** Copier only, read for the stats: frames copied out and the time it took. */
        volatile long copies, copyNs;

        Feed(int srcWidth, int srcHeight, boolean stock) {
            this.srcWidth = srcWidth;
            this.srcHeight = srcHeight;
            this.stock = stock;
            frameBytes = (long) srcWidth * srcHeight * 4;
            if (srcWidth == width && srcHeight == height) {
                xs = ys = null;
            } else {
                xs = new int[width];
                ys = new int[height];
                for (int c = 0; c < width; c++) xs[c] = (2 * c + 1) * srcWidth / (2 * width);
                for (int r = 0; r < height; r++) ys[r] = (2 * r + 1) * srcHeight / (2 * height);
            }
            for (int i = 0; i < FRAME_BUFFERS; i++) free.add(new int[width * height]);
            watcher = new Thread("camera watch") {
                @Override
                public void run() {
                    watch();
                }
            };
            copier = new Thread("camera copy") {
                @Override
                public void run() {
                    copy();
                }
            };
            watcher.setDaemon(true);
            copier.setDaemon(true);
        }

        void start() {
            watcher.start();
            copier.start();
        }

        /** Stops the threads, waiting a little for them. The RAM stays mapped either way. */
        void end() {
            running = false;
            synchronized (this) {
                notifyAll();
            }
            long until = SystemClock.uptimeMillis() + JOIN_MS;
            join(watcher, until);
            join(copier, until);
        }

        private void join(Thread t, long until) {
            if (t.getState() == Thread.State.NEW || t == Thread.currentThread()) return;
            try {
                t.join(Math.max(1, until - SystemClock.uptimeMillis()));
            } catch (InterruptedException ignored) {
                // They stop on their own.
            }
        }

        String stats() {
            return torn + " torn, " + busy + " busy, " + missed + " missed";
        }

        /** Frames, buffer writes, torn, busy, missed, copies, copy time. */
        synchronized long[] counts() {
            return new long[] {completions, starts[0] + starts[1] + starts[2] + starts[3], torn, busy, missed, copies, copyNs};
        }

        synchronized String debugStatus() {
            return "frames=" + completions + "\nstarts=" + starts[0] + "," + starts[1] + "," + starts[2] + "," + starts[3]
                    + "\ntorn=" + torn + "\nbusy=" + busy + "\nmissed=" + missed + "\n";
        }

        /**
         * Hashes a strip of each buffer, 1/16 of the way down, every WATCH_MS. A single buffer
         * changing means it's being written, and the one that changed before it is complete. If
         * several change between two looks, which came first is unknown; it waits for the next.
         * Right after a buffer starts, the next can't for about a frame, so it waits longer.
         */
        private void watch() {
            long offset = ((long) (srcHeight / 16) * srcWidth + srcWidth / 3) * 4;
            int n = srcWidth / 3;
            int[] strip = new int[n];
            int[] last = new int[CameraRam.SLOTS];
            boolean[] changed = new boolean[CameraRam.SLOTS];
            int writing = -1;
            for (boolean first = true; running; first = false) {
                boolean started = false;
                int count = 0, slot = -1;
                for (int i = 0; i < CameraRam.SLOTS; i++) {
                    ram.read(i * frameBytes + offset, strip, 0, n);
                    int h = 1;
                    for (int p : strip) h = 31 * h + p;
                    changed[i] = !first && h != last[i];
                    last[i] = h;
                    if (changed[i]) {
                        count++;
                        slot = i;
                    }
                }
                if (count > 0) {
                    long now = System.nanoTime();
                    synchronized (this) {
                        for (int i = 0; i < CameraRam.SLOTS; i++) if (changed[i]) starts[i]++;
                        if (count > 1) {
                            missed++;
                            writing = -1;
                        } else if (slot != writing) {
                            if (writing >= 0) {
                                completedSlot = writing;
                                completedNs = now;
                                completions++;
                                notifyAll();
                            }
                            writing = slot;
                            started = true;
                        }
                    }
                }
                SystemClock.sleep(started ? WATCH_AFTER_START_MS : WATCH_MS);
            }
        }

        /**
         * Keeps every n-th completed frame on the camera's 59.94 Hz tick grid: frames are placed
         * on the tick nearest their completion; the next frame kept is the first at or past the
         * target tick, and the target advances by exactly n, so the cadence stays even. A frame
         * whose buffer started being written again during the copy is torn, and dropped.
         * <p>
         * If the frames stop: for our camera, it's restarted after a while; for the stock view's
         * (reverse is over, or it changed its size), the search for them starts again.
         */
        private void copy() {
            long seen = 0, lastNs = System.nanoTime();
            boolean stalled = false;
            int[] row = xs != null ? new int[srcWidth] : null;
            while (running) {
                int slot;
                long at, startsBefore;
                synchronized (this) {
                    while (running && completions == seen) {
                        try {
                            wait(STALL_MS / 4);
                        } catch (InterruptedException ignored) {
                            // Checks running.
                        }
                        long quiet = (System.nanoTime() - lastNs) / 1_000_000;
                        if (stock && running && quiet >= STALL_MS) {
                            Log.i("the reverse view's frames stopped");
                            running = false;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (feed != Feed.this || !passive) return;
                                    endFeed();
                                    findStockFrames();
                                }
                            });
                            return;
                        }
                        if (!stalled && quiet >= STALL_MS) {
                            Log.i("no new frames from the camera; not recording until there are");
                            stalled = true;
                        }
                        if (running && quiet >= STALL_RESTART_MS) {
                            Log.w("no frames for " + quiet / 1000 + " s; restarting the camera");
                            running = false;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (feed == Feed.this) stop();  // the guard starts it again
                                }
                            });
                        }
                    }
                    if (!running) return;
                    seen = completions;
                    slot = completedSlot;
                    at = completedNs;
                    startsBefore = starts[slot];
                }
                lastNs = at;
                if (stalled) {
                    Log.i("the camera's frames are back");
                    stalled = false;
                }
                long tick = ((at - startNs) / 1000 + TICK_US / 2) / TICK_US;
                if (tick < nextTick) continue;
                // Fell more than a frame behind (a stall): restart the cadence here.
                nextTick = tick - nextTick >= ticksPerFrame ? tick + ticksPerFrame : nextTick + ticksPerFrame;
                final int[] buf = free.poll();
                if (buf == null) {
                    busy++;
                    continue;
                }
                long t0 = System.nanoTime();
                long base = slot * frameBytes;
                if (xs == null) {
                    ram.read(base, buf, 0, buf.length);
                } else if (Native.loaded) {
                    Native.copyScaled(ram.address(base, frameBytes), srcWidth, ys, xs, buf);
                } else {
                    for (int r = 0, o = 0; r < ys.length; r++) {
                        ram.read(base + (long) ys[r] * srcWidth * 4, row, 0, srcWidth);
                        for (int x : xs) buf[o++] = row[x];
                    }
                }
                copyNs += System.nanoTime() - t0;
                copies++;
                boolean rewritten;
                synchronized (this) {
                    rewritten = starts[slot] != startsBefore;
                }
                if (rewritten) {
                    torn++;
                    free.offer(buf);
                    continue;
                }
                long pts = tick * TICK_US;
                if (pts <= lastPtsUs) pts = lastPtsUs + TICK_US;
                lastPtsUs = pts;
                final long framePts = pts;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        onFrame(Feed.this, buf, framePts);
                    }
                });
            }
        }
    }
}
