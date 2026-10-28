package dev.skramer.dashcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.SystemClock;
import java.nio.ByteBuffer;
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
 */
@SuppressWarnings("deprecation")
final class Recorder {
    /** Gen5W's VPU H.264 encoder. It isn't in media_codecs.xml, but MediaCodec can open it by name. */
    private static final String ENCODER = "OMX.TCC.ENC.avc";
    private static final int CAMERA_ID = 0;
    /** Frames copied out and waiting for the encoder, at most. */
    private static final int FRAME_BUFFERS = 2;
    /** The camera's frame period (NTSC, 59.94 Hz). Timestamps snap to it: arrival times jitter. */
    private static final long TICK_US = 1_000_000L * 1001 / 60_000;
    /** How often the watcher looks for a buffer being written; a frame takes about 16 ms. */
    private static final long WATCH_MS = 2;
    /** No new frame for this long: the camera has stopped sending. After STALL_RESTART_MS, start it again. */
    private static final long STALL_MS = 2_000, STALL_RESTART_MS = 60_000;
    /** How long stop() waits for the feed's threads, which only ever block for one frame copy. */
    private static final long JOIN_MS = 200;

    private final Config config;
    private final Handler handler;
    private final SegmentWriter writer;
    private final int width, height, ySize, cSize, frameInts;
    private final byte[] i420;
    private final int ticksPerFrame;
    private final ArrayBlockingQueue<int[]> free = new ArrayBlockingQueue<>(FRAME_BUFFERS);

