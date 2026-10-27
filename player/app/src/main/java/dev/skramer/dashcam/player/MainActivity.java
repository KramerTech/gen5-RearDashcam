package dev.skramer.dashcam.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.StatFs;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recorder status and switch, drive space, and the footage by day and hour. */
public final class MainActivity extends Activity {
    private static final long STATUS_MS = 2000;
    private static final long RESCAN_MS = 30_000;

    private final Handler handler = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ImageView statusDot;
    private TextView statusText, driveText, dayTitle, empty;
    private Button recordButton, savedButton;
    private ListView daysView;
    private DayView dayView;
    private View body;
    private final DaysAdapter days = new DaysAdapter();

    private RecorderLink rec = new RecorderLink();
    private Library lib;
    private long selectedDay = -1;
    private long lastScan;

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refresh(System.currentTimeMillis() - lastScan > RESCAN_MS);
            handler.postDelayed(this, STATUS_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        DrivingGuard.onCreate(this);
        statusDot = (ImageView) findViewById(R.id.statusDot);
        statusText = (TextView) findViewById(R.id.statusText);
        driveText = (TextView) findViewById(R.id.driveText);
        dayTitle = (TextView) findViewById(R.id.dayTitle);
        empty = (TextView) findViewById(R.id.empty);
        recordButton = (Button) findViewById(R.id.recordButton);
        savedButton = (Button) findViewById(R.id.savedButton);
        daysView = (ListView) findViewById(R.id.days);
        dayView = (DayView) findViewById(R.id.dayView);
        body = findViewById(R.id.body);

        daysView.setAdapter(days);
        daysView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                selectDay(days.get(position));
            }
        });
        daysView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDeleteDay(days.get(position));
                return true;
            }
        });
        dayView.setListener(new DayView.Listener() {
            @Override
            public void onHour(long hourStart) {
                Clip c = lib == null ? null : lib.firstEndingAfter(hourStart);
                play(c == null ? hourStart : Math.max(hourStart, c.start));
            }
        });
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !rec.enabled();
                RecorderLink.Settings s = RecorderLink.Settings.read(MainActivity.this);
                s.enabled = on;
                try {
                    s.write(MainActivity.this);
                } catch (java.io.IOException e) {
                    Toast.makeText(MainActivity.this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                recordButton.setEnabled(false);
                statusText.setText(on ? "Turning recording on…" : "Turning recording off…");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh(false);
                    }
                }, 1500);
            }
        });
        savedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SavedActivity.class));
            }
        });
        findViewById(R.id.settingsButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.latestButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play(0);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (DrivingGuard.check(this)) return;
        refresh(true);
        handler.postDelayed(statusTick, STATUS_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void play(long wall) {
        startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_WALL, wall));
    }

    /** Re-reads the recorder status, and with {@code scan} the drive too, off the main thread. */
    private void refresh(final boolean scan) {
        if (io.isShutdown()) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final RecorderLink r = RecorderLink.read();
                final Library l = scan ? Library.scan(r) : null;
                final long[] space = space(r);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        rec = r;
                        if (l != null) {
                            lib = l;
                            lastScan = System.currentTimeMillis();
                        }
                        bindStatus(space);
                        if (l != null) bindLibrary();
                    }
                });
            }
        });
    }

    /** {total, free} bytes of the recording drive, or null if it isn't mounted. */
    private static long[] space(RecorderLink r) {
        try {
            StatFs fs = new StatFs(r.mount);
            long total = (long) fs.getBlockCount() * fs.getBlockSize();
            if (total == 0) return null;
            return new long[] {total, (long) fs.getAvailableBlocks() * fs.getBlockSize()};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void bindStatus(long[] space) {
        int color;
        String text;
        if (rec.state == null && !rec.running) {
            color = 0xFF5B6470;
            text = "The recorder isn't installed";
        } else if (!rec.running) {
            color = 0xFFF59E0B;
            text = "The recorder isn't running";
        } else {
            switch (rec.state == null ? "" : rec.state) {
                case "recording":
                    color = 0xFFEF4444;
                    text = "Recording since " + Fmt.hm(rec.since);
                    break;
                case "paused":
                    color = 0xFF5B6470;
                    text = "Paused while you watch";
                    break;
                case "reverse":
                    color = 0xFFF59E0B;
                    text = "Paused while the reverse camera is on";
                    break;
                case "off":
                    color = 0xFF5B6470;
                    text = "Recording is off";
                    break;
                case "nodrive":
                    color = 0xFFF59E0B;
                    text = "Waiting for a USB drive";
                    break;
                default:
                    color = 0xFFF59E0B;
                    text = "Starting the camera…";
                    break;
            }
        }
        statusDot.setColorFilter(color);
        statusText.setText(text);
        recordButton.setText(rec.enabled() ? "Stop recording" : "Start recording");
        recordButton.setEnabled(rec.running);

        if (space == null) {
            driveText.setText("No drive at " + rec.mount);
        } else {
            String s = Fmt.size(space[1]) + " free of " + Fmt.size(space[0]);
            if (lib != null) {
                long recorded = lib.recordedMs(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
                double bytesPerHour = recorded > 30 * 60_000L
                        ? lib.footageBytes * 3_600_000.0 / recorded
                        : rec.bitrate / 8.0 * 3600 * 1.02;
                long loop = space[0] / 100 * (100 - rec.minFreePercent) - lib.savedBytes;
                if (loop > 0) s += " · keeps about " + Math.round(loop / bytesPerHour) + " h";
            }
            driveText.setText(s);
        }
    }

    private void bindLibrary() {
        List<Long> list = lib.days();
        days.set(list);
        int events = lib.savedEvents().size();
        savedButton.setText(events == 0 ? "Saved" : "Saved (" + events + ")");
        if (list.isEmpty()) {
            body.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            empty.setText(new java.io.File(rec.mount).isDirectory() && space(rec) != null
                    ? "No recordings yet in " + rec.out + "."
                    : "Plug in the USB drive the dashcam records to.");
            return;
        }
        body.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        if (!list.contains(selectedDay)) selectedDay = list.get(0);
        selectDay(selectedDay);
    }

    private void selectDay(long day) {
        selectedDay = day;
        int pos = days.days.indexOf(day);
        daysView.setItemChecked(pos, true);
        dayView.set(day, lib);
        dayTitle.setText(Fmt.longDay(day) + "  ·  " + Fmt.duration(lib.recordedMs(day, Library.nextDay(day))) + " recorded");
    }

    private void confirmDeleteDay(final long day) {
        final List<Clip> doomed = new ArrayList<>();
        Clip live = "recording".equals(rec.state) ? lib.last() : null;
        for (Clip c : lib.day(day)) if (!c.saved && c != live) doomed.add(c);
        if (doomed.isEmpty()) {
            Toast.makeText(this, "Nothing to delete: all of it is saved.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Delete " + Fmt.day(day) + "?")
                .setMessage("Deletes " + Fmt.duration(total(doomed)) + " of footage from "
                        + Fmt.longDay(day) + ". Saved clips are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                int failed = 0;
                                for (Clip c : doomed) if (!c.file.delete()) failed++;
                                final int f = failed;
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (f > 0) Toast.makeText(MainActivity.this, "Couldn't delete " + f + " files", Toast.LENGTH_LONG).show();
                                        refresh(true);
                                    }
                                });
                            }
                        });
                    }
                })
                .show();
    }

    private static long total(List<Clip> clips) {
        long ms = 0;
        for (Clip c : clips) ms += c.durationMs;
        return ms;
    }

    private final class DaysAdapter extends BaseAdapter {
        final List<Long> days = new ArrayList<>();

        void set(List<Long> list) {
            days.clear();
            days.addAll(list);
            notifyDataSetChanged();
        }

        long get(int i) {
            return days.get(i);
        }

        @Override
        public int getCount() {
            return days.size();
        }

        @Override
        public Object getItem(int i) {
            return days.get(i);
        }

        @Override
        public long getItemId(int i) {
            return days.get(i);
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_day, parent, false);
            long day = days.get(i);
            ((TextView) v.findViewById(R.id.title)).setText(Fmt.day(day));
            ((TextView) v.findViewById(R.id.subtitle)).setText(Fmt.duration(lib.recordedMs(day, Library.nextDay(day))));
            return v;
        }
    }
}
