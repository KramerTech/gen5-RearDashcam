package dev.skramer.dashcam.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.extractor.DefaultExtractorsFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays a day's segments back to back as one stream (ExoPlayer playlist), shown against the
 * clock: an hour bar to scrub, hour and day navigation that skips gaps, frame stepping, speeds,
 * pinch zoom (for plates), snapshots, and saving the minutes around a moment.
 */
public final class PlayerActivity extends Activity implements Player.Listener, HourBar.Listener {
    static final String EXTRA_WALL = "wall";
    private static final String TAG = "DashcamPlayer";
    private static final float[] SPEEDS = {1, 2, 4, 8, 0.5f};
    /** One frame at 30 fps, the most the recorder records; back steps go back by this until they reach the previous frame. */
    private static final long FRAME_US = 33_367;
    private static final int MAX_STEP_BACK_TRIES = 8;
    private static final long TICK_MS = 100;
    private static final long SAVE_AROUND_MS = 60_000;

    private final Handler handler = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Timeline.Window window = new Timeline.Window();
    private ExoPlayer player;
    private ProgressiveMediaSource.Factory sources;
    private RecorderLink rec;
    private Library lib;
    /** The playlist: one day's clips, in order. Same objects as in {@link #lib}. */
    private List<Clip> playlist = Collections.emptyList();
    private long playlistDay = -1;
    /** Where to start, or resume after onStop: wall time, 0 for the latest footage. */
    private long resumeWall;
    private boolean resumePlaying = true;
    private int speedIndex;
    private boolean scrubbing;
    private long scrubWall, lastScrubSeek, lastEnd;
    private boolean clipsChanged;
    private long probedHour = -1;
    private int errors;
    /** Timestamp of the frame on screen (period time, µs), from the renderer. */
    private volatile long frameUs = -1;
    /** A backward frame step in progress: the frame it started from, and where it's looking now (µs). */
    private long stepBackFrom = -1, stepBackTarget;
    private int stepBackTries;

