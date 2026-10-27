package dev.skramer.dashcam.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** A day as 24 hour tiles, each with a minute-by-minute bar of what was recorded. Tap to play. */
public final class DayView extends View {
    interface Listener {
        void onHour(long hourStart);
    }

    private static final int COLS = 6, ROWS = 4;
    private final float dp = getResources().getDisplayMetrics().density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int[] minutes = new int[24];
    /** Per minute of the day: 0 none, 1 recorded, 2 saved. */
    private final byte[] cover = new byte[1440];
    private long day = -1;
    private int pressed = -1;
    private Listener listener;

    public DayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void set(long day, Library lib) {
        this.day = day;
        java.util.Arrays.fill(cover, (byte) 0);
        java.util.Arrays.fill(minutes, 0);
        long end = Library.nextDay(day);
        for (Clip c : lib.between(day, end)) {
            int from = (int) Math.max(0, (c.start - day) / 60_000);
            int to = (int) Math.min(1440, (Math.min(c.end(), end) - day + 59_999) / 60_000);
            for (int m = from; m < to; m++) cover[m] = (byte) Math.max(cover[m], c.saved ? 2 : 1);
        }
        for (int m = 0; m < 1440; m++) if (cover[m] != 0) minutes[m / 60]++;
        invalidate();
    }

    private void tile(int h, RectF out) {
        float gap = 8 * dp;
        float w = (getWidth() - gap * (COLS - 1)) / COLS, ht = (getHeight() - gap * (ROWS - 1)) / ROWS;
        int col = h % COLS, row = h / COLS;
        out.set(col * (w + gap), row * (ht + gap), col * (w + gap) + w, row * (ht + gap) + ht);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (day < 0) return;
        long now = System.currentTimeMillis();
        int nowHour = Library.dayOf(now) == day ? (int) ((Library.hourOf(now) - day) / Library.HOUR) : -1;
        for (int h = 0; h < 24; h++) {
            tile(h, r);
            boolean any = minutes[h] > 0;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(h == pressed ? 0xFF3A4352 : any ? 0xFF1F242D : 0xFF14171D);
            canvas.drawRoundRect(r, 10 * dp, 10 * dp, paint);
            if (h == nowHour) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * dp);
                paint.setColor(0xFF3B82F6);
                canvas.drawRoundRect(r, 10 * dp, 10 * dp, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            float pad = 12 * dp;
            paint.setTextSize(20 * dp);
            paint.setFakeBoldText(true);
            paint.setColor(any ? 0xFFF2F4F7 : 0xFF5B6470);
            canvas.drawText(String.format(java.util.Locale.US, "%02d:00", h), r.left + pad, r.top + pad + 18 * dp, paint);
            paint.setFakeBoldText(false);
            if (any) {
                paint.setTextSize(15 * dp);
                paint.setColor(0xFF9AA3AF);
                String t = minutes[h] + " min";
                canvas.drawText(t, r.right - pad - paint.measureText(t), r.top + pad + 16 * dp, paint);
            }
            // Minute bar
            float barTop = r.bottom - pad - 12 * dp, barBottom = r.bottom - pad;
            float bw = (r.width() - 2 * pad) / 60;
            paint.setColor(0xFF2A303A);
            canvas.drawRect(r.left + pad, barTop, r.right - pad, barBottom, paint);
            for (int m = 0; m < 60; m++) {
                byte c = cover[h * 60 + m];
                if (c == 0) continue;
                paint.setColor(c == 2 ? 0xFFF5A524 : 0xFF3B82F6);
                canvas.drawRect(r.left + pad + m * bw, barTop, r.left + pad + (m + 1) * bw + 0.5f, barBottom, paint);
            }
        }
    }

    private int hit(float x, float y) {
        for (int h = 0; h < 24; h++) {
            tile(h, r);
            if (r.contains(x, y)) return minutes[h] > 0 ? h : -1;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed >= 0 && hit(e.getX(), e.getY()) != pressed) {
                    pressed = -1;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int h = pressed;
                pressed = -1;
                invalidate();
                if (h >= 0 && listener != null) listener.onHour(day + h * Library.HOUR);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
