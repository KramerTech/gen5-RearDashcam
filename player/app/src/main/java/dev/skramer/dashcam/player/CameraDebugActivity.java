package dev.skramer.dashcam.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Camera debug: the camera's frames straight from RAM next to a log, for working out why a car's
 * recordings come out blank.
 * <p>
 * While this screen is open it asks the recorder (through a request file, like the player's
 * lease) to run a debug session instead of recording: the camera and size picked here, checked
 * but not recorded. The recorder hands the camera to the stock reverse view as always. This
 * screen reads the frames itself (see {@link CameraRam}), so it also shows whatever the stock
 * view's session writes, which is the point of pressing the camera button with it open: the log
 * times when the picture appears against when the recorder let go of the camera.
 * <p>
 * The log mixes this screen's lines ("app") with the recorder's ("rec"). "Scan RAM" maps which
 * parts of the camera's memory are changing, to find where, and at what size, frames are written.
 * "Save Report" puts the log, a scan, the recorder's report (camera list, kernel log, logcat) and
 * the current picture on the USB drive.
 */
public final class CameraDebugActivity extends Activity {
    private static final String REGION = "rearcamera", VIQE = "rearcamera_viqe";
    private static final File RECORDER = new File("/data/nemesis/dashcam");
    /** 1140x720 and 1780x720 are the stock view's sizes for analog cameras and for camera type 6 (the EV6's). */
    private static final String[] SIZES = {"720x480", "1140x720", "1280x480", "1780x720", "1920x720"};
    private static final String[] CAMERAS = {"auto", "0", "3"};
    private static final long POLL_MS = 500;
    /** Stop asking for the camera once the screen has been out of sight this long. */
    private static final long HIDDEN_MS = 60_000;
    private static final long REPORT_WAIT_MS = 40_000;
    private static final int SLOTS = 4;
    private static final int KEEP_LINES = 400, REPORT_LINES = 5000;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final Handler ui = new Handler();
    private float dp;
    private TextView viewStatus, recStatus;
    /** The log as shown: a list, so adding a line only lays out the lines on screen. */
    private ArrayAdapter<String> lines;
    private RamView picture;
    private LinearLayout cameraRow, sizeRow;
    private Button reportButton;
    private Button nullRecordingToggle;
    private boolean nullRecordingOn, leaseSuspended;
    /** Experiment: the recorder asks the car to power the rear camera on while this is set. */
    private boolean powerTest;
    /** The last frame drawn, for the report's picture. */
    private volatile byte[] latest;
    private volatile int latestW, latestH;

    private String camera = "auto", size = SIZES[0];
    private final ArrayList<String> all = new ArrayList<>();
    private long seen = -1;
    private String report = "", lastState;
    private volatile String lastScan = "";
    private long reportAsked;
    private volatile long stoppedAt;
    private String recSize;
    private HashMap<String, String> rec = new HashMap<>();
    private long lastFrames = -1, lastFramesAt;

