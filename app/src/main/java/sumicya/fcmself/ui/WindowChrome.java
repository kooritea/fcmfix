package sumicya.fcmself.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Edge-to-edge chrome: the header fade over the status bar and the inset-aware paddings.
 *
 * <p>The header extends into the status-bar region; a compact fade ends before the first row so
 * the title stays readable without letting the grid's rounded corners enter the safe area.
 */
public final class WindowChrome {

    private WindowChrome() {
    }

    public static void applyHeaderScrim(View header, int surfaceColor) {
        if (header == null) {
            return;
        }
        try {
            int r = Color.red(surfaceColor);
            int g = Color.green(surfaceColor);
            int b = Color.blue(surfaceColor);
            int[] colors = {
                    Color.argb(0xF2, r, g, b),
                    Color.argb(0xE8, r, g, b),
                    Color.argb(0xC0, r, g, b),
                    Color.argb(0x60, r, g, b),
                    Color.argb(0x00, r, g, b)
            };
            float[] offsets = {0f, 0.50f, 0.72f, 0.90f, 1f};
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, colors);
            gradient.setColors(colors, offsets);
            gradient.setGradientType(GradientDrawable.LINEAR_GRADIENT);
            gradient.setShape(GradientDrawable.RECTANGLE);
            header.setBackground(gradient);
            header.setElevation(0f);
            header.setOutlineProvider(null);
            header.setClipToOutline(false);
        } catch (Throwable t) {
            header.setAlpha(0.96f);
        }
    }

    public static void applyInsets(View header, RecyclerView list, View bottomControls) {
        if (header != null) {
            final int basePaddingStart = header.getPaddingStart();
            final int basePaddingEnd = header.getPaddingEnd();
            final int basePaddingBottom = header.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(header, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
                v.setPaddingRelative(basePaddingStart, bars.top, basePaddingEnd, basePaddingBottom);
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(header);

            header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (list == null) {
                    return;
                }
                int height = v.getHeight();
                if (height <= 0 || list.getPaddingTop() == height) {
                    return;
                }
                // The first row starts after the full header; no rounded card edge is drawn under
                // the compact gradient tail.
                list.setPadding(
                        list.getPaddingLeft(), height, list.getPaddingRight(),
                        list.getPaddingBottom());
            });
        }

        if (bottomControls != null) {
            ViewGroup.MarginLayoutParams initial =
                    (ViewGroup.MarginLayoutParams) bottomControls.getLayoutParams();
            final int baseBottomMargin = initial.bottomMargin;
            ViewCompat.setOnApplyWindowInsetsListener(bottomControls, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                params.bottomMargin = baseBottomMargin + bars.bottom;
                v.setLayoutParams(params);
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(bottomControls);
        }
    }
}
