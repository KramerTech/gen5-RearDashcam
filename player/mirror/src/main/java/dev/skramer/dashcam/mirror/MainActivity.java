package dev.skramer.dashcam.mirror;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import dev.skramer.dashcam.view.RamView;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Live camera only. No file browser, playback, storage access, camera HAL or driving-guard override. */
public final class MainActivity extends Activity {
    /** "MIR2": the frame header carries the recording status. Must match the recorder's MirrorFeed. */
    private static final int MAGIC = 0x4d495232;
    private final Handler ui = new Handler();
    private final Object connection = new Object();
    private RamView picture;
    private TextView status;
    /** The recorder's MirrorFeed status codes; NONE while disconnected. */
    private static final int NONE = -1, RECORDING = 1, DISABLED = 2, NO_DRIVE = 3;
    private static final int RED = 0xffe11c1c;  // the red of the head unit's own status icons
    private static final int GREY = 0xff9aa3af;
    private View recordingBadge, recordingDot;
    private TextView recordingLabel;
    private Animation blink;
    private int shownStatus = NONE;
    /** The recorder's status as of the last header, or NONE. */
    private volatile int recording = NONE;
    private volatile long generation, captured;
    private volatile String message = "Connecting to dashcam…";
    private LocalSocket socket;
    private boolean visible;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setWindowAnimations(0);
        overridePendingTransition(0, 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(0xff000000);
        picture = new RamView(this);
        picture.setVisibility(View.INVISIBLE);
        layout.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(0xffffffff); status.setTextSize(22); status.setGravity(Gravity.CENTER);
        layout.addView(status, new FrameLayout.LayoutParams(-1, -1));
        label(layout, "Digital Rear View Mirror", Gravity.TOP | Gravity.LEFT);
        label(layout, "Tap anywhere to exit", Gravity.TOP | Gravity.RIGHT);
        // No box, so it stays clear of the picture.
        TextView caution = label(layout, "Caution: wide-angle lens\nObjects closer than they appear", Gravity.BOTTOM | Gravity.LEFT);
        caution.setBackgroundColor(0);
        recordingBadge = recordingBadge(layout);
        // Above the SurfaceView and labels: every point is the same exit target.
        View exit = new View(this);
        exit.setContentDescription("Tap anywhere to exit");
        exit.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        layout.addView(exit, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layout);
    }

    private TextView label(FrameLayout layout, String text, int corner) {
        TextView label = new TextView(this);
        label.setText(text); label.setTextColor(0xffffffff); label.setTextSize(16);
        label.setPadding(dp(10), dp(6), dp(10), dp(6));
        label.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, corner);
        position.setMargins(dp(16), dp(16), dp(16), dp(16));
        layout.addView(label, position);
        return label;
    }

    /**
     * Bottom right, like the corner labels: a slowly blinking red dot and "Recording to USB", or
     * an empty grey one and why not. Hidden while connecting, or if the recorder says neither.
     */
    private View recordingBadge(FrameLayout layout) {
        LinearLayout badge = new LinearLayout(this);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackgroundColor(0x99000000);
        recordingDot = new View(this);
        blink = new AlphaAnimation(1f, 0.15f);
        blink.setDuration(1000);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        badge.addView(recordingDot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        recordingLabel = new TextView(this);
        recordingLabel.setTextColor(0xffffffff); recordingLabel.setTextSize(16);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = dp(10);
        badge.addView(recordingLabel, gap);
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        position.setMargins(dp(16), dp(16), dp(16), dp(16));
        layout.addView(badge, position);
        return badge;
    }

    @Override protected void onStart() { super.onStart(); visible = true; }
    @Override protected void onStop() { visible = false; super.onStop(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (visible && "dev.skramer.dashcam.mirror.TOGGLE".equals(intent.getAction())) finish();
        else setIntent(intent);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onResume() {
        super.onResume();
        if (isFinishing()) return;
        picture.onResume();
        overridePendingTransition(0, 0);
        final long session = ++generation;
        captured = 0; recording = NONE; message = "Connecting to dashcam…";
        ui.post(refresh);
        new Thread("live mirror") { public void run() { receive(session); } }.start();
    }

    @Override public void onPause() {
        generation++; captured = 0; recording = NONE;
        synchronized (connection) {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            socket = null;
        }
        ui.removeCallbacks(refresh);
        picture.setVisibility(View.INVISIBLE); picture.onPause();
        super.onPause();
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            boolean fresh = captured != 0 && SystemClock.uptimeMillis() - captured < 750 && picture.error == null;
            picture.setVisibility(fresh ? View.VISIBLE : View.INVISIBLE);
            status.setVisibility(fresh ? View.GONE : View.VISIBLE);
            String text = picture.error != null ? "Live view unavailable" : captured != 0 ? "Camera feed interrupted" : message;
            if (!text.contentEquals(status.getText())) status.setText(text);
            if (recording != shownStatus) showRecording(recording);
            ui.postDelayed(this, 100);
        }
    };

    private void showRecording(int status) {
        shownStatus = status;
        String text = status == RECORDING ? "Recording to USB" : status == DISABLED ? "Recording Disabled"
                : status == NO_DRIVE ? "No Flash Drive" : null;
        recordingBadge.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text == null) return;
        recordingLabel.setText(text);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        if (status == RECORDING) {
            dot.setColor(RED);
            recordingDot.startAnimation(blink);
        } else {
            dot.setColor(0);
            dot.setStroke(dp(2), GREY);
            recordingDot.clearAnimation();
        }
        recordingDot.setBackgroundDrawable(dot);
    }

    private void receive(long session) {
        while (generation == session) {
            try (LocalSocket local = new LocalSocket()) {
                synchronized (connection) {
                    if (generation != session) return;
                    socket = local;
                }
                local.connect(new LocalSocketAddress("dev.skramer.dashcam.mirror"));
                local.setSoTimeout(1500);
                DataInputStream in = new DataInputStream(local.getInputStream());
                DataOutputStream out = new DataOutputStream(local.getOutputStream());
                byte[] frame = new byte[0];
                while (generation == session) {
                    out.writeInt(MAGIC); out.flush();
                    if (in.readInt() != MAGIC) throw new IOException("protocol mismatch");
                    int w = in.readInt(), h = in.readInt();
                    long at = in.readLong(); boolean mirror = in.readBoolean();
                    int status = in.readByte();
                    if (generation == session) recording = status;
                    if (w == -1 && h == 0) {
                        if (generation == session) ui.post(new Runnable() { public void run() { finish(); } });
                        return;
                    }
                    if (w == 0 && h == 0) {
                        if (generation == session) { captured = 0; message = "Waiting for live camera…"; }
                        continue;
                    }
                    if (w < 64 || h < 64 || w > 1920 || h > 1080) throw new IOException("invalid frame size");
                    int bytes = w * h * 4;
                    if (frame.length != bytes) frame = new byte[bytes];
                    in.readFully(frame);
                    if (generation != session) return;
                    if (at > 0 && SystemClock.uptimeMillis() - at < 750) {
                        picture.post(frame, w, h, mirror); captured = at;
                    }
                }
            } catch (IOException e) {
                if (generation != session) return;
                captured = 0; recording = NONE; message = "Dashcam listener unavailable";
                SystemClock.sleep(500);
            } finally {
                synchronized (connection) { if (generation == session) socket = null; }
            }
        }
    }
}
