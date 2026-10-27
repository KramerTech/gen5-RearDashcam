package dev.skramer.dashcam.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One hour of footage as a bar: recorded spans, saved spans, the playhead. Drag to scrub. */
public final class HourBar extends View {
    interface Listener {
        /** The user is scrubbing to {@code wall}; {@code done} when they let go. */
        void onScrub(long wall, boolean done);
    }

    private final float dp = getResources().getDisplayMetrics().density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private long hour = -1, playhead = -1;
    private List<Clip> clips = Collections.emptyList();
    private Listener listener;

    public HourBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setListener(Listener l) {
        listener = l;
    }

    long hour() {
        return hour;
    }

    void set(long hour, List<Clip> clips) {
        this.hour = hour;
        this.clips = clips;
        invalidate();
    }

    void setPlayhead(long wall) {
        if (wall == playhead) return;
        playhead = wall;
        invalidate();
    }

    private void layoutTrack() {
        float side = 14 * dp;
        track.set(side, 16 * dp, getWidth() - side, 16 * dp + 40 * dp);
    }

    private float x(long wall) {
        return track.left + track.width() * (wall - hour) / (float) Library.HOUR;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (hour < 0) return;
        layoutTrack();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A303A);
        canvas.drawRoundRect(track, 6 * dp, 6 * dp, paint);
        for (Clip c : clips) {
            float l = Math.max(track.left, x(c.start)), r = Math.min(track.right, x(c.end()));
            if (r <= l) continue;
            paint.setColor(c.saved ? 0xFFF5A524 : 0xFF3B82F6);
            canvas.drawRect(l, track.top, Math.max(r, l + 1), track.bottom, paint);
        }
        // Ticks every 5 minutes, labels every 10
        paint.setTextSize(14 * dp);
        for (int m = 0; m <= 60; m += 5) {
            float tx = track.left + track.width() * m / 60f;
            paint.setColor(m % 10 == 0 ? 0xFF9AA3AF : 0xFF5B6470);
            canvas.drawRect(tx - dp / 2, track.bottom + 2 * dp, tx + dp / 2, track.bottom + (m % 10 == 0 ? 10 : 6) * dp, paint);
            if (m % 10 != 0) continue;
            String label = m == 0 || m == 60 ? Fmt.hm(hour + m * 60_000L) : String.format(Locale.US, ":%02d", m);
            float w = paint.measureText(label);
            float lx = Math.max(0, Math.min(getWidth() - w, tx - w / 2));
            canvas.drawText(label, lx, track.bottom + 26 * dp, paint);
        }
        if (playhead >= hour && playhead <= hour + Library.HOUR) {
            float px = x(playhead);
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(px - 1.5f * dp, track.top - 6 * dp, px + 1.5f * dp, track.bottom + 2 * dp, paint);
            canvas.drawCircle(px, track.top - 6 * dp, 7 * dp, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (hour < 0 || listener == null) return false;
        layoutTrack();
        float f = Math.max(0, Math.min(1, (e.getX() - track.left) / track.width()));
        long wall = hour + (long) (f * (Library.HOUR - 1));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                playhead = wall;
                invalidate();
                listener.onScrub(wall, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                playhead = wall;
                invalidate();
                listener.onScrub(wall, true);
                return true;
            default:
                return true;
        }
    }
}
