package dev.skramer.dashcam;

import android.media.MediaCodec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;

/** Runs production configuration, codec selection and output draining with simulated codecs. */
public final class EncoderTest {
    private static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x42, 0, 0x1e};
    private static final byte[] PPS = {0, 0, 0, 1, 0x68, (byte) 0xce, 0x06};
    private static final byte[] KEYFRAME = {0, 0, 0, 1, 0x65, 1, 2, 3};

    public static void main(String[] args) throws Exception {
        configuration();
        for (String name : new String[] {Config.HW_ENCODER, Config.SW_ENCODER}) {
            unavailable(name, false);
            unavailable(name, true);
            output(name);
        }
        System.out.println("PASS: settings, restart detection, both encoder selections, failure cleanup, SPS/PPS and frames");
        System.out.println("Simulated codecs only; actual encoding and playback require the head unit.");
    }

    private static void configuration() throws Exception {
        Config hardware = Config.parse(new String[] {"--encoder=hw"});
        Config software = Config.parse(new String[] {"--encoder=sw"});
        check(Config.HW_ENCODER.equals(hardware.encoder), "hardware alias");
        check(Config.SW_ENCODER.equals(software.encoder), "software alias");
        check(!hardware.recordingSettings().equals(software.recordingSettings()), "encoder change triggers restart");
        Path dir = Files.createTempDirectory("dashcam-settings-");
        Path app = dir.resolve("recorder.conf");
        try {
            hardware.appConf = app.toString();
            hardware.stateDir = dir.toString();
            Files.write(app, "encoder=sw\n".getBytes(StandardCharsets.US_ASCII));
            check(Config.SW_ENCODER.equals(hardware.withAppSettings().encoder), "app setting overrides default");
            Files.move(app, dir.resolve("settings"));
            check(Config.SW_ENCODER.equals(hardware.withAppSettings().encoder), "setting survives app file removal");
        } finally {
            Files.deleteIfExists(app);
            Files.deleteIfExists(dir.resolve("settings"));
            Files.delete(dir);
        }
    }

    private static Recorder recorder(String name, boolean blank) throws Exception {
        Config config = new Config();
        config.encoder = name;
        config.hideGuideLines = false;
        config.debugNoCamera = blank;
        Recorder recorder = new Recorder(config, null);
        // Pause the real writer so we can examine the frames queued by drain().
        Thread writer = (Thread) field(recorder, "writer");
        writer.interrupt();
        writer.join(2000);
        check(!writer.isAlive(), "writer stopped for inspection");
        return recorder;
    }

    private static void unavailable(String name, boolean blank) throws Exception {
        MediaCodec.unavailable = true;
        MediaCodec.typeLookups = 0;
        android.util.Log.lastError = null;
        Recorder recorder = recorder(name, blank);
        recorder.start();
        check(name.equals(MediaCodec.requestedName), "requested named codec");
        check(MediaCodec.typeLookups == 0, "unavailable codec must never fall back by MIME type");
        check(!recorder.isActive() && field(recorder, "encoder") == null, "failed start cleaned up");
        check(android.util.Log.lastError != null && android.util.Log.lastError.contains(name), "failure names selected encoder");
        MediaCodec.unavailable = false;
        System.out.println("PASS: unavailable " + name + (blank ? " (blank frames)" : " (camera)"));
    }

    private static void output(String name) throws Exception {
        MediaCodec.typeLookups = 0;
        Recorder recorder = recorder(name, false);
        Method create = Recorder.class.getDeclaredMethod("createEncoder");
        create.setAccessible(true);
        MediaCodec codec = (MediaCodec) create.invoke(recorder);
        check(name.equals(MediaCodec.requestedName), "selected encoder opened by name");
        check(MediaCodec.typeLookups == 0, "selection must not use a MIME lookup");
        byte[] config = concat(SPS, PPS);
        if (Config.HW_ENCODER.equals(name)) {
            codec.setOutput(new byte[][] {config, KEYFRAME}, new int[] {2, 1});
        } else {
            codec.setOutput(new byte[][] {SPS, PPS, KEYFRAME}, new int[] {2, 2, 1});
        }
        set(recorder, "encoder", codec);
        set(recorder, "outputs", codec.getOutputBuffers());
        Method drain = Recorder.class.getDeclaredMethod("drain");
        drain.setAccessible(true);
        drain.invoke(recorder);
        BlockingQueue<?> queue = (BlockingQueue<?>) field(field(recorder, "writer"), "queue");
        Object frame = queue.poll();
        check(frame != null && queue.isEmpty(), "exactly one frame queued");
        check(Arrays.equals(config, (byte[]) field(frame, "config")), "keyframe has both SPS and PPS");
        check(Arrays.equals(KEYFRAME, Arrays.copyOf((byte[]) field(frame, "data"), (Integer) field(frame, "size"))), "frame payload preserved");
        check((Boolean) field(frame, "key"), "keyframe flag preserved");
        check((Long) field(frame, "ptsUs") == 123456L, "timestamp preserved");
        check(codec.outputReleases == (Config.HW_ENCODER.equals(name) ? 2 : 3), "all codec buffers released");
        recorder.stop();
        check(codec.released && field(recorder, "codecConfig") == null, "stop releases codec and clears headers");
        System.out.println("PASS: selection and output for " + name);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
