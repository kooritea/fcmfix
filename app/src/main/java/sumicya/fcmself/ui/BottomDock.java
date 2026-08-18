package sumicya.fcmself.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;

import sumicya.fcmself.R;
import sumicya.fcmself.model.FilterMode;

/**
 * The connected bottom control rail: filter segments with their counters, the expanding search
 * field and the menu FAB.
 *
 * <p>These controls share one translucent surface and merge their touching corners, so their shape
 * and colour rules only make sense together — keeping them in one component avoids the cross-talk
 * that used to be spread over the Activity.
 */
public final class BottomDock {

    private static final long SEARCH_DEBOUNCE_MS = 120L;

    public interface Callbacks {
        void onFilterChanged(FilterMode mode);

        /** Debounced, trimmed and lower-cased search input. */
        void onSearchQueryChanged(String query);

        void onFabMenuRequested();
    }

    private final Activity activity;
    private final AppTheme theme;
    private final Callbacks callbacks;

    private final int enterMs;
    private final int exitMs;
    private final int scaleMs;
    private final int fastMs;
    private final long searchReopenDelayMs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final MaterialCardView dock;
    private final MaterialCardView[] filterCards;
    private final TextView[] filterLabels;
    private final TextView[] filterCounts;
    private final MaterialCardView searchContainer;
    private final MaterialCardView searchToggleContainer;
    private final TextInputEditText searchInput;
    private final ImageButton searchToggle;
    private final FloatingActionButton fab;

    private ValueAnimator searchWidthAnimator;
    private String pendingQuery = "";
    private boolean searchExpanded;
    private FilterMode filterMode = FilterMode.ALL;

