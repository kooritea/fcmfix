package sumicya.fcmself.ui;

import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import sumicya.fcmself.R;
import sumicya.fcmself.model.AppInfo;

/**
 * The self-sizing confirmation pill above the dock.
 *
 * <p>Motion durations come from {@code R.integer.motion_*} so enter/exit stay aligned with the
 * FAB menu animations.
 */
public final class FeedbackBar {

    public static final long DURATION_SHORT_MS = 2_400L;
    public static final long DURATION_LONG_MS = 4_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final MaterialCardView container;
    private final ImageView icon;
    private final TextView text;
    private final TextView action;
    private final AppTheme theme;
    private final int enterMs;
    private final int exitMs;
    private final Runnable hideTask = () -> hide(false);

    private Runnable undoAction;
    private int generation;

    public FeedbackBar(View root, AppTheme theme) {
        this.theme = theme;
        this.container = root.findViewById(R.id.feedback_container);
        this.icon = root.findViewById(R.id.feedback_icon);
        this.text = root.findViewById(R.id.feedback_text);
        this.action = root.findViewById(R.id.feedback_action);
        this.enterMs = root.getResources().getInteger(R.integer.motion_enter_ms);
        this.exitMs = root.getResources().getInteger(R.integer.motion_exit_ms);
        this.action.setOnClickListener(v -> {
            Runnable pending = undoAction;
            hide(true);
            if (pending != null) {
                pending.run();
            }
        });
    }

    public void showToggle(AppInfo info, Runnable undo) {
        boolean allowed = info.isAllow;
        show((allowed ? "已启用：" : "已停用：") + info.name,
                allowed ? R.drawable.ic_status_check : R.drawable.ic_status_close,
                AppTheme.translucent(
                        allowed ? theme.primaryContainer : theme.errorContainer,
                        AppTheme.GLASS_ALPHA),
                allowed ? theme.onPrimaryContainer : theme.onErrorContainer,
                DURATION_LONG_MS,
                undo);
    }

    public void showUnsupported(String message) {
        show(message,
                R.drawable.ic_status_dash,
                AppTheme.translucent(theme.surfaceContainerHigh, AppTheme.GLASS_ALPHA),
                theme.onSurface,
                DURATION_SHORT_MS,
                null);
    }

    public void show(
            String message,
            int iconRes,
            int containerColor,
            int contentColor,
            long durationMs,
            Runnable undo) {
        main.removeCallbacks(hideTask);
        generation++;
        undoAction = undo;
        container.animate().cancel();
        container.setCardBackgroundColor(containerColor);
        container.setStrokeWidth(0);
        AppTheme.removeShadow(container);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(contentColor));
        text.setText(message);
        text.setTextColor(contentColor);
        applyWidth(message);
        action.setTextColor(contentColor);
        action.setEnabled(undo != null);
        action.setVisibility(undo != null ? View.VISIBLE : View.INVISIBLE);

        if (container.getVisibility() != View.VISIBLE) {
            container.setAlpha(0f);
            container.setVisibility(View.VISIBLE);
            container.animate().alpha(1f).setDuration(enterMs).start();
        } else {
            container.setAlpha(1f);
        }
        main.postDelayed(hideTask, durationMs);
    }

    public void hide(boolean immediate) {
        main.removeCallbacks(hideTask);
        undoAction = null;
        final int current = ++generation;
        container.animate().cancel();
        if (container.getVisibility() != View.VISIBLE) {
            container.setAlpha(1f);
            return;
        }
        if (immediate) {
            container.setVisibility(View.GONE);
            container.setAlpha(1f);
            return;
        }
        container.animate()
                .alpha(0f)
                .setDuration(exitMs)
                .withEndAction(() -> {
                    if (current == generation) {
                        container.setVisibility(View.GONE);
                        container.setAlpha(1f);
                    }
                })
                .start();
    }

    public void release() {
        main.removeCallbacks(hideTask);
        hide(true);
    }

    private void applyWidth(String message) {
        int desired = Math.round(text.getPaint().measureText(message)) + theme.dp(112);
        desired = Math.max(theme.dp(160), Math.min(desired, theme.dp(280)));
        ViewGroup.LayoutParams params = container.getLayoutParams();
        if (params.width != desired) {
            params.width = desired;
            container.setLayoutParams(params);
        }
    }
}
