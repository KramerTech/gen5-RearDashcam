package dev.skramer.dashcam.player;

import android.app.Activity;
import android.os.Bundle;
import android.os.StatFs;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.Locale;

/** Recording settings, saved to the file the recorder reads. Changes restart the recorder. */
public final class SettingsActivity extends Activity {
    private static final int[] FPS = {10, 15, 20, 30};
    private static final int[] BITRATES = {1_000_000, 1_500_000, 2_000_000, 3_000_000, 5_000_000};
    private static final int[] SEGMENTS = {60, 120, 300};
    private static final int[] MIN_FREE = {5, 10, 20};

    private RecorderLink.Settings settings;
    private RecorderLink rec;
    private LinearLayout rows;
    private TextView estimate;
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DrivingGuard.onCreate(this);
        dp = getResources().getDisplayMetrics().density;
        settings = RecorderLink.Settings.read(this);
        rec = RecorderLink.read();
        if (settings.fps == 0) settings.fps = rec.fps;
        if (settings.bitrate == 0) settings.bitrate = rec.bitrate;
        if (settings.segment == 0) settings.segment = rec.segment;
        if (settings.minFree == 0) settings.minFree = rec.minFreePercent;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getResources().getColor(R.color.panel));
        bar.setPadding(px(12), 0, px(16), 0);
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
        TextView title = text("Recording settings", 24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = px(16);
        bar.addView(title, tl);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(72)));

        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(px(24), px(8), px(24), px(16));
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DrivingGuard.check(this);
    }

    private void build() {
        rows.removeAllViews();
        row("Frame rate", "Fewer frames, smaller files and less work for the head unit.", FPS, settings.fps, new Pick() {
            public String label(int v) { return v + " fps"; }
            public void set(int v) { settings.fps = v; }
        });
        row("Quality", "Bits per second of video. 1.5 Mbit/s at 15 fps looks like 3 Mbit/s at 30.", BITRATES, settings.bitrate, new Pick() {
            public String label(int v) { return String.format(Locale.US, v % 1_000_000 == 0 ? "%.0f Mbit/s" : "%.1f Mbit/s", v / 1e6); }
            public void set(int v) { settings.bitrate = v; }
        });
        row("File length", "Each file on the drive covers this long.", SEGMENTS, settings.segment, new Pick() {
            public String label(int v) { return v / 60 + " min"; }
            public void set(int v) { settings.segment = v; }
        });
        row("Keep free", "The oldest recordings are deleted to keep this much of the drive free.", MIN_FREE, settings.minFree, new Pick() {
            public String label(int v) { return v + "%"; }
            public void set(int v) { settings.minFree = v; }
        });
        estimate = text("", 17);
        estimate.setTextColor(getResources().getColor(R.color.text_dim));
        estimate.setPadding(0, px(18), 0, 0);
        rows.addView(estimate);
        updateEstimate();
    }

    private interface Pick {
        String label(int value);

        void set(int value);
    }

    private void row(String name, String hint, int[] values, int current, final Pick pick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(12), 0, 0);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(name, 20));
        TextView h = text(hint, 14);
        h.setTextColor(getResources().getColor(R.color.text_dim));
        labels.addView(h);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        for (final int v : values) {
            Button b = new Button(this);
            b.setText(pick.label(v));
            b.setActivated(v == current);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pick.set(v);
                    save();
                    build();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(140), px(56));
            lp.leftMargin = px(8);
            row.addView(b, lp);
        }
        rows.addView(row);
    }

    private void save() {
        try {
            settings.write(this);
            Toast.makeText(this, "Saved. The recorder restarts with it in a few seconds.", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateEstimate() {
        double gbPerHour = settings.bitrate / 8.0 * 3600 * 1.03 / 1e9;
        String s = String.format(Locale.US, "About %.1f GB per hour of driving.", gbPerHour);
        try {
            StatFs fs = new StatFs(rec.mount);
            long total = (long) fs.getBlockCount() * fs.getBlockSize();
            if (total > 0) {
                double hours = total / 1e9 * (100 - settings.minFree) / 100 / gbPerHour;
                s += String.format(Locale.US, " This %s drive holds about %.0f hours before the oldest is deleted (less whatever is saved).",
                        Fmt.size(total), hours);
            }
        } catch (IllegalArgumentException e) {
            // no drive
        }
        estimate.setText(s);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int px(int dps) {
        return Math.round(dps * dp);
    }
}