    private RearCam camera;
    private Feed feed;
    private MediaCodec encoder;
    private ByteBuffer[] inputs, outputs;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] codecConfig;
    private volatile boolean active;
    private long startNs, lastPtsUs;
    private int dropped;
    private String noPicture;
    private long statsStartMs, convertNs;
    private int encoded;

    Recorder(Config config, Handler handler) {
        this.config = config;
        this.handler = handler;
        width = config.width;
        height = config.height;
        ySize = width * height;
        cSize = ySize / 4;
        frameInts = width * height;
        i420 = new byte[ySize + 2 * cSize];
        ticksPerFrame = Math.max(1, Math.round(60_000f / 1001 / config.fps));
        for (int i = 0; i < FRAME_BUFFERS; i++) free.add(new int[frameInts]);
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
            if (!config.debugNoEncode) {
                encoder = MediaCodec.createByCodecName(ENCODER);
                configureEncoder();
            }
            feed = new Feed(CameraRam.map(width, height));  // unmapped on stop
            Log.i("camera type " + RearCam.type());
            final RearCam[] opened = new RearCam[1];
            opened[0] = camera = RearCam.open(CAMERA_ID, new Runnable() {
                @Override
                public void run() {
                    if (camera != null && camera == opened[0]) stop();  // the guard restarts it
                }
            });
            camera.start(width, height);
            startNs = System.nanoTime();
            lastPtsUs = -1;
            statsStartMs = SystemClock.uptimeMillis();
            convertNs = 0;
            encoded = 0;
            active = true;
            feed.start();
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
        if (feed != null) feed.running = false;
        if (camera != null) {
            camera.release();
            camera = null;
        }
        if (feed != null) {
            feed.end();
            feed = null;
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
        codecConfig = null;
        writer.end();
        if (was) Log.i("camera released");
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
            if (!active || from != feed) return;
            String why = noPicture(argb);
            if (why == null ? noPicture != null : !why.equals(noPicture)) {
                Log.i(why != null ? why + "; not recording until there is one" : "the camera's picture is back");
                noPicture = why;
            }
            if (encoder != null && why == null) {
                encode(argb, ptsUs);
                drain();
                logStats();
            }
        } finally {
            free.offer(argb);
        }
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
     * Converts a frame to I420 (BT.601, video range; chroma from the top left pixel of each 2×2)
     * and queues it to the encoder.
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
        for (int row = 0, s = 0; row < height; row++) {
            int end = s + w;
            if ((row & 1) == 0) {
                for (int c = (row >> 1) * half; s < end; s += 2, c++) {
                    int p = argb[s], r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                    out[s] = (byte) ((66 * r + 129 * g + 25 * b + 4224) >> 8);
                    out[u + c] = (byte) ((-38 * r - 74 * g + 112 * b + 32896) >> 8);
                    out[v + c] = (byte) ((112 * r - 94 * g - 18 * b + 32896) >> 8);
                    int q = argb[s + 1];
                    out[s + 1] = (byte) ((66 * ((q >> 16) & 0xff) + 129 * ((q >> 8) & 0xff) + 25 * (q & 0xff) + 4224) >> 8);
                }
            } else {
                for (; s < end; s++) {
                    int p = argb[s];
                    out[s] = (byte) ((66 * ((p >> 16) & 0xff) + 129 * ((p >> 8) & 0xff) + 25 * (p & 0xff) + 4224) >> 8);
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

    /** Debugging: the encoder alone, fed grey frames at the configured rate. */
    private void startBlank() throws java.io.IOException {
        encoder = MediaCodec.createByCodecName(ENCODER);
        configureEncoder();
        startNs = System.nanoTime();
        lastPtsUs = -1;
        statsStartMs = SystemClock.uptimeMillis();
        active = true;
        final int[] grey = new int[frameInts];
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

    /** Once a minute: frame rate, conversion cost (to watch the CPU load) and frames lost in RAM. */
    private void logStats() {
        long now = SystemClock.uptimeMillis();
        if (now - statsStartMs < 60_000 || encoded == 0) return;
        Feed f = feed;
        Log.i(String.format(java.util.Locale.US, "%.1f fps, conversion %.1f ms/frame, %d dropped%s",
                encoded * 1000f / (now - statsStartMs), convertNs / 1e6 / encoded, dropped,
                f != null ? ", " + f.stats() : ""));
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

    /**
     * One camera session's frames out of RAM: the watcher finds completed buffers, the copier
     * copies out the ones kept and posts them to the main thread.
     */
    private final class Feed {
        private final CameraRam ram;
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

        Feed(CameraRam ram) {
            this.ram = ram;
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

        /** Stops the threads and unmaps the RAM, unless a thread is somehow still reading it. */
        void end() {
            running = false;
            synchronized (this) {
                notifyAll();
            }
            long until = SystemClock.uptimeMillis() + JOIN_MS;
            boolean done = join(watcher, until) & join(copier, until);
            if (done) ram.unmap();
            else Log.w("camera feed threads still running; leaving the camera RAM mapped");
        }

        private boolean join(Thread t, long until) {
            if (t.getState() == Thread.State.NEW) return true;
            try {
                t.join(Math.max(1, until - SystemClock.uptimeMillis()));
            } catch (InterruptedException ignored) {
                // Checked below.
            }
            return !t.isAlive();
        }

        String stats() {
            return torn + " torn, " + busy + " busy, " + missed + " missed";
        }

        /**
         * Hashes a strip of each buffer, 1/16 of the way down, every WATCH_MS. A single buffer
         * changing means it's being written, and the one that changed before it is complete. If
         * several change between two looks, which came first is unknown; it waits for the next.
         */
        private void watch() {
            int offset = (height / 16) * width + width / 3, n = width / 3;
            int[] strip = new int[n];
            int[] last = new int[CameraRam.SLOTS];
            boolean[] changed = new boolean[CameraRam.SLOTS];
            int writing = -1;
            for (boolean first = true; running; first = false) {
                int count = 0, slot = -1;
                for (int i = 0; i < CameraRam.SLOTS; i++) {
                    ram.read(i, offset, strip, n);
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
                        }
                    }
                }
                SystemClock.sleep(WATCH_MS);
            }
        }

        /**
         * Keeps every n-th completed frame on the camera's 59.94 Hz tick grid: frames are placed
         * on the tick nearest their completion; the next frame kept is the first at or past the
         * target tick, and the target advances by exactly n, so the cadence stays even. A frame
         * whose buffer started being written again during the copy is torn, and dropped.
         */
        private void copy() {
            long seen = 0, lastNs = System.nanoTime();
            boolean stalled = false;
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
                long pts = tick * TICK_US;
                if (pts <= lastPtsUs) pts = lastPtsUs + TICK_US;
                lastPtsUs = pts;
                final int[] buf = free.poll();
                if (buf == null) {
                    busy++;
                    continue;
                }
                ram.read(slot, 0, buf, frameInts);
                boolean rewritten;
                synchronized (this) {
                    rewritten = starts[slot] != startsBefore;
                }
                if (rewritten) {
                    torn++;
                    free.offer(buf);
                    continue;
                }
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
