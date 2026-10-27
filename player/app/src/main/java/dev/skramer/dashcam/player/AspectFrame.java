package dev.skramer.dashcam.player;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The video's frame: as tall as it's allowed, and 4:3 wide (the backup camera is NTSC,
 * 720x480 stored, 4:3 shown), but no more than 60% of the available width.
 */
public final class AspectFrame extends FrameLayout {
    public AspectFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = View.MeasureSpec.getSize(heightSpec);
        int maxW = View.MeasureSpec.getSize(widthSpec) * 6 / 10;
        int w = Math.min(h * 4 / 3, maxW);
        if (w < h * 4 / 3) h = w * 3 / 4;
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    }
}