    private TextureView video;
    private TextView overlayTime, overlayBadge, message, dayTitle, hourLabel, timeBig, clipInfo;
    private HourBar bar;
    private ImageButton playPause;
    private Button speed, save;
    private final Matrix zoom = new Matrix();
    private float zoomScale = 1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, TICK_MS);
        }
    };
    /** Keeps the recorder paused while the player is open (see RecorderLink.renewViewingLease). */
    private final Runnable lease = new Runnable() {
        @Override
        public void run() {
            RecorderLink.renewViewingLease(PlayerActivity.this);
            handler.postDelayed(this, RecorderLink.LEASE_RENEW_MS);
        }
    };
    private final Runnable hideMessage = new Runnable() {
        @Override
        public void run() {
            message.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        DrivingGuard.onCreate(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        video = (TextureView) findViewById(R.id.video);
        overlayTime = (TextView) findViewById(R.id.overlayTime);
        overlayBadge = (TextView) findViewById(R.id.overlayBadge);
        message = (TextView) findViewById(R.id.message);
        dayTitle = (TextView) findViewById(R.id.dayTitle);
        hourLabel = (TextView) findViewById(R.id.hourLabel);
        timeBig = (TextView) findViewById(R.id.timeBig);
        clipInfo = (TextView) findViewById(R.id.clipInfo);
        bar = (HourBar) findViewById(R.id.hourBar);
        playPause = (ImageButton) findViewById(R.id.playPause);
        speed = (Button) findViewById(R.id.speed);
        save = (Button) findViewById(R.id.save);
        bar.setListener(this);
        resumeWall = state != null ? state.getLong(EXTRA_WALL) : getIntent().getLongExtra(EXTRA_WALL, 0);
        sources = new ProgressiveMediaSource.Factory(new FileDataSource.Factory(), new DefaultExtractorsFactory());

        click(R.id.back, new Runnable() { public void run() { finish(); } });
        click(R.id.playPause, new Runnable() { public void run() { togglePlay(); } });
        click(R.id.back60, new Runnable() { public void run() { skip(-60_000); } });
        click(R.id.back10, new Runnable() { public void run() { skip(-10_000); } });
        click(R.id.fwd10, new Runnable() { public void run() { skip(10_000); } });
        click(R.id.fwd60, new Runnable() { public void run() { skip(60_000); } });
        click(R.id.frameBack, new Runnable() { public void run() { stepFrame(-1); } });
        click(R.id.frameFwd, new Runnable() { public void run() { stepFrame(1); } });
        click(R.id.prevHour, new Runnable() { public void run() { prevHour(); } });
        click(R.id.nextHour, new Runnable() { public void run() { nextHour(); } });
        click(R.id.speed, new Runnable() { public void run() { cycleSpeed(); } });
        click(R.id.save, new Runnable() { public void run() { saveHere(); } });
        click(R.id.snapshot, new Runnable() { public void run() { snapshot(); } });
        click(R.id.latest, new Runnable() { public void run() { openAt(0, true); } });
        setUpZoom();
    }

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null && lib != null) r.run();
                else if (id == R.id.back) finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(lease);
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)).build();
        player.setVideoTextureView(video);
        player.addListener(this);
        player.setPlaybackSpeed(SPEEDS[speedIndex]);
        player.setVideoFrameMetadataListener(new VideoFrameMetadataListener() {
            @Override
            public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs, Format format, MediaFormat mediaFormat) {
                frameUs = presentationTimeUs;
                handler.post(frameShown);
            }
        });
        reload(new Runnable() {
            @Override
            public void run() {
                openAt(resumeWall, resumePlaying);
            }
        });
        handler.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DrivingGuard.check(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(lease);
        RecorderLink.endViewingLease(this);
        if (player != null) {
            if (!playlist.isEmpty()) {
                resumeWall = wallNow();
                resumePlaying = player.getPlayWhenReady();
            }
            player.release();
            player = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong(EXTRA_WALL, player != null && !playlist.isEmpty() ? wallNow() : resumeWall);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** Re-reads the drive off the main thread, then runs {@code then}. */
    private void reload(final Runnable then) {
        if (io.isShutdown()) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final RecorderLink r = RecorderLink.read();
                final Library l = Library.scan(r);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (player == null) return;
                        rec = r;
                        lib = l;
                        probedHour = -1;
                        then.run();
                    }
                });
            }
        });
    }

    // Positions ------------------------------------------------------------------------------

    private Clip current() {
        if (player == null || playlist.isEmpty()) return null;
        int i = player.getCurrentMediaItemIndex();
        return i < playlist.size() ? playlist.get(i) : null;
    }

    /** The wall-clock time of what's on screen: while paused, of the exact frame showing. */
    private long wallNow() {
        Clip c = current();
        if (c == null) return resumeWall;
        if (!player.getPlayWhenReady() && frameUs >= 0) return c.start + frameUs / 1000;
        return c.start + player.getCurrentPosition();
    }

    /** Loads the playlist of the day with footage at (or after) {@code wall}; 0 = the latest. */
    private void openAt(long wall, boolean play) {
        if (lib.isEmpty()) {
            showMessage("No recordings on the drive", 0);
            playlist = Collections.emptyList();
            player.clearMediaItems();
            return;
        }
        if (wall <= 0) {
            Clip last = lib.last();
            wall = Math.max(last.start, last.end() - 3000);
        }
        Clip target = lib.firstEndingAfter(wall);
        if (target == null) {
            target = lib.last();
            wall = Math.max(target.start, target.end() - 3000);
        }
        wall = Math.max(wall, target.start);
        playlistDay = Library.dayOf(target.start);
        playlist = lib.day(playlistDay);
        List<MediaSource> list = new ArrayList<>(playlist.size());
        for (Clip c : playlist) list.add(sources.createMediaSource(MediaItem.fromUri(Uri.fromFile(c.file))));
        player.setMediaSources(list, playlist.indexOf(target), wall - target.start);
        player.prepare();
        player.setPlayWhenReady(play);
        frameUs = -1;
        clipsChanged = true;
        updateUi();
    }

    /** Seeks to a wall-clock time, skipping forward over gaps (or back, if going back). */
    private void seekWall(long wall, boolean backward) {
        Clip c = lib.firstEndingAfter(wall);
        if (backward && (c == null || c.start > wall)) {
            Clip before = lib.lastBefore(wall);
            if (before != null) {
                c = before;
                wall = Math.max(before.start, before.end() - 1000);
            }
        }
        if (c == null) {
            openAt(0, player.getPlayWhenReady());
            return;
        }
        wall = Math.max(wall, c.start);
        int i = playlist.indexOf(c);
        if (i < 0) {
            openAt(wall, player.getPlayWhenReady());
            return;
        }
        frameUs = -1;
        player.seekTo(i, wall - c.start);
    }

    private void skip(long ms) {
        player.setSeekParameters(SeekParameters.EXACT);
        seekWall(wallNow() + ms, ms < 0);
    }

    /** To the previous hour with footage. */
    private void prevHour() {
        long hour = Library.hourOf(wallNow());
        Clip c = lib.lastBefore(hour);
        if (c == null) {
            showMessage("No earlier footage", 1500);
            return;
        }
        seekHour(Library.hourOf(Math.min(c.end() - 1, hour - 1)));
    }

    /** To the next hour with footage. */
    private void nextHour() {
        long next = Library.hourOf(wallNow()) + Library.HOUR;
        Clip c = lib.firstEndingAfter(next);
        if (c == null) {
            showMessage("No later footage", 1500);
            return;
        }
        seekHour(Library.hourOf(Math.max(c.start, next)));
    }

    private void seekHour(long hour) {
        Clip c = lib.firstEndingAfter(hour);
        if (c == null) return;
        player.setSeekParameters(SeekParameters.EXACT);
        seekWall(Math.max(hour, c.start), false);
    }

    @Override
    public void onScrub(long wall, boolean done) {
        if (player == null || lib == null) return;
        scrubbing = !done;
        scrubWall = wall;
        long now = SystemClock.uptimeMillis();
        if (done) {
            player.setSeekParameters(SeekParameters.EXACT);
            seekWall(wall, false);
        } else if (now - lastScrubSeek > 150) {
            // Keyframes only while dragging: one per second, and each shows at once.
            lastScrubSeek = now;
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
            seekWall(wall, false);
        }
        updateUi();
    }

    // Playback controls ----------------------------------------------------------------------

    private void togglePlay() {
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            openAt(0, true);
            return;
        }
        player.setPlayWhenReady(!player.getPlayWhenReady());
        updateUi();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        player.setPlaybackSpeed(SPEEDS[speedIndex]);
        updateUi();
    }

    /**
     * One frame forward or back, paused. A paused seek shows the first frame at or after the
     * target, so forward aims just past the frame on screen. Back aims one 30 fps frame earlier;
     * if that shows the same frame (lower frame rates, dropped frames), frameShown backs up again.
     */
    private void stepFrame(int dir) {
        player.setPlayWhenReady(false);
        player.setSeekParameters(SeekParameters.EXACT);
        Clip c = current();
        if (c == null) return;
        int i = player.getCurrentMediaItemIndex();
        long cur = frameUs >= 0 ? frameUs : player.getCurrentPosition() * 1000;
        if (dir > 0) {
            long target = cur / 1000 + 1;
            long duration = player.getDuration();
            if (duration != C.TIME_UNSET && target >= duration - 1 && i + 1 < playlist.size()) player.seekTo(i + 1, 0);
            else player.seekTo(i, target);
        } else {
            long target = cur - FRAME_US;
            if (target < 0) {
                if (i > 0) player.seekTo(i - 1, Math.max(0, playlist.get(i - 1).durationMs - 40));
                return;
            }
            stepBackFrom = cur;
            stepBackTarget = target;
            stepBackTries = 0;
            player.seekTo(i, target / 1000);
        }
    }

    /** A frame was rendered: if a back step landed on the frame it started from, back up further. */
    private final Runnable frameShown = new Runnable() {
        @Override
        public void run() {
            errors = 0;
            if (stepBackFrom < 0 || player == null) return;
            stepBackTarget -= FRAME_US;
            if (frameUs >= stepBackFrom && stepBackTarget >= 0 && ++stepBackTries < MAX_STEP_BACK_TRIES) {
                player.seekTo(player.getCurrentMediaItemIndex(), stepBackTarget / 1000);
            } else {
                stepBackFrom = -1;
            }
        }
    };

    // Save and snapshot ----------------------------------------------------------------------

    /**
     * Saves the minute before and after this moment: those clips move to the Saved folder. A save
     * that touches an earlier one joins it (Library.savedEvents), so saved footage only ever
     * comes in whole events, and those are unsaved or deleted whole, from the Saved screen.
     */
    private void saveHere() {
        final long wall = wallNow();
        Clip c = current();
        if (c != null && c.saved) {
            showMessage("Already saved\nUnsave or delete it from Saved on the home screen", 3000);
            return;
        }
        final List<Clip> clips = lib.between(wall - SAVE_AROUND_MS, wall + SAVE_AROUND_MS);
        final boolean playing = player.getPlayWhenReady();
        save(clips, new Runnable() {
            @Override
            public void run() {
                List<Clip> event = clips;
                for (List<Clip> e : lib.savedEvents()) {
                    for (Clip s : e) if (s.file.getName().equals(clips.get(0).file.getName())) event = e;
                }
                Clip first = event.get(0), last = event.get(event.size() - 1);
                showMessage("Saved " + Fmt.hm(first.start) + " – " + Fmt.hm(last.end())
                        + "\nSaved clips are never deleted to make room", 3000);
                openAt(wall, playing);
            }
        });
    }

    /** Moves clips into Saved, rescans, then runs {@code then}. */
    private void save(final List<Clip> clips, final Runnable then) {
        if (clips.isEmpty()) return;
        final Library l = lib;
        io.execute(new Runnable() {
            @Override
            public void run() {
                int failed = 0;
                for (Clip c : clips) if (!l.setSaved(c, true)) failed++;
                final int f = failed;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (f > 0) Toast.makeText(PlayerActivity.this, "Couldn't move " + f + " files", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        reload(then);
    }

    /** Saves what's on screen (zoom included) as a JPEG in the Snapshots folder. */
    private void snapshot() {
        final Bitmap frame = video.getBitmap(960, 720);
        if (frame == null) return;
        final File dir = new File(lib.dir, Library.SNAPSHOTS);
        final File file = new File(dir, Fmt.file(wallNow()) + ".jpg");
        io.execute(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        frame.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    }
                    result = "Snapshot saved: " + dir.getName() + "/" + file.getName();
                } catch (IOException e) {
                    Log.w(TAG, "snapshot", e);
                    result = "Couldn't save the snapshot";
                }
                frame.recycle();
                final String r = result;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        showMessage(r, 2500);
                    }
                });
            }
        });
    }

    // Zoom -----------------------------------------------------------------------------------

    /** Pinch to zoom up to 6x, drag to pan, double tap to reset; a single tap plays or pauses. */
    private void setUpZoom() {
        final ScaleGestureDetector scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float s = Math.max(1, Math.min(6, zoomScale * d.getScaleFactor()));
                zoom.postScale(s / zoomScale, s / zoomScale, d.getFocusX(), d.getFocusY());
                zoomScale = s;
                applyZoom();
                return true;
            }
        });
        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoomScale <= 1) return false;
                zoom.postTranslate(-dx, -dy);
                applyZoom();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (player != null && lib != null) togglePlay();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoom.reset();
                zoomScale = 1;
                applyZoom();
                return true;
            }
        });
        video.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                scale.onTouchEvent(e);
                if (!scale.isInProgress()) gestures.onTouchEvent(e);
                return true;
            }
        });
    }

    /** Keeps the zoomed picture covering the view. */
    private void applyZoom() {
        float[] v = new float[9];
        zoom.getValues(v);
        float w = video.getWidth(), h = video.getHeight();
        float minX = w - w * zoomScale, minY = h - h * zoomScale;
        v[Matrix.MTRANS_X] = Math.max(minX, Math.min(0, v[Matrix.MTRANS_X]));
        v[Matrix.MTRANS_Y] = Math.max(minY, Math.min(0, v[Matrix.MTRANS_Y]));
        v[Matrix.MSCALE_X] = v[Matrix.MSCALE_Y] = zoomScale;
        zoom.setValues(v);
        video.setTransform(zoom);
        video.invalidate();
        updateUi();
    }

    // Player events --------------------------------------------------------------------------

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_ENDED) atEnd();
        updateUi();
    }

    @Override
    public void onIsPlayingChanged(boolean playing) {
        updateUi();
    }

    /** End of the day's playlist: continue with the next day, or newer footage of a live clip. */
    private void atEnd() {
        final long wall = wallNow();
        long now = SystemClock.uptimeMillis();
        long wait = now - lastEnd < 2000 ? 1000 : 0;  // at the live edge: don't spin
        lastEnd = now;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                reload(new Runnable() {
                    @Override
                    public void run() {
                        Clip next = lib.firstEndingAfter(wall + 500);
                        if (next != null) {
                            openAt(Math.max(next.start, wall), true);
                        } else {
                            player.setPlayWhenReady(false);
                            showMessage("That's the latest footage", 2500);
                        }
                    }
                });
            }
        }, wait);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Clip c = current();
        Log.w(TAG, "can't play " + (c == null ? "?" : c.file), error);
        int i = player.getCurrentMediaItemIndex();
        showMessage("Can't play " + (c == null ? "this clip" : c.file.getName()), 2500);
        if (++errors <= 5 && i + 1 < playlist.size()) {
            player.seekTo(i + 1, 0);
            player.prepare();
        }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        // Prepared clips know their exact length.
        int n = Math.min(timeline.getWindowCount(), playlist.size());
        for (int i = 0; i < n; i++) {
            timeline.getWindow(i, window);
            if (window.isPlaceholder || window.durationUs == C.TIME_UNSET) continue;
            Clip c = playlist.get(i);
            long d = window.durationUs / 1000 + FRAME_US / 1000;
            if (!c.exact || Math.abs(c.durationMs - d) > 100) {
                c.durationMs = d;
                c.exact = true;
                clipsChanged = true;
            }
        }
    }

    /** Probes the exact lengths of the clips around the shown hour, so the bar shows gaps right. */
    private void probeAround(long hour) {
        if (hour == probedHour || io.isShutdown()) return;
        probedHour = hour;
        final List<Clip> clips = lib.between(hour - Library.HOUR, hour + 2 * Library.HOUR);
        io.execute(new Runnable() {
            @Override
            public void run() {
                for (final Clip c : clips) {
                    if (c.exact) continue;
                    final long d = TsProbe.durationMs(c.file);
                    if (d < 0) continue;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            c.durationMs = d;
                            c.exact = true;
                            clipsChanged = true;
                        }
                    });
                }
            }
        });
    }

    // UI -------------------------------------------------------------------------------------

    private void showMessage(String text, long ms) {
        handler.removeCallbacks(hideMessage);
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        if (ms > 0) handler.postDelayed(hideMessage, ms);
    }

    private void updateUi() {
        if (player == null || lib == null || playlist.isEmpty()) return;
        long w = scrubbing ? scrubWall : wallNow();
        boolean paused = !player.getPlayWhenReady() && !scrubbing;
        String t = paused ? Fmt.timeMs(w) : Fmt.time(w);
        overlayTime.setText(t);
        timeBig.setText(t);
        dayTitle.setText(Fmt.longDay(Library.dayOf(w)));
        long hour = Library.hourOf(w);
        if (hour != bar.hour() || clipsChanged) {
            clipsChanged = false;
            bar.set(hour, lib.between(hour, hour + Library.HOUR));
            hourLabel.setText(Fmt.hm(hour) + " – " + Fmt.hm(hour + Library.HOUR));
            probeAround(hour);
        }
        bar.setPlayhead(w);
        Clip c = current();
        if (c != null) clipInfo.setText(c.file.getName() + (c.saved ? "  ·  saved" : ""));
        save.setText(c != null && c.saved ? "Saved ★" : "Save");
        boolean playing = player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        float s = SPEEDS[speedIndex];
        String sp = (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "×";
        speed.setText("Speed " + sp);
        String badge = s == 1 ? "" : sp;
        if (zoomScale > 1.01f) badge += (badge.isEmpty() ? "" : "  ") + String.format(java.util.Locale.US, "Zoom %.1f×", zoomScale);
        overlayBadge.setText(badge);
        overlayBadge.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
