package dev.skramer.dashcam.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saved clips, grouped into events: tap one to play it; rename, unsave (back into the loop) or delete. */
public final class SavedActivity extends Activity {
    private final Handler handler = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final EventsAdapter events = new EventsAdapter();
    private Library lib;
    private Map<String, String> names = new HashMap<>();
    private TextView summary, empty;
    private RenameSheet renameSheet;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_saved);
        DrivingGuard.onCreate(this);
        summary = (TextView) findViewById(R.id.summary);
        empty = (TextView) findViewById(R.id.empty);
        ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(events);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int i, long id) {
                List<Clip> e = events.list.get(i);
                startActivity(new Intent(SavedActivity.this, PlayerActivity.class)
                        .putExtra(PlayerActivity.EXTRA_WALL, e.get(0).start));
            }
        });
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
    public void onBackPressed() {
        if (renameSheet != null && renameSheet.isShowing()) renameSheet.dismiss();
        else super.onBackPressed();
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
                final Map<String, String> n = l.names();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        lib = l;
                        names = n;
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
                lib.setName(clips, null);
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

    /** The event's name, or when it was. */
    private String title(List<Clip> e) {
        String name = Library.nameOf(e, names);
        return name != null ? name : when(e);
    }

    private static String when(List<Clip> e) {
        Clip first = e.get(0), last = e.get(e.size() - 1);
        return Fmt.day(Library.dayOf(first.start)) + "  " + Fmt.hm(first.start) + " – " + Fmt.hm(last.end());
    }

    private void rename(final List<Clip> e) {
        renameSheet = RenameSheet.show(this, "Name this recording", Library.nameOf(e, names), when(e),
                "Recorded " + when(e) + ".\nLeave it empty to show just the date and time.", new RenameSheet.Listener() {
                    @Override
                    public void onSave(final String n) {
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                final boolean ok = lib.setName(e, n);
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!ok) Toast.makeText(SavedActivity.this, "Couldn't save the name", Toast.LENGTH_LONG).show();
                                        reload();
                                    }
                                });
                            }
                        });
                    }
                });
    }

    private final class EventsAdapter extends BaseAdapter {
        final List<List<Clip>> list = new ArrayList<>();

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
            String detail = Fmt.duration(ms) + " · " + e.size() + (e.size() == 1 ? " file · " : " files · ") + Fmt.size(bytes);
            if (Library.nameOf(e, names) != null) detail = when(e) + " · " + detail;
            ((TextView) v.findViewById(R.id.subtitle)).setText(detail);
            v.findViewById(R.id.rename).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    rename(e);
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
