package dev.skramer.dashcam.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saved clips, grouped into events: play, unsave (back into the loop) or delete. */
public final class SavedActivity extends Activity {
    private final Handler handler = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final EventsAdapter events = new EventsAdapter();
    private Library lib;
    private TextView summary, empty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_saved);
        DrivingGuard.onCreate(this);
        summary = (TextView) findViewById(R.id.summary);
        empty = (TextView) findViewById(R.id.empty);
        ((ListView) findViewById(R.id.list)).setAdapter(events);
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (DrivingGuard.check(this)) return;
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void reload() {
        if (io.isShutdown()) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Library l = Library.scan(RecorderLink.read());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        lib = l;
                        List<List<Clip>> list = l.savedEvents();
                        events.set(list);
                        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                        summary.setText(list.isEmpty() ? "" : list.size() + (list.size() == 1 ? " event, " : " events, ")
                                + Fmt.size(l.savedBytes) + " · kept until you delete or unsave them");
                    }
                });
            }
        });
    }

    /** Runs a file job on the clips, then reloads. */
    private void apply(final List<Clip> clips, final boolean delete) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                int failed = 0;
                for (Clip c : clips) {
                    boolean ok = delete ? c.file.delete() : lib.setSaved(c, false);
                    if (!ok) failed++;
                }
                final int f = failed;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (f > 0) Toast.makeText(SavedActivity.this, "Couldn't change " + f + " files", Toast.LENGTH_LONG).show();
                        reload();
                    }
                });
            }
        });
    }

    private static String title(List<Clip> e) {
        Clip first = e.get(0), last = e.get(e.size() - 1);
        return Fmt.day(Library.dayOf(first.start)) + "  " + Fmt.hm(first.start) + " – " + Fmt.hm(last.end());
    }

    private final class EventsAdapter extends BaseAdapter {
        private final List<List<Clip>> list = new ArrayList<>();

        void set(List<List<Clip>> l) {
            list.clear();
            list.addAll(l);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return list.size();
        }

        @Override
        public Object getItem(int i) {
            return list.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_saved, parent, false);
            final List<Clip> e = list.get(i);
            long ms = 0, bytes = 0;
            for (Clip c : e) {
                ms += c.durationMs;
                bytes += c.bytes;
            }
            ((TextView) v.findViewById(R.id.title)).setText(title(e));
            ((TextView) v.findViewById(R.id.subtitle)).setText(Fmt.duration(ms) + " · " + e.size()
                    + (e.size() == 1 ? " file · " : " files · ") + Fmt.size(bytes));
            v.findViewById(R.id.play).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    startActivity(new Intent(SavedActivity.this, PlayerActivity.class)
                            .putExtra(PlayerActivity.EXTRA_WALL, e.get(0).start));
                }
            });
            v.findViewById(R.id.unsave).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    confirm("Unsave " + title(e) + "?",
                            "It goes back into the loop, and gets deleted when the drive needs room.", "Unsave", e, false);
                }
            });
            v.findViewById(R.id.delete).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    confirm("Delete " + title(e) + "?", "This can't be undone.", "Delete", e, true);
                }
            });
            return v;
        }
    }

    private void confirm(String title, String message, String action, final List<Clip> clips, final boolean delete) {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        apply(clips, delete);
                    }
                })
                .show();
    }
}
