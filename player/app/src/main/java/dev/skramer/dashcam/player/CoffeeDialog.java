package dev.skramer.dashcam.player;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Entirely offline: a bundled QR image, with no browser, network or payment SDK. */
final class CoffeeDialog {
    private CoffeeDialog() { }

    static Dialog show(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        int gap = Math.round(14 * density), codeSize = Math.round(144 * density);
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(activity.getResources().getColor(R.color.panel));
        background.setCornerRadius(12 * density);
        body.setBackground(background);
        TextView title = new TextView(activity);
        title.setText(emoji(activity, R.string.coffee_title, "☕", R.drawable.ic_coffee, 28));
        title.setTextSize(24); title.setGravity(Gravity.CENTER);
        title.setPadding(gap, gap, gap, gap);
        GradientDrawable band = new GradientDrawable();
        band.setColor(activity.getResources().getColor(R.color.button));
        float radius = 12 * density;
        band.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        title.setBackground(band);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(activity.getResources().getColor(R.color.text));
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(gap, gap, gap, gap);
        body.addView(content, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundResource(R.drawable.qr_card);
        int inset = Math.round(8 * density);
        card.setPadding(inset, inset, inset, inset);
        content.addView(card, new LinearLayout.LayoutParams(codeSize + inset * 2, -2));

        ImageView code = new ImageView(activity);
        code.setImageResource(R.drawable.kofi_qr);
        code.setScaleType(ImageView.ScaleType.FIT_CENTER);
        code.setContentDescription("QR code for https://ko-fi.com/kramertech");
        card.addView(code, new LinearLayout.LayoutParams(codeSize, codeSize));

        TextView link = new TextView(activity);
        link.setText(R.string.coffee_url);
        link.setTextColor(0xff344054); link.setTextSize(12); link.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams url = new LinearLayout.LayoutParams(-1, -2);
        url.topMargin = Math.round(2 * density);
        card.addView(link, url);

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        words.leftMargin = gap;
        content.addView(copy, words);
        TextView message = new TextView(activity);
        message.setText(emoji(activity, R.string.coffee_message, "🙂", R.drawable.ic_smile, 20));
        message.setTextColor(activity.getResources().getColor(R.color.text));
        message.setTextSize(18);
        message.setLineSpacing(4 * density, 1);
        copy.addView(message);

        Button done = new Button(activity);
        done.setText(R.string.coffee_dismiss); done.setTextSize(18);
        done.setBackgroundResource(R.drawable.button_bg);
        done.setTextColor(activity.getResources().getColor(R.color.text));
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { dialog.dismiss(); }
        });
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-1, Math.round(48 * density));
        button.setMargins(gap, 0, gap, gap);
        body.addView(done, button);
        dialog.setContentView(body);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        int width = Math.min(Math.round(400 * density), activity.getResources().getDisplayMetrics().widthPixels - gap * 2);
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }

    /** Bundled emoji render even on KitKat builds whose system font has no glyph. */
    private static CharSequence emoji(Activity activity, int textId, String symbol, int imageId, int dp) {
        String text = activity.getString(textId);
        SpannableString result = new SpannableString(text);
        int size = Math.round(dp * activity.getResources().getDisplayMetrics().density);
        for (int at = text.indexOf(symbol); at >= 0; at = text.indexOf(symbol, at + symbol.length())) {
            Drawable image = activity.getResources().getDrawable(imageId);
            image.setBounds(0, 0, size, size);
            result.setSpan(new ImageSpan(image, ImageSpan.ALIGN_BASELINE), at, at + symbol.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }
}