    private volatile boolean running = true;
    private volatile CameraRam ram;
    /** The size the frames are read as: the recorder's session's, or the one asked for. */
    private volatile int viewW = 720, viewH = 480;
    /** Flip it left to right, as the recorder does (its mirror setting; on unless it says otherwise). */
    private volatile boolean mirror = true;
    /** For the auto test: frames the viewer has seen completed, and its last picture verdict. */
    private volatile long framesSeen;
    private volatile String pictureKind = "";
    /** Camera parameters to ask for: "auto", "hal" or "ours" (CameraDebug.Request.halParams). */
    private volatile String params = "auto";
    private Button autoButton, powerButton;
    private volatile boolean autoRunning;
    private Thread viewer, scanner;
    private final Object scanLock = new Object();
    private boolean scanWanted;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DrivingGuard.onCreate(this);
        dp = getResources().getDisplayMetrics().density;
        setContentView(build());
        log("camera debug " + appVersion() + " on " + Build.DISPLAY);
        log("preview pauses saved recording; Record to /dev/null keeps the camera and encoder running");
        log("to test: wait 10 s, press the camera button (in park) for a few seconds, close the stock view, wait 10 s, Save Report");
        viewer = new Thread("ram view") {
            @Override
            public void run() {
                view();
            }
        };
        scanner = new Thread("ram scan") {
            @Override
            public void run() {
                scanLoop();
            }
        };
        viewer.start();
        scanner.start();
        ui.post(poll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DrivingGuard.check(this);
        picture.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        picture.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stoppedAt = 0;
        leaseSuspended = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = SystemClock.uptimeMillis();
        if (nullRecordingOn || RecorderLink.nullRecording(this)) {
            leaseSuspended = true;
            new File(getFilesDir(), RecorderLink.DEBUG).delete();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        ui.removeCallbacksAndMessages(null);
        new File(getFilesDir(), RecorderLink.DEBUG).delete();
        synchronized (scanLock) {
            scanLock.notifyAll();
        }
        boolean stopped = join(viewer) & join(scanner);
        CameraRam r = ram;
        if (r != null && stopped) r.unmap();
    }

    private static boolean join(Thread t) {
        try {
            t.join(500);
        } catch (InterruptedException ignored) {
            // checked below
        }
        return !t.isAlive();
    }

    // ---- layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getResources().getColor(R.color.panel));
        bar.setPadding(px(12), 0, px(12), 0);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(px(72), px(56)));
        TextView title = text("Camera Debug", 24, R.color.text);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = px(16);
        bar.addView(title, tl);
        Button scan = new Button(this);
        scan.setText("Scan RAM");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestScan();
            }
        });
        reportButton = new Button(this);
        reportButton.setText("Save Report");
        reportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askReport();
            }
        });
        nullRecordingToggle = new Button(this);
        styleNullToggle(false);
        nullRecordingToggle.setVisibility(View.GONE);
        nullRecordingToggle.setEnabled(false);
        nullRecordingToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View button) {
                boolean checked = !nullRecordingOn;
                try {
                    RecorderLink.setNullRecording(CameraDebugActivity.this, checked);
                    if (checked) { camera = "auto"; size = SIZES[0]; updatePicks(); }
                    log(checked ? "Recording to /dev/null; camera and encoder continue after leaving this page"
                            : "Null recording disabled");
                } catch (IOException e) {
                    log("can't change null recording: " + e);
                }
                updateNullToggle();
            }
        });
        bar.addView(nullRecordingToggle, barButton());
        final Button power = powerButton = new Button(this);
        power.setText("Power test: off");
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (autoRunning) return;
                powerTest = !powerTest;
                power.setText(powerTest ? "Power test: ON" : "Power test: off");
                log(powerTest ? "power test on: the recorder asks the car to power the rear camera on, then reopens it"
                        : "power test off: the recorder asks the car to power the rear camera off");
                writeRequest();
            }
        });
        bar.addView(power, barButton());
        autoButton = new Button(this);
        autoButton.setText("Auto Test");
        autoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAutoTest();
            }
        });
        bar.addView(autoButton, barButton());
        bar.addView(scan, barButton());
        bar.addView(reportButton, barButton());
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(72)));

        LinearLayout body = new LinearLayout(this);
        body.setPadding(px(12), px(10), px(12), px(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF000000);
        picture = new RamView(this);
        frame.addView(picture, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        viewStatus = text("", 14, R.color.text);
        viewStatus.setBackgroundColor(0xA0000000);
        viewStatus.setPadding(px(8), px(4), px(8), px(4));
        frame.addView(viewStatus, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
        left.addView(frame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        cameraRow = pickRow("Camera", CAMERAS);
        sizeRow = pickRow("Size", SIZES);
        left.addView(cameraRow);
        left.addView(sizeRow);
        updatePicks();
        body.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 11));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(0xFF05080A);
        recStatus = text("recorder: …", 13, R.color.text_dim);
        recStatus.setTypeface(Typeface.MONOSPACE);
        recStatus.setPadding(px(8), px(6), px(8), px(6));
        recStatus.setBackgroundColor(0xFF10161C);
        right.addView(recStatus);
        lines = new ArrayAdapter<String>(this, 0) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                TextView t = (TextView) convert;
                if (t == null) {
                    t = new TextView(CameraDebugActivity.this);
                    t.setTypeface(Typeface.MONOSPACE);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    t.setTextColor(0xFF7CE38B);
                    t.setPadding(px(8), px(1), px(8), px(1));
                }
                t.setText(getItem(position));
                return t;
            }
        };
        ListView terminal = new ListView(this);
        terminal.setAdapter(lines);
        terminal.setDivider(null);
        terminal.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);  // follows new lines unless scrolled up
        terminal.setStackFromBottom(true);
        right.addView(terminal, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 9);
        rl.leftMargin = px(12);
        body.addView(right, rl);
        root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private LinearLayout.LayoutParams barButton() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(56));
        lp.leftMargin = px(8);
        return lp;
    }

    private LinearLayout pickRow(String label, String[] values) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(8), 0, 0);
        TextView l = text(label, 16, R.color.text_dim);
        row.addView(l, new LinearLayout.LayoutParams(px(72), LinearLayout.LayoutParams.WRAP_CONTENT));
        for (final String v : values) {
            Button b = new Button(this);
            b.setText(v.replace('x', '×'));
            b.setTag(v);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setMinHeight(px(48));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (autoRunning) return;
                    if (label.equals("Camera")) camera = v;
                    else size = v;
                    params = "auto";
                    log("asking the recorder for camera " + camera + " at " + size);
                    updatePicks();
                    writeRequest();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(48), 1);
            lp.leftMargin = px(6);
            row.addView(b, lp);
        }
        return row;
    }

    private void updatePicks() {
        for (LinearLayout row : new LinearLayout[] {cameraRow, sizeRow}) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View v = row.getChildAt(i);
                if (v.getTag() != null) v.setActivated(v.getTag().equals(row == cameraRow ? camera : size));
            }
        }
    }

    // ---- auto test

    /** One setup the auto test tries: camera, size, parameters ("hal"/"ours"), power test. */
    private static final String[][] AUTO_STEPS = {
        {"auto", "1780x720", "hal", "0"},
        {"auto", "1780x720", "ours", "0"},
        {"auto", "1710x720", "hal", "0"},
        {"auto", "1140x720", "hal", "0"},
        {"auto", "1920x720", "hal", "0"},
        {"auto", "1280x720", "hal", "0"},
        {"auto", "720x480", "hal", "0"},
        {"auto", "720x480", "ours", "0"},
        {"3", "1780x720", "hal", "0"},
        {"auto", "1780x720", "hal", "1"},
    };
    private static final long AUTO_SETTLE_MS = 5000, AUTO_WATCH_MS = 10000;

    /**
     * Tries each of AUTO_STEPS for AUTO_WATCH_MS (after AUTO_SETTLE_MS for the recorder to reopen
     * the camera), logs how many frames arrived and what they looked like, then saves a report:
     * one press for a tester instead of a round trip per setup.
     */
    private void startAutoTest() {
        if (autoRunning || nullRecordingOn) return;
        autoRunning = true;
        autoButton.setEnabled(false);
        autoButton.setText("Testing…");
        log("auto test: " + AUTO_STEPS.length + " setups, about " + AUTO_STEPS.length * (AUTO_SETTLE_MS + AUTO_WATCH_MS) / 1000
                + " s; don't touch the screen or shift out of park until the report is saved");
        new Thread("auto test") {
            @Override
            public void run() {
                StringBuilder summary = new StringBuilder("auto test results:");
                for (int i = 0; i < AUTO_STEPS.length && running && stoppedAt == 0; i++) {
                    final String[] s = AUTO_STEPS[i];
                    final String what = "camera " + s[0] + " at " + s[1] + ", " + s[2] + " parameters" + (s[3].equals("1") ? ", power test on" : "");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            camera = s[0];
                            size = s[1];
                            params = s[2];
                            powerTest = s[3].equals("1");
                            powerButton.setText(powerTest ? "Power test: ON" : "Power test: off");
                            updatePicks();
                            writeRequest();
                        }
                    });
                    log("auto test " + (i + 1) + "/" + AUTO_STEPS.length + ": " + what);
                    SystemClock.sleep(AUTO_SETTLE_MS);
                    long f0 = framesSeen;
                    SystemClock.sleep(AUTO_WATCH_MS);
                    long frames = framesSeen - f0;
                    String r = String.format(Locale.US, "%s: %d frames in %d s (%.1f/s), picture %s", what, frames,
                            AUTO_WATCH_MS / 1000, frames * 1000f / AUTO_WATCH_MS, pictureKind);
                    log("auto test " + (i + 1) + " result: " + r);
                    summary.append("\n  ").append(i + 1).append(". ").append(r);
                }
                final String done = summary.toString();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        for (String line : done.split("\n")) log(line);
                        camera = "auto";
                        size = SIZES[0];
                        params = "auto";
                        powerTest = false;
                        powerButton.setText("Power test: off");
                        updatePicks();
                        writeRequest();
                        autoRunning = false;
                        autoButton.setEnabled(true);
                        autoButton.setText("Auto Test");
                        askReport();
                    }
                });
            }
        }.start();
    }

    // ---- talking to the recorder

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (stoppedAt != 0 && SystemClock.uptimeMillis() - stoppedAt > HIDDEN_MS) {
                finish();  // out of sight: let the recorder go back to recording
                return;
            }
            updateNullToggle();
            writeRequest();
            readRecorder();
            ui.postDelayed(this, POLL_MS);
        }
    };

    private void updateNullToggle() {
        boolean mounted = RecorderLink.driveMounted(RecorderLink.read().mount);
        if (mounted) {
            try { RecorderLink.setNullRecording(this, false); }
            catch (IOException e) { log("can't clear null recording request: " + e); }
        }
        boolean on = !mounted && RecorderLink.nullRecording(this);
        if (on != nullRecordingOn) styleNullToggle(on);
        nullRecordingOn = on;
        nullRecordingToggle.setEnabled(!mounted);
        nullRecordingToggle.setVisibility(mounted ? View.GONE : View.VISIBLE);
    }

    private void styleNullToggle(boolean on) {
        SpannableString label = new SpannableString((on ? "●" : "○") + "  Record to /dev/null");
        label.setSpan(new ForegroundColorSpan(on ? 0xFF22C55E : getResources().getColor(R.color.text_dim)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        nullRecordingToggle.setText(label);
        nullRecordingToggle.setActivated(false);
        nullRecordingToggle.setContentDescription("Record to /dev/null, " + (on ? "on" : "off"));
    }

    /** The request, rewritten every poll: that's also what keeps it fresh. */
    private void writeRequest() {
        if (leaseSuspended) return;
        File dir = getFilesDir();
        File tmp = new File(dir, RecorderLink.DEBUG + ".tmp");
        String text = "camera=" + camera + "\nsize=" + size + "\nseen=" + seen + "\nreport=" + report
                + "\npower=" + (powerTest ? 1 : 0) + "\nparams=" + params + "\n";
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            log("can't write the request: " + e);
            return;
        }
        dir.setExecutable(true, false);
        tmp.setReadable(true, false);
        if (!tmp.renameTo(new File(dir, RecorderLink.DEBUG))) log("can't replace the request file");
    }

    private void readRecorder() {
        HashMap<String, String> s = new HashMap<>();
        ArrayList<Long> numbers = new ArrayList<>();
        ArrayList<String> lines = new ArrayList<>();
        File f = new File(RECORDER, "debug");
        try (BufferedReader in = new BufferedReader(new FileReader(f))) {
            for (String line; (line = in.readLine()) != null; ) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1);
                if (k.startsWith("log.")) {
                    try {
                        numbers.add(Long.parseLong(k.substring(4)));
                        lines.add(v);
                    } catch (NumberFormatException ignored) {
                        // not a log line
                    }
                } else {
                    s.put(k, v);
                }
            }
        } catch (IOException e) {
            // not there yet
        }
        // A restarted recorder numbers its lines from 0 again.
        boolean restarted = !numbers.isEmpty() && numbers.get(numbers.size() - 1) < seen;
        for (int i = 0; i < numbers.size(); i++) {
            if (restarted || numbers.get(i) > seen) add("rec " + lines.get(i));
        }
        if (!numbers.isEmpty()) seen = Math.max(restarted ? -1 : seen, numbers.get(numbers.size() - 1));
        long time = parse(s.get("time"), 0);
        String state;
        if (s.isEmpty() || Math.abs(System.currentTimeMillis() - time) > 3000) {
            RecorderLink r = RecorderLink.read();
            state = !r.running ? "not running" : "not answering (an older recorder? state " + r.state + ")";
            s.clear();
        } else {
            state = s.containsKey("state") ? s.get("state") : "?";
        }
        if (!state.equals(lastState)) {
            log("recorder: " + state);
            lastState = state;
        }
        rec = s;
        enableCameras(s.get("cameras"));
        boolean session = "debug".equals(state) || "recording-null".equals(state);
        for (LinearLayout row : new LinearLayout[] {cameraRow, sizeRow}) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child.getTag() != null && (row == sizeRow || child.getTag().equals("auto")))
                    child.setEnabled(!nullRecordingOn);
                else if (child.getTag() != null && nullRecordingOn) child.setEnabled(false);
            }
        }
        recSize = session ? s.get("size") : null;
        if (s.containsKey("mirror")) mirror = !"false".equals(s.get("mirror"));
        String sz = recSize != null ? recSize : size;
        try {
            String[] wh = sz.split("x");
            viewW = Integer.parseInt(wh[0]);
            viewH = Integer.parseInt(wh[1]);
        } catch (RuntimeException ignored) {
            // keep
        }
        StringBuilder b = new StringBuilder("recorder: ").append(state);
        if (!s.isEmpty()) b.append("  gear ").append(s.get("gear")).append("  camera type ").append(s.get("type"));
        if (session) {
            long frames = parse(s.get("frames"), 0), now = SystemClock.uptimeMillis();
            String rate = "";
            if (lastFrames >= 0 && frames >= lastFrames && now > lastFramesAt) {
                rate = String.format(Locale.US, " (%.1f/s)", (frames - lastFrames) * 1000f / (now - lastFramesAt));
            }
            lastFrames = frames;
            lastFramesAt = now;
            b.append("\ncamera ").append(s.get("camera")).append(" at ").append(s.get("size"))
                    .append("  signal ").append(s.get("signal")).append("  frames ").append(frames).append(rate)
                    .append("\npicture: ").append(s.get("picture")).append("  luma ").append(s.get("luma"))
                    .append("  torn ").append(s.get("torn")).append(" busy ").append(s.get("busy")).append(" missed ").append(s.get("missed"));
        } else {
            lastFrames = -1;
        }
        recStatus.setText(b);
        if (!report.isEmpty() && reportAsked != 0) {
            if (report.equals(s.get("report"))) {
                reportAsked = 0;
                saveReport(true);
            } else if (SystemClock.uptimeMillis() - reportAsked > REPORT_WAIT_MS) {
                reportAsked = 0;
                log("the recorder didn't send its report; saving without it");
                saveReport(false);
            }
        }
    }

    /** Only cameras the recorder says exist ("N cameras; ..."); "auto" always. */
    private void enableCameras(String cameras) {
        int n = Integer.MAX_VALUE;
        try {
            n = Integer.parseInt(cameras.substring(0, cameras.indexOf(' ')));
        } catch (RuntimeException ignored) {
            // unknown: leave them all
        }
        for (int i = 0; i < cameraRow.getChildCount(); i++) {
            Object tag = cameraRow.getChildAt(i).getTag();
            if (tag != null && !tag.equals("auto")) cameraRow.getChildAt(i).setEnabled(Integer.parseInt((String) tag) < n);
        }
    }

    private static long parse(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- report

    private void askReport() {
        report = String.valueOf(System.currentTimeMillis());
        reportAsked = SystemClock.uptimeMillis();
        reportButton.setEnabled(false);
        log("collecting the report (the recorder takes up to half a minute)…");
        requestScan();
        writeRequest();
        if (rec.isEmpty()) {
            reportAsked = 0;
            log("the recorder isn't answering; saving without its part");
            saveReport(false);
        }
    }

    private void saveReport(boolean withRecorder) {
        reportButton.setEnabled(true);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(RecorderLink.read().out);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = getExternalFilesDir(null);
            log("no drive; saving to " + dir);
        }
        File txt = new File(dir, "camera-debug-" + stamp + ".txt");
        StringBuilder b = new StringBuilder();
        b.append("Dashcam camera debug report ").append(stamp).append("\napp ").append(appVersion())
                .append("\nfirmware ").append(Build.DISPLAY).append(" / ").append(Build.FINGERPRINT)
                .append("\nasked for camera ").append(camera).append(" at ").append(size).append("\n\n===== last RAM scan =====\n")
                .append(lastScan.isEmpty() ? "(none)\n" : lastScan).append("\n===== log =====\n");
        synchronized (all) {
            for (String l : all) b.append(l).append('\n');
        }
        b.append("\n===== recorder status =====\n");
        for (String k : rec.keySet()) b.append(k).append('=').append(rec.get(k)).append('\n');
        if (withRecorder) {
            b.append('\n');
            try (BufferedReader in = new BufferedReader(new FileReader(new File(RECORDER, "debug-report.txt")))) {
                for (String line; (line = in.readLine()) != null; ) b.append(line).append('\n');
            } catch (IOException e) {
                b.append("(can't read the recorder's report: ").append(e).append(")\n");
            }
        }
        try (FileOutputStream out = new FileOutputStream(txt)) {
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            log("report saved: " + txt);
        } catch (IOException e) {
            log("can't save the report: " + e);
        }
        Bitmap bm = snapshot();
        if (bm != null) {
            File png = new File(dir, "camera-debug-" + stamp + ".png");
            try (FileOutputStream out = new FileOutputStream(png)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, out);
                log("picture saved: " + png);
            } catch (IOException e) {
                log("can't save the picture: " + e);
            }
        }
        final File firmware = new File(dir, "firmware-" + Build.PRODUCT + "-" + Build.TIME / 1000);
        new Thread("firmware copy") {
            @Override
            public void run() {
                copyFirmware(firmware);
            }
        }.start();
    }

    /**
     * Copies this firmware's stock camera app code and the framework it's compiled against (the
     * BOOTCLASSPATH odex files, about 40 MB), so its camera setup can be read for another car's
     * firmware. Files under /system are readable by any app. Done once per firmware: a file
     * already there at the same size is skipped.
     */
    private void copyFirmware(File to) {
        List<File> files = new ArrayList<>();
        files.add(new File("/system/app/HKMC_RearCamera.odex"));
        String bcp = System.getenv("BOOTCLASSPATH");
        for (String jar : bcp == null ? new String[0] : bcp.split(":")) {
            if (jar.endsWith(".jar")) files.add(new File(jar.substring(0, jar.length() - 4) + ".odex"));
        }
        if (!to.isDirectory() && !to.mkdirs()) {
            log("can't make " + to);
            return;
        }
        int copied = 0;
        byte[] buf = new byte[1 << 16];
        for (File f : files) {
            File dst = new File(to, f.getName());
            if (!f.isFile() || dst.length() == f.length()) continue;
            try (FileInputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(dst)) {
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                copied++;
            } catch (IOException e) {
                log("can't copy " + f + ": " + e);
            }
        }
        if (copied > 0) log("firmware files copied to " + to + " (" + copied + " files); send that folder along with the report");
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- log

    /** Adds a line from any thread, timed. */
    private void log(String msg) {
        final String line = TIME.format(new Date()) + " app " + msg;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            add(line);
        } else {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    add(line);
                }
            });
        }
    }

    /** On the main thread. */
    private void add(String line) {
        synchronized (all) {
            all.add(line);
            if (all.size() > REPORT_LINES) all.remove(0);
        }
        if (lines.getCount() >= KEEP_LINES) lines.remove(lines.getItem(0));
        lines.add(line);  // the list shows the end, and only lays out the lines on screen
    }

    // ---- the frames

    /**
     * Watches the ring of four frames, as the recorder does (a buffer starting to change means
     * the one before it is complete), draws completed frames at up to 30 a second, and logs when
     * the ring starts or stops changing and when the picture comes and goes, plus a summary every
     * second (every 10 s while nothing changes). With nothing changing it shows buffer 0 once a
     * second.
     */
    private void view() {
        try {
            ram = CameraRam.map(REGION);
            log(String.format(Locale.US, "mapped %s: 0x%x, 0x%x bytes", REGION, ram.physical, ram.length));
        } catch (Throwable t) {
            log("can't map the camera RAM: " + t);
            return;
        }
        requestScan();
        int w = 0, h = 0;
        long frameBytes = 0;
        int[] strip = null;
        byte[][] frames = new byte[2][];
        int next = 0;
        int[] hash = new int[SLOTS];
        int writing = -1, completed = -1, shownSlot = -1;
        long starts = 0, completions = 0, shownCount = 0, readNs = 0, lastShownAt = 0;
        long statsAt = SystemClock.uptimeMillis(), summaryAt = statsAt, uploadsBefore = 0, uploadNsBefore = 0;
        long lastChangeAt = 0, cpuAt = cpuTicks();
        boolean first = true, changing = false;
        String kind = null, detail = "", unfit = null, glError = null;
        try {
            while (running) {
                // The encoder can keep running behind AA; the hidden debug preview
                // must not keep copying/uploading another 30 full frames per second.
                if (stoppedAt != 0) {
                    first = true;
                    writing = completed = shownSlot = -1;
                    SystemClock.sleep(200);
                    continue;
                }
                if (viewW != w || viewH != h) {
                    w = viewW;
                    h = viewH;
                    frameBytes = (long) w * h * 4;
                    if (frameBytes * SLOTS > ram.length) {
                        String sz = w + "x" + h;
                        if (!sz.equals(unfit)) log(SLOTS + " " + sz + " frames don't fit in " + REGION);
                        unfit = sz;
                        SystemClock.sleep(500);
                        w = 0;
                        continue;
                    }
                    unfit = null;
                    strip = new int[w / 3];
                    frames[0] = new byte[(int) frameBytes];
                    frames[1] = new byte[(int) frameBytes];
                    first = true;
                    writing = completed = shownSlot = -1;
                    log("reading the ring as " + SLOTS + " " + w + "x" + h + " BGRA frames");
                }
                int count = 0, slot = -1;
                for (int i = 0; i < SLOTS; i++) {
                    ram.read(i * frameBytes + ((long) (h / 16) * w + w / 3) * 4, strip, 0, strip.length);
                    int x = 1;
                    for (int p : strip) x = 31 * x + p;
                    if (!first && x != hash[i]) {
                        count++;
                        slot = i;
                    }
                    hash[i] = x;
                }
                first = false;
                long now = SystemClock.uptimeMillis();
                if (count > 0) {
                    starts += count;
                    lastChangeAt = now;
                    if (count == 1 && slot != writing) {
                        if (writing >= 0) {
                            completed = writing;
                            completions++;
                            framesSeen++;
                        }
                        writing = slot;
                    } else if (count > 1) {
                        writing = -1;
                    }
                }
                boolean nowChanging = now - lastChangeAt < 1000 && lastChangeAt != 0;
                if (nowChanging != changing) {
                    changing = nowChanging;
                    log(changing ? "the ring is changing: frames are being written" : "the ring stopped changing");
                    requestScan();
                }
                int show = -1;
                if (changing && completed >= 0 && completed != shownSlot && now - lastShownAt >= 33) show = completed;
                else if (!changing && now - lastShownAt >= 1000) show = 0;
                if (show >= 0) {
                    byte[] frame = frames[next];
                    next ^= 1;
                    long t0 = System.nanoTime();
                    ram.readBytes(show * frameBytes, frame, frame.length);
                    readNs += System.nanoTime() - t0;
                    shownSlot = changing ? show : -1;
                    lastShownAt = now;
                    shownCount++;
                    String[] v = verdict(frame, w, h);
                    detail = v[1];
                    if (!v[0].equals(kind)) {
                        log("picture: " + v[1]);
                        kind = v[0];
                    }
                    pictureKind = v[1];
                    picture.post(frame, w, h, mirror);
                    latest = frame;
                    latestW = w;
                    latestH = h;
                }
                if (picture.error != null && !picture.error.equals(glError)) {
                    glError = picture.error;
                    log("drawing failed: " + glError);
                }
                if (now - statsAt >= 1000) {
                    float secs = (now - statsAt) / 1000f;
                    long uploads = picture.uploads, uploadNs = picture.uploadNs;
                    long drawn = uploads - uploadsBefore;
                    long cpu = cpuTicks();
                    final String line = String.format(Locale.US,
                            "RAM %dx%d: writes %.0f/s, frames %.1f/s, shown %.1f/s (read %.1f ms, GL upload %.1f ms), app CPU %.0f%%\n%s",
                            w, h, starts / secs, completions / secs, drawn / secs,
                            shownCount == 0 ? 0 : readNs / 1e6 / shownCount, drawn == 0 ? 0 : (uploadNs - uploadNsBefore) / 1e6 / drawn,
                            (cpu - cpuAt) / secs, detail);
                    cpuAt = cpu;
                    if (now - summaryAt >= (changing ? 1000 : 10_000)) {
                        log(line.replace("\n", "; "));
                        summaryAt = now;
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            viewStatus.setText(line);
                        }
                    });
                    starts = completions = shownCount = readNs = 0;
                    uploadsBefore = uploads;
                    uploadNsBefore = uploadNs;
                    statsAt = now;
                }
                SystemClock.sleep(2);
            }
        } catch (Throwable t) {
            log("reading the camera RAM failed: " + t);
        }
    }

    /**
     * What a frame of BGRA bytes looks like, from a grid of pixels: its kind ("ok", "flat" or
     * "not BGRA") and a description. Flat, every pixel exactly one color, means no signal: real
     * video always has noise. The hardware writes every pixel opaque.
     */
    private static String[] verdict(byte[] f, int w, int h) {
        int first = pixel(f, (h / 16) * w + w / 24), opaque = 0, n = 0;
        boolean flat = true;
        long luma = 0;
        for (int row = h / 16; row < h; row += h / 8) {
            for (int col = w / 24; col < w; col += w / 12) {
                int p = pixel(f, row * w + col);
                n++;
                if (p >>> 24 == 0xff) opaque++;
                if (p != first) flat = false;
                luma += (77 * ((p >> 16) & 0xff) + 150 * ((p >> 8) & 0xff) + 29 * (p & 0xff)) >> 8;
            }
        }
        if (opaque < n) {
            return new String[] {"not BGRA", String.format(Locale.US, "not BGRA: %d of %d grid pixels opaque, first 0x%08x", opaque, n, first)};
        }
        if (flat) return new String[] {"flat", String.format(Locale.US, "one color 0x%08x (no picture)", first)};
        return new String[] {"ok", "ok, brightness " + luma / n};
    }

    /** This app's CPU time so far (user + system), in hundredths of a second, so per-second deltas are percent of one core. */
    private static long cpuTicks() {
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String s = in.readLine();
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Pixel i as 0xAARRGGBB. */
    private static int pixel(byte[] f, int i) {
        int o = i * 4;
        return (f[o + 3] & 0xff) << 24 | (f[o + 2] & 0xff) << 16 | (f[o + 1] & 0xff) << 8 | (f[o] & 0xff);
    }

    /** The last frame drawn, as the screen shows it (mirrored if it's mirrored), for the report. */
    private Bitmap snapshot() {
        byte[] f = latest;
        int w = latestW, h = latestH;
        if (f == null || f.length != w * h * 4) return null;
        f = f.clone();  // the reader may be filling it again
        int[] argb = new int[w * h];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int p = pixel(f, row * w + col) | 0xff000000;
                argb[row * w + (mirror ? w - 1 - col : col)] = p;
            }
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
    }

    // ---- RAM scan

    private void requestScan() {
        synchronized (scanLock) {
            scanWanted = true;
            scanLock.notifyAll();
        }
    }

    /** Scans when asked, at most once every 3 s. */
    private void scanLoop() {
        long last = 0;
        while (running) {
            synchronized (scanLock) {
                while (running && !scanWanted) {
                    try {
                        scanLock.wait();
                    } catch (InterruptedException ignored) {
                        // checks running
                    }
                }
                scanWanted = false;
            }
            long wait = last + 3000 - SystemClock.uptimeMillis();
            if (wait > 0) SystemClock.sleep(wait);
            if (!running || ram == null) continue;
            last = SystemClock.uptimeMillis();
            try {
                StringBuilder b = new StringBuilder();
                b.append(pmap());
                b.append(scan(ram));
                try {
                    CameraRam viqe = CameraRam.map(VIQE);
                    try {
                        b.append(scan(viqe));
                    } finally {
                        viqe.unmap();
                    }
                } catch (Throwable t) {
                    b.append(VIQE).append(": ").append(t).append('\n');
                }
                lastScan = b.toString();
                for (String line : lastScan.split("\n")) log(line);
            } catch (Throwable t) {
                log("RAM scan failed: " + t);
            }
        }
    }

    private static String pmap() {
        StringBuilder b = new StringBuilder("pmap:");
        try {
            for (String[] f : CameraRam.regions()) {
                if (f[2].contains("camera") || f[2].contains("parking") || f[2].contains("viqe") || f[2].contains("lvds")) {
                    b.append(' ').append(f[2]).append(' ').append(f[0]).append('+').append(f[1]);
                }
            }
        } catch (IOException e) {
            b.append(' ').append(e);
        }
        return b.append('\n').toString();
    }

    private static final int BLOCK = 16384, PROBE = 16, PASSES = 8;
    private static final String[] GUESSES = {"720x480", "760x480", "1140x720", "1280x480", "1280x538", "1280x720",
            "1420x538", "1780x720", "1920x720", "1920x1080"};

    /**
     * Which parts of a region change, over about a second: 16 pixels from the middle of every
     * 16 KiB, read 8 times. Prints runs of blocks alike (changing or still; opaque BGRA, zero,
     * one value, or other), and guesses the frame size from how far the changing part reaches,
     * as the hardware writes a ring of four frames from the start.
     */
    private String scan(CameraRam r) throws Exception {
        int blocks = (int) (r.length / BLOCK);
        int[] probe = new int[PROBE], hashes = new int[blocks];
        boolean[] changed = new boolean[blocks];
        char[] kind = new char[blocks];
        for (int pass = 0; pass < PASSES && running; pass++) {
            for (int bl = 0; bl < blocks; bl++) {
                r.read((long) bl * BLOCK + BLOCK / 2, probe, 0, PROBE);
                int x = 1, opaque = 0, zero = 0;
                boolean same = true;
                for (int p : probe) {
                    x = 31 * x + p;
                    if (p >>> 24 == 0xff) opaque++;
                    if (p == 0) zero++;
                    if (p != probe[0]) same = false;
                }
                if (pass > 0 && x != hashes[bl]) changed[bl] = true;
                hashes[bl] = x;
                kind[bl] = zero == PROBE ? '0' : same ? '1' : opaque == PROBE ? 'A' : 'x';
            }
            SystemClock.sleep(120);
        }
        StringBuilder b = new StringBuilder(String.format(Locale.US, "scan %s (0x%x bytes, every 16 KiB):\n", r.name, r.length));
        int runs = 0, firstChanged = -1, lastChanged = -1;
        for (int start = 0; start < blocks; ) {
            int end = start;
            while (end < blocks && changed[end] == changed[start] && kind[end] == kind[start]) end++;
            if (changed[start]) {
                if (firstChanged < 0) firstChanged = start;
                lastChanged = end - 1;
            }
            int run = start;
            start = end;
            if (runs++ < 40) {
                b.append(String.format(Locale.US, "  0x%07x-0x%07x %6.2f MiB %s, %s\n", (long) run * BLOCK, (long) end * BLOCK,
                        (end - run) * BLOCK / 1048576.0, changed[run] ? "CHANGING" : "still",
                        kind[run] == '0' ? "zero" : kind[run] == '1' ? "one value" : kind[run] == 'A' ? "opaque (BGRA-like)" : "mixed/not opaque"));
            }
        }
        if (runs > 40) b.append("  (").append(runs - 40).append(" more runs)\n");
        if (firstChanged < 0) {
            b.append("  nothing changed\n");
        } else {
            long reach = (long) (lastChanged + 1) * BLOCK;
            b.append(String.format(Locale.US, "  changing from 0x%x to about 0x%x: a ring of 4 would be about 0x%x bytes per frame",
                    (long) firstChanged * BLOCK, reach, reach / 4));
            String match = null;
            for (String g : GUESSES) {
                String[] wh = g.split("x");
                long bytes = Long.parseLong(wh[0]) * Long.parseLong(wh[1]) * 4 * 4;
                if (Math.abs(bytes - reach) <= 2L * BLOCK) match = g;
            }
            b.append(match != null ? " = " + match + " BGRA\n" : " (no usual size)\n");
            int[] head = new int[8];
            for (int i = 0; i < 4; i++) {
                long at = (long) firstChanged * BLOCK + i * (reach - (long) firstChanged * BLOCK) / 4;
                at -= at % 4;
                r.read(at, head, 0, head.length);
                b.append(String.format(Locale.US, "  at 0x%07x:", at));
                for (int p : head) b.append(String.format(Locale.US, " %08x", p));
                b.append('\n');
            }
        }
        return b.toString();
    }

    // ---- helpers

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getResources().getColor(color));
        return t;
    }

    private int px(int dps) {
        return Math.round(dps * dp);
    }
}
