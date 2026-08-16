package sumicya.fcmself.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.card.MaterialCardView;

import sumicya.fcmself.R;

/**
 * The Material 3 Expressive palette resolved once per screen, plus the few surface rules the whole
 * UI shares.
 *
 * <p>Resolving theme attributes and allocating {@link ColorStateList}s per bind used to show up
 * while flinging the grid; every value here is computed a single time in {@code onCreate}.
 */
public final class AppTheme {

    /** Alpha of the "glass" primary-container surfaces (0-255). */
    public static final int GLASS_ALPHA = 184;
    /** Alpha of the connected bottom dock, one step lighter than the glass surfaces. */
    public static final int DOCK_ALPHA = 112;

    public final int error;
    public final int onSurface;
    public final int onSurfaceVariant;
    public final int surfaceContainerLow;
    public final int surfaceContainerHigh;
    public final int primaryContainer;
    public final int onPrimaryContainer;
    /**
     * Keep the dynamic Primary Container itself translucent. Do not pre-composite it onto a
     * neutral surface: that introduces a grey cast and defeats the lightweight glass effect.
     */
    public final int translucentPrimaryContainer;
    public final int errorContainer;
    public final int onErrorContainer;

    public final ColorStateList tintNeutral;
    public final ColorStateList tintOnPrimaryContainer;
    public final ColorStateList tintOnErrorContainer;

    private final float density;

    private AppTheme(Context context) {
        int fallbackPrimary = ContextCompat.getColor(context, R.color.colorPrimary);
        error = resolveColor(
                context,
                android.R.attr.colorError,
                ContextCompat.getColor(context, R.color.status_error));
        onSurface = resolveColor(
                context, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY);
        onSurfaceVariant = resolveColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(context, R.color.status_disconnected));
        surfaceContainerLow = resolveColor(
                context,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                Color.WHITE);
        surfaceContainerHigh = resolveColor(
                context,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.WHITE);
        primaryContainer = resolveColor(
                context,
                com.google.android.material.R.attr.colorPrimaryContainer,
                fallbackPrimary);
        onPrimaryContainer = resolveColor(
                context,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                Color.WHITE);
        translucentPrimaryContainer = translucent(primaryContainer, GLASS_ALPHA);
        errorContainer = resolveColor(
                context, com.google.android.material.R.attr.colorErrorContainer, error);
        onErrorContainer = resolveColor(
                context, com.google.android.material.R.attr.colorOnErrorContainer, Color.WHITE);

        tintNeutral = ColorStateList.valueOf(onSurfaceVariant);
        tintOnPrimaryContainer = ColorStateList.valueOf(onPrimaryContainer);
        tintOnErrorContainer = ColorStateList.valueOf(onErrorContainer);
        density = context.getResources().getDisplayMetrics().density;
    }

    public static AppTheme resolve(Context context) {
        return new AppTheme(context);
    }

    public static int resolveColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                return ContextCompat.getColor(context, value.resourceId);
            }
            return value.data;
        }
        return fallback;
    }

    public static int translucent(int color, int alpha) {
        return ColorUtils.setAlphaComponent(color, alpha);
    }

    /** Material surfaces in this app are flat; shadows would break the glass layering. */
    public static void removeShadow(View view) {
        view.setElevation(0f);
        view.setTranslationZ(0f);
        view.setStateListAnimator(null);
    }

    public void applyPrimarySurface(
            MaterialCardView card, boolean active, int inactiveContainer) {
        card.setCardBackgroundColor(active ? translucentPrimaryContainer : inactiveContainer);
        card.setCardElevation(0f);
        card.setMaxCardElevation(0f);
        removeShadow(card);
    }

    public int dp(float value) {
        return Math.round(value * density);
    }

    public float dpF(float value) {
        return value * density;
    }
}
