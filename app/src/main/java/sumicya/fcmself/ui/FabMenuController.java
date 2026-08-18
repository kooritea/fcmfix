package sumicya.fcmself.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.shape.ShapeAppearanceModel;

import sumicya.fcmself.R;
import sumicya.fcmself.core.Contract;

/**
 * The popup menu anchored above the FAB: three persisted switches, two actions and the collapsible
 * hook-health panel.
 *
 * <p>The popup is content sized and re-measured whenever the health panel expands, so it grows
 * upwards from the dock instead of jumping to a fixed height.
 */
public final class FabMenuController {

    /** Everything the menu needs from the screen that owns it. */
    public interface Host {
        boolean isSwitchEnabled(String key);

        /** @return true when the new value was persisted. */
        boolean setSwitchEnabled(String key, boolean value);

        void onEnableAllFcm();

        void onOpenDiagnostics();

        /** The health panel was expanded; push rendered status text into {@code statusView}. */
        void onHealthPanelOpened(TextView statusView);

        void onHealthPanelClosed();

        void onMenuDismissed();
    }

    private static final int[] SWITCH_LABELS = {
            R.string.fab_menu_disable_auto_clean,
            R.string.fab_menu_include_icebox,
            R.string.fab_menu_no_response
    };

    private static final int[] ALL_LABELS = {
            R.string.fab_menu_disable_auto_clean,
            R.string.fab_menu_include_icebox,
            R.string.fab_menu_no_response,
            R.string.fab_menu_enable_all_fcm,
            R.string.fab_menu_open_diagnostics
    };

    private static final String[] SWITCH_KEYS = {
            Contract.KEY_DISABLE_AUTO_CLEAN,
            Contract.KEY_INCLUDE_ICEBOX,
            Contract.KEY_NO_RESPONSE
    };

    private final Activity activity;
    private final Host host;
    private final FloatingActionButton fab;
    private PopupWindow popup;
    private boolean healthExpanded;

    public FabMenuController(Activity activity, Host host, FloatingActionButton fab) {
        this.activity = activity;
        this.host = host;
        this.fab = fab;
    }

    public void show() {
        if (popup != null && popup.isShowing()) {
            return;
        }
        View content = LayoutInflater.from(activity).inflate(R.layout.fab_menu, null);
        LinearLayout items = content.findViewById(R.id.fab_menu_items);
        AppTheme theme = AppTheme.from(activity);

        for (int i = 0; i < SWITCH_KEYS.length; i++) {
            final String key = SWITCH_KEYS[i];
            final int label = SWITCH_LABELS[i];
            View row = LayoutInflater.from(activity).inflate(R.layout.fab_menu_switch_row, items, false);
            TextView title = row.findViewById(R.id.fab_menu_switch_title);
            title.setText(label);
            title.setTextColor(theme.onSurface);
            com.google.android.material.materialswitch.MaterialSwitch sw =
                    row.findViewById(R.id.fab_menu_switch);
            sw.setChecked(host.isSwitchEnabled(key));
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!host.setSwitchEnabled(key, isChecked)) {
                    buttonView.setChecked(!isChecked);
                }
            });
            items.addView(row);
        }

        View healthPanel = LayoutInflater.from(activity).inflate(R.layout.hook_health_panel, items, false);
        MaterialCardView healthCard = healthPanel.findViewById(R.id.hook_health_card);
        TextView healthStatus = healthPanel.findViewById(R.id.hook_health_status);
        ImageView healthChevron = healthPanel.findViewById(R.id.hook_health_chevron);
        healthCard.setCardBackgroundColor(theme.primaryContainer);
        healthCard.setStrokeWidth(0);
        healthCard.setCardElevation(0f);
        healthCard.setMaxCardElevation(0f);
        AppTheme.removeShadow(healthCard);
        healthStatus.setTextColor(theme.onPrimaryContainer);
        healthStatus.setOnLongClickListener(v -> {
            CharSequence text = healthStatus.getText();
            if (text == null || text.length() == 0) {
                return true;
            }
            ClipboardManager cm =
                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("fcmself-health", text));
                Toast.makeText(activity, R.string.hook_health_copied, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        items.addView(healthPanel);

        ScrollView menuScroll = content.findViewById(R.id.fab_menu_scroll);
        // ... rest truncated for this attempt - need full content
