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
            R.string.fab_menu_select_all_fcm,
            R.string.fab_menu_diagnostics,
            R.string.fab_menu_hook_health
    };

    private final Activity activity;
    private final AppTheme theme;
    private final Host host;

    public FabMenuController(Activity activity, AppTheme theme, Host host) {
        this.activity = activity;
        this.theme = theme;
        this.host = host;
    }

    public void show(FloatingActionButton anchor) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        ViewGroup popupParent = (ViewGroup) anchor.getParent();
        View content = inflater.inflate(R.layout.fab_menu_popup, popupParent, false);
        content.setClipToOutline(true);
        LinearLayout items = content.findViewById(R.id.fab_menu_items);

        final int horizontalMargin = theme.dp(8);
        final int popupWidth = measureWidth(inflater, items, horizontalMargin);
        final int expandedWidth = Math.min(
                theme.dp(280),
                activity.getResources().getDisplayMetrics().widthPixels - horizontalMargin * 2);

        PopupWindow popup = new PopupWindow(
                content, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(0f);
        popup.setAnimationStyle(R.style.Animation_Fcmself_FabMenu);
        popup.setClippingEnabled(true);

        for (int i = 0; i < Contract.BOOLEAN_KEYS.length; i++) {
            addSwitchRow(items, SWITCH_LABELS[i], Contract.BOOLEAN_KEYS[i]);
        }
        addActionRow(items, R.string.fab_menu_select_all_fcm, R.drawable.ic_status_check, v -> {
            host.onEnableAllFcm();
            popup.dismiss();
        });
        addActionRow(items, R.string.fab_menu_diagnostics, R.drawable.ic_open_external, v -> {
            popup.dismiss();
            host.onOpenDiagnostics();
        });

        View healthRow = addActionRow(
                items, R.string.fab_menu_hook_health, R.drawable.ic_health, null);
        View healthPanel = inflater.inflate(R.layout.hook_health_panel, items, false);
        MaterialCardView healthCard = (MaterialCardView) healthPanel;
        TextView healthStatus = healthPanel.findViewById(R.id.hook_health_status);
        healthCard.setCardBackgroundColor(
                AppTheme.translucent(theme.primaryContainer, AppTheme.GLASS_ALPHA));
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
        healthRow.setOnClickListener(v -> {
            boolean expand = healthPanel.getVisibility() != View.VISIBLE;
            healthPanel.setVisibility(expand ? View.VISIBLE : View.GONE);
            if (expand) {
                healthStatus.setText(R.string.hook_health_querying);
                host.onHealthPanelOpened(healthStatus);
            } else {
                host.onHealthPanelClosed();
            }
            applyEdgeShapes(items);
            position(popup, content, anchor, expand ? expandedWidth : popupWidth, horizontalMargin);
            if (expand) {
                healthPanel.post(() -> menuScroll.smoothScrollTo(0, healthPanel.getBottom()));
            }
        });
        popup.setOnDismissListener(host::onMenuDismissed);
        applyEdgeShapes(items);
        position(popup, content, anchor, popupWidth, horizontalMargin);
    }

    private int measureWidth(LayoutInflater inflater, LinearLayout items, int horizontalMargin) {
        View probe = inflater.inflate(R.layout.fab_menu_item, items, false);
        TextView label = probe.findViewById(R.id.menu_label);
        float longestText = 0f;
        for (int labelRes : ALL_LABELS) {
            longestText = Math.max(
                    longestText, label.getPaint().measureText(activity.getString(labelRes)));
        }
        int desired = Math.round(longestText) + theme.dp(80);
        int screenMaximum =
                activity.getResources().getDisplayMetrics().widthPixels - horizontalMargin * 2;
        int maximum = Math.min(theme.dp(280), screenMaximum);
        int minimum = Math.min(theme.dp(208), maximum);
        return Math.max(minimum, Math.min(desired, maximum));
    }

    private void position(
            PopupWindow popup,
            View content,
            View anchor,
            int popupWidth,
            int horizontalMargin) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        // The content-sized popup remains centred on the connected control rail.
        int x = (screenWidth - popupWidth) / 2;
        int maximumX = screenWidth - popupWidth - horizontalMargin;
        x = Math.max(horizontalMargin, Math.min(x, maximumX));
        int verticalGap = theme.dp(8);
        int availableHeight = Math.max(1, location[1] - verticalGap - horizontalMargin);
        int popupHeight = Math.min(content.getMeasuredHeight(), availableHeight);
        int y = Math.max(horizontalMargin, location[1] - popupHeight - verticalGap);
        if (popup.isShowing()) {
            popup.update(x, y, popupWidth, popupHeight);
        } else {
            popup.setHeight(popupHeight);
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        }
    }

    private void applyEdgeShapes(LinearLayout items) {
        int firstVisible = -1;
        int lastVisible = -1;
        for (int i = 0; i < items.getChildCount(); i++) {
            if (items.getChildAt(i).getVisibility() == View.VISIBLE) {
                if (firstVisible < 0) {
                    firstVisible = i;
                }
                lastVisible = i;
            }
        }
        float radius = theme.dpF(16);
        for (int i = 0; i < items.getChildCount(); i++) {
            MaterialCardView row = (MaterialCardView) items.getChildAt(i);
            ShapeAppearanceModel.Builder shape = row.getShapeAppearanceModel()
                    .toBuilder()
                    .setAllCornerSizes(0f);
            if (i == firstVisible) {
                shape.setTopLeftCornerSize(radius);
                shape.setTopRightCornerSize(radius);
            }
            if (i == lastVisible) {
                shape.setBottomLeftCornerSize(radius);
                shape.setBottomRightCornerSize(radius);
            }
            row.setShapeAppearanceModel(shape.build());
        }
    }

    private View inflateRow(LinearLayout parent, int labelRes) {
        View row = LayoutInflater.from(activity)
                .inflate(R.layout.fab_menu_item, parent, false);
        ((TextView) row.findViewById(R.id.menu_label)).setText(labelRes);
        parent.addView(row);
        return row;
    }

    private void addSwitchRow(LinearLayout parent, int labelRes, String key) {
        View row = inflateRow(parent, labelRes);
        bindSwitchRow(row, host.isSwitchEnabled(key));
        row.setOnClickListener(v -> {
            boolean next = !host.isSwitchEnabled(key);
            if (host.setSwitchEnabled(key, next)) {
                bindSwitchRow(row, next);
            }
        });
    }

    private void bindSwitchRow(View row, boolean checked) {
        MaterialCardView rowCard = (MaterialCardView) row;
        MaterialCardView stateContainer = row.findViewById(R.id.menu_state_container);
        MaterialCardView labelContainer = row.findViewById(R.id.menu_label_container);
        ImageView symbol = row.findViewById(R.id.menu_symbol);
        TextView label = row.findViewById(R.id.menu_label);
        int content = checked ? theme.onPrimaryContainer : theme.onErrorContainer;
        theme.applyPrimarySurface(rowCard, checked, theme.errorContainer);
        // Child segments stay transparent; the row owns one continuous, shadow-free surface.
        stateContainer.setCardBackgroundColor(Color.TRANSPARENT);
        labelContainer.setCardBackgroundColor(Color.TRANSPARENT);
        symbol.setImageResource(
                checked ? R.drawable.ic_status_check : R.drawable.ic_status_close);
        symbol.setImageTintList(ColorStateList.valueOf(content));
        label.setTextColor(content);
        row.setContentDescription(label.getText() + "，" + (checked ? "已开启" : "已关闭"));
    }

    private View addActionRow(
            LinearLayout parent, int labelRes, int iconRes, View.OnClickListener listener) {
        View row = inflateRow(parent, labelRes);
        MaterialCardView rowCard = (MaterialCardView) row;
        MaterialCardView stateContainer = row.findViewById(R.id.menu_state_container);
        MaterialCardView labelContainer = row.findViewById(R.id.menu_label_container);
        ImageView symbol = row.findViewById(R.id.menu_symbol);
        TextView label = row.findViewById(R.id.menu_label);
        // Actions share the exact same translucent primary surface as an enabled switch.
        theme.applyPrimarySurface(rowCard, true, theme.surfaceContainerHigh);
        stateContainer.setCardBackgroundColor(Color.TRANSPARENT);
        labelContainer.setCardBackgroundColor(Color.TRANSPARENT);
        symbol.setImageResource(iconRes);
        symbol.setImageTintList(ColorStateList.valueOf(theme.onPrimaryContainer));
        label.setTextColor(theme.onPrimaryContainer);
        row.setContentDescription(label.getText());
        if (listener != null) {
            row.setOnClickListener(listener);
        }
        return row;
    }
}
