package dev.skramer.dashcam.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * Compact in-app keyboard (from the Tilepad launcher), so typing doesn't depend on the head
 * unit's system keyboard, which doesn't match the app.
 */
final class KeyPad extends LinearLayout {
    interface Listener {
        void onText(String text);

        void onBackspace();

        void onClear();

        void onDone();
    }

    static final int KEY = 0xFF262C36, KEY_PRESSED = 0xFF3A4352, ACCENT = 0xFF3B82F6, ACCENT_PRESSED = 0xFF2F6FDB;
    static final int TEXT = 0xFFF2F4F7;

    private static final String[][] LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"z", "x", "c", "v", "b", "n", "m"},
    };
    private static final String[][] SYMBOLS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", "(", ")", "&", "@", "'", "+"},
            {".", ",", "?", "!", "#", "_", "*"},
    };

    private final Listener listener;
    private final String doneLabel;
    private final float density;
    private boolean symbols, shift;

    KeyPad(Context context, String doneLabel, Listener listener) {
        super(context);
        this.listener = listener;
        this.doneLabel = doneLabel;
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        build();
    }

    /** Capitalize the next letter (e.g. at the start of a name). */
    void setShift(boolean value) {
        if (shift == value) return;
        shift = value;
        build();
    }

    private void build() {
        removeAllViews();
        String[][] rows = symbols ? SYMBOLS : LETTERS;
        addRow(rows[0], null, null, 0f);
        addRow(rows[1], null, null, 0.5f);
        View shiftKey = symbols ? null : glyphKey(true, shift ? ACCENT : KEY, new Runnable() {
            @Override
            public void run() {
                setShift(!shift);
            }
        }, "Shift");
        View back = glyphKey(false, KEY, new Runnable() {
            @Override
            public void run() {
                listener.onBackspace();
            }
        }, "Backspace");
        back.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                listener.onClear();
                return true;
            }
        });
        addRow(rows[2], shiftKey, back, 0f);

        LinearLayout bottom = row();
        bottom.addView(textKey(symbols ? "ABC" : "123", KEY, 16, new Runnable() {
            @Override
            public void run() {
                symbols = !symbols;
                build();
            }
        }), weight(1.5f));
        bottom.addView(textKey("space", KEY, 16, new Runnable() {
            @Override
            public void run() {
                type(" ");
            }
        }), weight(6f));
        bottom.addView(textKey(doneLabel, ACCENT, 17, new Runnable() {
            @Override
            public void run() {
                listener.onDone();
            }
        }), weight(2.5f));
        addView(bottom, rowParams());
    }

    private void addRow(String[] keys, View lead, View trail, float sidePad) {
        LinearLayout row = row();
        if (sidePad > 0) row.addView(new View(getContext()), weight(sidePad));
        if (lead != null) row.addView(lead, weight(1.5f));
        else if (trail != null) row.addView(new View(getContext()), weight(1.5f));
        for (String key : keys) {
            final String shown = shift && !symbols ? key.toUpperCase(Locale.US) : key;
            row.addView(textKey(shown, KEY, 22, new Runnable() {
                @Override
                public void run() {
                    type(shown);
                }
            }), weight(1f));
        }
        if (trail != null) row.addView(trail, weight(1.5f));
        if (sidePad > 0) row.addView(new View(getContext()), weight(sidePad));
        addView(row, rowParams());
    }

    private void type(String text) {
        listener.onText(text);
        if (shift) setShift(false);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private LayoutParams rowParams() {
        LayoutParams p = new LayoutParams(-1, 0, 1f);
        p.topMargin = p.bottomMargin = dp(3);
        return p;
    }

    private LayoutParams weight(float w) {
        LayoutParams p = new LayoutParams(0, -1, w);
        p.leftMargin = p.rightMargin = dp(3);
        return p;
    }

    private TextView textKey(String label, int fill, float sp, Runnable action) {
        TextView key = new TextView(getContext());
        key.setText(label);
        key.setTextSize(sp);
        key.setTextColor(TEXT);
        key.setIncludeFontPadding(false);
        key.setGravity(Gravity.CENTER);
        background(key, pressable(fill, fill == ACCENT ? ACCENT_PRESSED : KEY_PRESSED, 10));
        click(key, action);
        return key;
    }

    private View glyphKey(boolean isShift, int fill, Runnable action, String description) {
        FrameLayout key = new FrameLayout(getContext());
        background(key, pressable(fill, KEY_PRESSED, 10));
        ImageView glyph = new ImageView(getContext());
        glyph.setImageDrawable(new Glyph(isShift));
        key.addView(glyph, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        key.setContentDescription(description);
        click(key, action);
        return key;
    }

    /** A rounded fill with a pressed state. */
    Drawable pressable(int fill, int pressed, float radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(pressed, radiusDp, 0, 0));
        states.addState(new int[]{}, rounded(fill, radiusDp, 0, 0));
        return states;
    }

    GradientDrawable rounded(int fill, float radiusDp, float strokeDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), stroke);
        return shape;
    }

    @SuppressWarnings("deprecation")
    static void background(View view, Drawable drawable) {
        view.setBackgroundDrawable(drawable);
    }

    private static void click(View view, final Runnable action) {
        view.setClickable(true);
        view.setFocusable(false);
        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
    }

    /** Shift and backspace outlines on a 24-unit grid with a 2-unit round stroke. */
    private static final class Glyph extends Drawable {
        private final boolean shift;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Glyph(boolean shift) {
            this.shift = shift;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(2f);
            stroke.setColor(TEXT);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float side = Math.min(b.width(), b.height());
            canvas.save();
            canvas.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f);
            canvas.scale(side / 24f, side / 24f);
            if (shift) {
                poly(canvas, 12f, 4f, 20f, 12.5f, 15.5f, 12.5f, 15.5f, 19.5f, 8.5f, 19.5f, 8.5f, 12.5f, 4f, 12.5f);
            } else {
                poly(canvas, 8f, 5f, 21f, 5f, 21f, 19f, 8f, 19f, 2f, 12f);
                canvas.drawLine(11.5f, 9.5f, 16.5f, 14.5f, stroke);
                canvas.drawLine(16.5f, 9.5f, 11.5f, 14.5f, stroke);
            }
            canvas.restore();
        }

        private void poly(Canvas canvas, float... xy) {
            path.reset();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            path.close();
            canvas.drawPath(path, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            stroke.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
