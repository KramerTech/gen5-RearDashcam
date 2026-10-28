package dev.skramer.dashcam;

import android.app.ActivityManagerNative;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the stock reverse view working. The dashcam and the stock rear camera share one video
 * input, and while the dashcam holds it the stock view can't open at all. So the camera is
 * released whenever the stock app wants it (it broadcasts CAMERA_APP_START, for reverse and for
 * the camera button) or the gear reads R, and only taken back once the gear is out of R and the
 * stock app has closed its camera. Meanwhile recording goes on from the stock view's frames
 * (Recorder.handOff, state "reversing"). If a release hangs, the process kills itself, which frees the
 * camera too; the service script restarts it. The heartbeat file lets the watchdog service kill
 * a process that stopped responding altogether.
 *
 * The CAMERA_APP_START receiver is a second line: this firmware's ActivityManager skips
 * receivers registered without an app process, so on Gen5W it's the gear polling (which reads 1
 * for the camera button too) that does the work.
 *
 * The player app's settings file turns recording off and on. The status file says what the
 * recorder is doing.
 *
 * While the app's camera debug screen is open (see {@link CameraDebug}) the camera runs a debug
 * session the screen asks for instead, whether or not recording is on, with the same hand-off
 * to the stock view.
 *
 * It only records while the ignition is on. The head unit also starts up while the car is off
 * (every half hour or so overnight, for the car's own checks), and then the camera has no power
 * and delivers noise.
 */
final class Guard extends Thread {
    private static final String STOCK_CAMERA_START = "com.hkmc.intent.action.CAMERA_APP_START";
    private static final int GEAR_REVERSE = 1;
    /** While we own the camera, detect reverse within 50 ms; other work stays at 250 ms. */
    private static final long POLL_MS = 50, STATE_POLL_MS = 250;
    /** After the last reverse or stock-camera signal, wait this long before taking the camera back. */
    private static final long SETTLE_MS = 3000;
    private static final long RELEASE_TIMEOUT_MS = 800;
    private static final long RETRY_MS = 5000;
    /** The ignition reading off this many polls in a row (about a second) means the car is off. */
    private static final int IGNITION_OFF_POLLS = 4;

    private final Config config;
    private final Recorder recorder;
    private final Storage storage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File heartbeat, status, appConf;
    private final Method gearStatus;
    private final StockCameraWatcher watcher = new StockCameraWatcher();
    private final CameraDebug cameraDebug;
    private final NullRecording nullTest;
    private boolean driveMounted;
    private volatile boolean nullRequested;
    private final Runnable startRecorder = new Runnable() {
        @Override
        public void run() {
            if (nullRequested) recorder.startNull(); else recorder.start();
        }
    };
    private final Runnable takeBack = new Runnable() {
        @Override
        public void run() {
            recorder.takeBack();
        }
    };
    private volatile long lastBlockMs = -SETTLE_MS;
    private boolean disabled;
    private long appConfModified, appConfLength;
    private int lastGear = -1;
    private int ignitionOffPolls;
    private Boolean lastIgnition;
    private String lastState;

    Guard(Config config, Recorder recorder) {
        super("guard");
        this.config = config;
        this.recorder = recorder;
        storage = new Storage(config);
        heartbeat = new File(config.stateDir, "heartbeat");
        status = new File(config.stateDir, "status");
        appConf = new File(config.appConf);
        appConfModified = appConf.lastModified();
        appConfLength = appConf.length();
        disabled = !config.enabled;
        cameraDebug = new CameraDebug(config);
        nullTest = new NullRecording(config);
        gearStatus = gearMethod();
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            ActivityManagerNative.getDefault().registerReceiver(
                    null, null, watcher, new IntentFilter(STOCK_CAMERA_START), null, 0);
            Log.i("listening for " + STOCK_CAMERA_START);
        } catch (Throwable t) {
            Log.e("can't listen for the stock camera; the camera button won't work while recording", t);
        }
        if (disabled) Log.i("recording is turned off in the app");
        boolean wasDebugging = false;
        long nextStart = 0;
        boolean mounted = true;
        long nextStatePoll = 0;
        for (long beat = 0; ; ) {
            try {
                long now = SystemClock.uptimeMillis();
                int gear = gear();
                if (gear != lastGear) {
                    Log.i("gear status " + gear);
                    lastGear = gear;
                }
                if (gear == GEAR_REVERSE) {
                    lastBlockMs = now;
                    if (recorder.isActive() && !recorder.isPassive()) handOff("reverse");
                }
                if (now < nextStatePoll) {
                    SystemClock.sleep(recorder.isActive() && !recorder.isPassive() ? POLL_MS : STATE_POLL_MS);
                    continue;
                }
                nextStatePoll = now + STATE_POLL_MS;
                boolean carOff = carOff();
                boolean blocked = now - lastBlockMs < SETTLE_MS;
                if (beat % 4 == 0) checkAppSettings();
                if (beat % 4 == 0 || nullRequested) driveMounted = storage.mounted();
                boolean requested = nullTest.requested(driveMounted);
                if (requested != nullRequested) { nullRequested = requested; nextStart = 0; }
                final CameraDebug.Request debug = cameraDebug.read();
                boolean wantDebug = debug != null && !nullRequested;
                if ((debug != null) != wasDebugging) {
                    Log.i(debug != null ? "camera debug screen open" : "camera debug screen closed");
                    wasDebugging = debug != null;
                    if (debug == null) cameraDebug.end();
                }
                // The debug screen overrides recording being off.
                boolean off = disabled && !wantDebug && !nullRequested;
                String state;
                if (recorder.isActive()) {
                    if (carOff) {
                        release("ignition off");
                    } else if (recorder.isNullRecording() != nullRequested ||
                            (wantDebug ? !recorder.isDebug() || !debug.matches(recorder) : recorder.isDebug())) {
                        release("recording/debug mode changed");
                        nextStart = 0;
                    } else if (off) {
                        release("turned off");
                    } else if (blocked) {
                        if (!recorder.isPassive()) handOff(gear == GEAR_REVERSE ? "reverse" : "stock camera");
                    } else if (recorder.isPassive() && now >= nextStart) {
                        nextStart = now + RETRY_MS;
                        if (stockCameraOpen()) {
                            Log.i("the stock view still has the camera; waiting");
                            lastBlockMs = now;  // check again after SETTLE_MS
                        } else {
                            main.post(takeBack);
                        }
                    }
                    state = !recorder.isActive() ? (carOff ? "caroff" : off ? "off" : blocked ? "reverse" : "starting")
                            : recorder.isDebug() ? "debug" : recorder.isPassive() ? "reversing"
                            : recorder.isNullRecording() ? "recording-null" : "recording";
                } else if (carOff) {
                    state = "caroff";
                } else if (off) {
                    state = "off";
                } else if (blocked) {
                    state = "reverse";
                } else {
                    if (now >= nextStart) {
                        nextStart = now + RETRY_MS;
                        mounted = wantDebug || nullRequested || driveMounted;
                        if (!mounted) {
                            if (beat % 120 == 0) Log.i("waiting for " + config.mount);
                        } else if (stockCameraOpen()) {
                            Log.i("the stock view still has the camera; waiting");
                            lastBlockMs = now;  // the stock view is still up; check again after SETTLE_MS
                        } else if (wantDebug) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    recorder.startDebug(CameraDebug.camera(debug), debug.width, debug.height);
                                }
                            });
                        } else {
                            main.post(startRecorder);
                        }
                    }
                    state = mounted ? "starting" : "nodrive";
                }
                writeStatus(state);
                if (debug != null) cameraDebug.update(debug, state, gear, recorder);
                if (beat % 4 == 0) writeHeartbeat(beat / 4);
                beat++;
                SystemClock.sleep(recorder.isActive() && !recorder.isPassive() ? POLL_MS : STATE_POLL_MS);
            } catch (Throwable t) {
                Log.e("guard loop", t);
                SystemClock.sleep(STATE_POLL_MS);
            }
        }
    }

    /**
     * True if the ignition is off: at once on the first reading, so a head unit that started up
     * with the car off never opens the camera; after that, once it has read off for
     * IGNITION_OFF_POLLS polls in a row. If the car can't say, it counts as on.
     */
    private boolean carOff() {
        Boolean on = Car.ignitionOn();
        if (on == null) on = true;
        if (on) ignitionOffPolls = 0;
        else if (lastIgnition == null) ignitionOffPolls = IGNITION_OFF_POLLS;
        else ignitionOffPolls = Math.min(ignitionOffPolls + 1, IGNITION_OFF_POLLS);
        if (!on.equals(lastIgnition)) {
            Log.i("ignition " + (on ? "on" : "off") + " (" + Car.describe() + ")");
            lastIgnition = on;
        }
        return ignitionOffPolls >= IGNITION_OFF_POLLS;
    }

    /** The stock app is about to open the rear camera: let go of it now. */
    void onStockCamera() {
        Log.i("the stock camera app is starting");
        lastBlockMs = SystemClock.uptimeMillis();
        if (recorder.isActive() && !recorder.isPassive()) handOff("stock camera");
    }

    /**
     * Lets go of the camera for the stock view, as release does, but goes on recording the stock
     * view's frames (Recorder.handOff); a debug session just stops.
     */
    private synchronized void handOff(String why) {
        if (!recorder.isActive() || recorder.isPassive()) return;
        if (recorder.isDebug()) {
            release(why);
            return;
        }
        Log.i("handing the camera over: " + why);
        final CountDownLatch released = new CountDownLatch(1);
        main.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                recorder.handOff(new Runnable() {
                    @Override
                    public void run() {
                        released.countDown();
                    }
                });
            }
        });
        awaitRelease(released);
    }

    /** Stops the recorder on the camera thread; if that doesn't finish in time, dies instead. */
    private synchronized void release(String why) {
        if (!recorder.isActive()) return;
        Log.i("releasing the camera: " + why);
        CountDownLatch done = new CountDownLatch(1);
        main.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                recorder.stop();
                done.countDown();
            }
        });
        awaitRelease(done);
    }

    /** Waits for the camera to be released; if it isn't in time, dies, which frees it. */
    private static void awaitRelease(CountDownLatch done) {
        boolean ok;
        try {
            ok = done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            ok = false;
        }
        if (!ok) {
            Log.e("camera release took over " + RELEASE_TIMEOUT_MS + " ms; exiting to free it", null);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /** RearCamera.getGearStatus(): asks the Cortex-M (/dev/tcc_cm_ctrl); 1 is reverse. */
    private static Method gearMethod() {
        try {
            return Class.forName("android.hardware.RearCamera").getMethod("getGearStatus");
        } catch (Throwable t) {
            Log.e("no RearCamera.getGearStatus(); relying on the stock camera broadcast alone", t);
            return null;
        }
    }

    private int gear() {
        if (gearStatus == null) return -1;
        try {
            return (Integer) gearStatus.invoke(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** True if the stock app has the rear camera open, per the rear camera service's dump. */
    private boolean stockCameraOpen() {
        IBinder service = ServiceManager.getService("media.rearcamera");
        if (service == null) return false;
        File dump = new File(config.stateDir, "rearcamera.txt");
        try {
            try (FileOutputStream out = new FileOutputStream(dump)) {
                service.dump(out.getFD(), new String[0]);
            }
            byte[] b = new byte[(int) Math.min(dump.length(), 64 * 1024)];
            try (FileInputStream in = new FileInputStream(dump)) {
                int n = in.read(b);
                return n > 0 && new String(b, 0, n, StandardCharsets.UTF_8).contains("Device is open");
            }
        } catch (Throwable t) {
            Log.e("can't check the rear camera service", t);
            return false;
        }
    }

    /** Picks up recording being turned on or off in the app. */
    private void checkAppSettings() {
        long m = appConf.lastModified(), length = appConf.length();
        // KitKat can report file timestamps only to the second. enabled=true/false
        // have different lengths, so rapid toggles must also compare the length.
        if (m == appConfModified && length == appConfLength) return;
        appConfModified = m;
        appConfLength = length;
        Config fresh = config.withAppSettings();
        saveSettingsCopy();
        if (fresh.enabled == !disabled) return;
        disabled = !fresh.enabled;
        Log.i("recording turned " + (fresh.enabled ? "on" : "off") + " in the app");
    }

    /** Copies the app's settings file to the state directory, so they outlive a reinstall of the app. */
    private void saveSettingsCopy() {
        if (!appConf.isFile()) return;
        File copy = config.savedSettings(), tmp = new File(config.stateDir, "settings.tmp");
        try (FileInputStream in = new FileInputStream(appConf); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } catch (IOException e) {
            Log.e("can't keep a copy of the app's settings", e);
            return;
        }
        if (!tmp.renameTo(copy)) Log.w("can't replace " + copy);
    }

    /**
     * What the player app shows: "recording", "reversing" (recording the reverse view's frames),
     * "debug" (a camera debug session), "reverse" (the camera is with the reverse view),
     * "caroff" (ignition off), "off", "nodrive" or "starting", and since when. Written only when
     * it changes, readable by any app. The heartbeat's age says whether the recorder is running.
     */
    private void writeStatus(String state) {
        String key = state + " " + driveMounted + " " + nullRequested + " " + disabled;
        if (key.equals(lastState)) return;
        File tmp = new File(config.stateDir, "status.tmp");
        String text = "state=" + state + "\nenabled=" + !disabled + "\nsince=" + System.currentTimeMillis() + "\nout=" + config.outDir
                + "\nmount=" + config.mount + "\nfps=" + config.fps + "\nbitrate=" + config.bitrate
                + "\nsegment=" + config.segmentSeconds + "\nminfree=" + config.minFreePercent
                + "\ndrive=" + driveMounted + "\nnullrecording=" + nullRequested + "\n";
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.e("can't write " + tmp, e);
            return;
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(status)) {
            Log.w("can't replace " + status);
            return;
        }
        lastState = key;
    }

    /** "pid count": the watchdog service kills the process if the count stops changing. */
    private void writeHeartbeat(long count) {
        try (FileOutputStream out = new FileOutputStream(heartbeat)) {
            out.write((android.os.Process.myPid() + " " + count + "\n").getBytes(StandardCharsets.US_ASCII));
            // The watchdog runs as NEMESIS root, which can't read past file permissions.
            if (count == 0) heartbeat.setReadable(true, false);
        } catch (IOException e) {
            if (count % 60 == 0) Log.e("can't write " + heartbeat, e);
        }
    }

    /** Receives CAMERA_APP_START without an app Context, registered straight with the ActivityManager. */
    private final class StockCameraWatcher extends IIntentReceiver.Stub {
        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
            onStockCamera();
            if (ordered) {
                try {
                    ActivityManagerNative.getDefault().finishReceiver(this, resultCode, data, extras, false);
                } catch (Throwable t) {
                    Log.e("finishReceiver", t);
                }
            }
        }
    }
}