    public BottomDock(Activity activity, AppTheme theme, Callbacks callbacks) {
        this.activity = activity;
        this.theme = theme;
        this.callbacks = callbacks;
        this.enterMs = activity.getResources().getInteger(R.integer.motion_enter_ms);
        this.exitMs = activity.getResources().getInteger(R.integer.motion_exit_ms);
        this.scaleMs = activity.getResources().getInteger(R.integer.motion_scale_ms);
        this.fastMs = activity.getResources().getInteger(R.integer.motion_fast_ms);
        this.searchReopenDelayMs = fastMs;

        dock = activity.findViewById(R.id.bottom_dock);
        filterCards = new MaterialCardView[]{
                activity.findViewById(R.id.filter_all_container),
                activity.findViewById(R.id.filter_fcm_container),
                activity.findViewById(R.id.filter_disabled_container)
        };
        filterLabels = new TextView[]{
                activity.findViewById(R.id.filter_all_label),
                activity.findViewById(R.id.filter_fcm_label),
                activity.findViewById(R.id.filter_disabled_label)
        };
        filterCounts = new TextView[]{
                activity.findViewById(R.id.filter_all_count),
                activity.findViewById(R.id.filter_fcm_count),
                activity.findViewById(R.id.filter_disabled_count)
        };
        searchContainer = activity.findViewById(R.id.search_container);
        searchToggleContainer = activity.findViewById(R.id.search_toggle_container);
        searchInput = activity.findViewById(R.id.search_input);
        searchToggle = activity.findViewById(R.id.search_toggle);
        fab = activity.findViewById(R.id.fab);

        AppTheme.removeShadow(dock);
        for (MaterialCardView card : filterCards) {
            AppTheme.removeShadow(card);
        }
        AppTheme.removeShadow(searchContainer);
        AppTheme.removeShadow(searchToggleContainer);

        filterCards[0].setOnClickListener(v -> selectFilter(FilterMode.ALL));
        filterCards[1].setOnClickListener(v -> selectFilter(FilterMode.FCM_ONLY));
        filterCards[2].setOnClickListener(v -> selectFilter(FilterMode.DISABLED));
        searchToggleContainer.setOnClickListener(v -> toggleSearch());
        fab.setOnClickListener(v -> callbacks.onFabMenuRequested());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                pendingQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                main.removeCallbacks(emitQuery);
                main.postDelayed(emitQuery, SEARCH_DEBOUNCE_MS);
                updateSearchWidth(s, true);
            }
        });

        updateFilterStyle();
        updateSearchToggleStyle(false);
        searchContainer.setVisibility(View.GONE);
    }

    private final Runnable emitQuery = () -> callbacks.onSearchQueryChanged(pendingQuery);

    public void setCounts(int all, int fcm, int disabled) {
        filterCounts[0].setText(String.valueOf(all));
        filterCounts[1].setText(String.valueOf(fcm));
        filterCounts[2].setText(String.valueOf(disabled));
    }

    public FilterMode getFilterMode() {
        return filterMode;
    }

    public void collapseSearchIfOpen() {
        if (searchExpanded) {
            toggleSearch();
        }
    }

    public void reopenSearchAfterReload() {
        if (!searchExpanded) {
            return;
        }
        main.postDelayed(() -> {
            if (searchExpanded) {
                searchInput.requestFocus();
            }
        }, searchReopenDelayMs);
    }

    private void selectFilter(FilterMode mode) {
        if (filterMode == mode) {
            return;
        }
        filterMode = mode;
        updateFilterStyle();
        animateSelection(filterCards[mode.ordinal()]);
        callbacks.onFilterChanged(mode);
    }

    private void animateSelection(View view) {
        view.animate().cancel();
        view.setScaleX(0.94f);
        view.setScaleY(0.94f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(enterMs).start();
    }

    private void updateFilterStyle() {
        int selectedIndex = filterMode.ordinal();
        float segmentRadius = theme.dpF(16);
        for (int i = 0; i < filterCards.length; i++) {
            boolean selected = i == selectedIndex;
            int content = selected ? theme.onPrimaryContainer : theme.onSurfaceVariant;
            theme.applyPrimarySurface(filterCards[i], selected, Color.TRANSPARENT);
            if (i == 0) {
                filterCards[i].setShapeAppearanceModel(filterCards[i].getShapeAppearanceModel()
                        .toBuilder()
                        .setAllCornerSizes(segmentRadius)
                        .build());
            }
            filterLabels[i].setTextColor(content);
            filterCounts[i].setTextColor(content);
            filterCards[i].setContentDescription(
                    filterLabels[i].getText() + "，" + filterCounts[i].getText()
                            + (selected ? "，当前筛选" : ""));
        }
        updateMiddleControlShapes();
    }

    private void updateSearchToggleStyle(boolean active) {
        theme.applyPrimarySurface(searchToggleContainer, active, Color.TRANSPARENT);
        searchToggle.setImageResource(active ? R.drawable.ic_status_close : R.drawable.ic_search);
        searchToggle.setImageTintList(ColorStateList.valueOf(
                active ? theme.onPrimaryContainer : theme.onSurfaceVariant));
        updateMiddleControlShapes();
    }

    /** The FCM segment and the search toggle merge their touching corners while both are active. */
    private void updateMiddleControlShapes() {
        float radius = theme.dpF(16);
        boolean merge = filterMode == FilterMode.FCM_ONLY && searchExpanded;

        ShapeAppearanceModel.Builder fcmShape = filterCards[1].getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(radius);
        ShapeAppearanceModel.Builder searchShape = searchToggleContainer.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(radius);

        if (merge) {
            fcmShape.setTopRightCornerSize(0f).setBottomRightCornerSize(0f);
            searchShape.setTopLeftCornerSize(0f).setBottomLeftCornerSize(0f);
        }

        // Disabled segment always full radius on the right edge of the group.
        ShapeAppearanceModel.Builder disabledShape = filterCards[2].getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(radius);

        filterCards[1].setShapeAppearanceModel(fcmShape.build());
        searchToggleContainer.setShapeAppearanceModel(searchShape.build());
        filterCards[2].setShapeAppearanceModel(disabledShape.build());
    }

    private void toggleSearch() {
        InputMethodManager keyboard =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        searchContainer.animate().cancel();
        float offset = theme.dpF(12);
        if (!searchExpanded) {
            searchExpanded = true;
            updateSearchWidth(searchInput.getText(), false);
            updateSearchToggleStyle(true);
            searchContainer.setVisibility(View.VISIBLE);
            searchContainer.setPivotY(theme.dpF(48));
            searchContainer.setAlpha(0f);
            searchContainer.setScaleX(0.94f);
            searchContainer.setScaleY(0.82f);
            searchContainer.setTranslationY(offset);
            searchContainer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(scaleMs)
                    .start();
            searchInput.requestFocus();
            if (keyboard != null) {
                keyboard.showSoftInput(searchInput, 0);
            }
        } else {
            searchExpanded = false;
            updateSearchToggleStyle(false);
            searchInput.setText("");
            searchInput.clearFocus();
            if (keyboard != null) {
                keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
            searchContainer.animate()
                    .alpha(0f)
                    .scaleX(0.94f)
                    .scaleY(0.82f)
                    .translationY(offset)
                    .setDuration(exitMs)
                    .withEndAction(() -> {
                        searchContainer.setVisibility(View.GONE);
                        searchContainer.setAlpha(1f);
                        searchContainer.setScaleX(1f);
                        searchContainer.setScaleY(1f);
                        searchContainer.setTranslationY(0f);
                    })
                    .start();
        }
    }

    private void updateSearchWidth(CharSequence query, boolean animate) {
        CharSequence measured = query == null || query.length() == 0
                ? activity.getString(R.string.search_hint)
                : query;
        int desired = Math.round(searchInput.getPaint().measureText(measured.toString()))
                + theme.dp(40);
        desired = Math.max(theme.dp(144), Math.min(desired, theme.dp(280)));

        ViewGroup.LayoutParams params = searchContainer.getLayoutParams();
        int currentWidth = searchContainer.getWidth() > 0
                ? searchContainer.getWidth()
                : params.width;
        if (searchWidthAnimator != null) {
            searchWidthAnimator.cancel();
        }
        if (!animate || currentWidth <= 0 || currentWidth == desired) {
            params.width = desired;
            searchContainer.setLayoutParams(params);
            return;
        }
        searchWidthAnimator = ValueAnimator.ofInt(currentWidth, desired);
        searchWidthAnimator.setDuration(fastMs);
        searchWidthAnimator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams animated = searchContainer.getLayoutParams();
            animated.width = (int) animation.getAnimatedValue();
            searchContainer.setLayoutParams(animated);
        });
        searchWidthAnimator.start();
    }
}
