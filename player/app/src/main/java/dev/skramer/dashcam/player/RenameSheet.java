package dev.skramer.dashcam.player;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A rename popup laid over the activity, like the Tilepad launcher's: a dimmed backdrop, a card
 * with the name field and buttons on the left and the in-app keyboard on the right.
 */
final class RenameSheet {
    interface Listener {
        /** The trimmed name; empty clears it. */
        void onSave(String name);
    }

    private static final int SCRIM = 0xCC05070A, CARD = 0xFF181C23, LINE = 0xFF2A303A;
    private static final int MUTED = 0xFF9AA3AF, FAINT = 0xFF5B6470;

    private final FrameLayout host;
    private final FrameLayout scrim;

    private RenameSheet(FrameLayout host, FrameLayout scrim) {
        this.host = host;
        this.scrim = scrim;
    }

    /** Shows the popup; placeholder is what shows (and what's used) while the name is empty. */
    static RenameSheet show(Activity activity, String title, String current, final String placeholder,
            String hint, final Listener listener) {
        FrameLayout host = (FrameLayout) activity.findViewById(android.R.id.content);
        final float density = activity.getResources().getDisplayMetrics().density;
        final StringBuilder text = new StringBuilder(current == null ? "" : current);

        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(SCRIM);
        scrim.setClickable(true);
        final RenameSheet sheet = new RenameSheet(host, scrim);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setClickable(true);  // only the backdrop dismisses
        card.setPadding(dp(density, 32), dp(density, 26), dp(density, 26), dp(density, 26));

        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(activity, title, 26, KeyPad.TEXT, true));
        final TextView field = text(activity, "", 22, KeyPad.TEXT, false);
        field.setSingleLine(true);
        field.setEllipsize(TextUtils.TruncateAt.START);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(density, 20), 0, dp(density, 20), 0);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(density, 64));
        fp.topMargin = dp(density, 20);
        left.addView(field, fp);
        TextView hintView = text(activity, hint, 15, MUTED, false);
        hintView.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(density, 12);
        left.addView(hintView, hp);
        left.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        final Runnable update = new Runnable() {
            @Override
            public void run() {
                boolean empty = text.length() == 0;
                field.setText(empty ? placeholder : text);
                field.setTextColor(empty ? FAINT : KeyPad.TEXT);
            }
        };
        final Runnable commit = new Runnable() {
            @Override
            public void run() {
                sheet.dismiss();
                listener.onSave(text.toString().trim());
            }
        };
        final KeyPad keys = new KeyPad(activity, "Save", new KeyPad.Listener() {
            @Override
            public void onText(String value) {
                text.append(value);
                update.run();
            }

            @Override
            public void onBackspace() {
                if (text.length() > 0) text.setLength(text.length() - 1);
                update.run();
            }

            @Override
            public void onClear() {
                text.setLength(0);
                update.run();
            }

            @Override
            public void onDone() {
                commit.run();
            }
        });
        keys.setShift(text.length() == 0);
        KeyPad.background(card, keys.rounded(CARD, 28, 1, LINE));
        KeyPad.background(field, keys.rounded(KeyPad.KEY, 16, 2, KeyPad.ACCENT));
        update.run();

        View cancel = button(activity, keys, "Cancel", KeyPad.KEY, KeyPad.KEY_PRESSED);
        View save = button(activity, keys, "Save", KeyPad.ACCENT, KeyPad.ACCENT_PRESSED);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commit.run();
            }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(-2, dp(density, 58)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(density, 58));
        sp.leftMargin = dp(density, 14);
        buttons.addView(save, sp);
        left.addView(buttons);

        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(dp(density, 560), dp(density, 268));
        kp.leftMargin = dp(density, 28);
        card.addView(keys, kp);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        cp.leftMargin = cp.rightMargin = dp(density, 24);
        scrim.addView(card, cp);
        host.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(140).start();
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        return sheet;
    }

    boolean isShowing() {
        return scrim.getParent() != null;
    }

    void dismiss() {
        if (!isShowing() || !scrim.isClickable()) return;
        scrim.setClickable(false);
        scrim.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override
            public void run() {
                ((ViewGroup) scrim.getParent()).removeView(scrim);
            }
        }).start();
    }

    private static View button(Activity activity, KeyPad keys, String title, int fill, int pressed) {
        TextView v = text(activity, title, 19, KeyPad.TEXT, true);
        v.setGravity(Gravity.CENTER);
        float density = activity.getResources().getDisplayMetrics().density;
        v.setPadding(dp(density, 26), 0, dp(density, 26), 0);
        v.setMinWidth(dp(density, 150));
        KeyPad.background(v, keys.pressable(fill, pressed, 28));
        v.setClickable(true);
        return v;
    }

    private static TextView text(Activity activity, String value, float sp, int color, boolean bold) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    private static int dp(float density, float v) {
        return Math.round(v * density);
    }
}
