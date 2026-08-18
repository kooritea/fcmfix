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
    private final Runnable applySearch = this::dispatchSearchQuery;

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
                activity.findViewById(R.id.filter_all),
                activity.findViewById(R.id.filter_fcm)
        };
        filterLabels = new TextView[]{
                activity.findViewById(R.id.filter_all_label),
                activity.findViewById(R.id.filter_fcm_label)
        };
        filterCounts = new TextView[]{
                activity.findViewById(R.id.all_stats),
                activity.findViewById(R.id.fcm_stats)
        };
        searchContainer = activity.findViewById(R.id.search_container);
        searchToggleContainer = activity.findViewById(R.id.search_toggle_container);
        searchInput = activity.findViewById(R.id.search_input);
        searchToggle = activity.findViewById(R.id.search_toggle);
        fab = activity.findViewById(R.id.main_fab);

        applyGlassSurfaces();
        wireSearch();
        wireFilters();
        wireFab();
    }

    public FilterMode filterMode() {
        return filterMode;
    }

    public FloatingActionButton fab() {
        return fab;
    }

    public boolean isSearchExpanded() {
        return searchExpanded;
    }

    /** Updates both segment counters and repaints the segmented control. */
    public void bindCounts(int allCount, int fcmCount) {
        filterCounts[0].setText(activity.getString(R.string.compact_count, allCount));
        filterCounts[1].setText(activity.getString(R.string.compact_count, fcmCount));
        updateFilterStyle();
    }

    /**
     * Keeps the main action visually stable; connection diagnostics are conveyed through the
     * accessible label rather than changing the palette underneath the user's finger.
     */
    public void updateFab(boolean menuOpen, int contentDescriptionRes) {
        fab.setBackgroundTintList(ColorStateList.valueOf(
                menuOpen ? theme.translucentPrimaryContainer : Color.TRANSPARENT));
        fab.setImageTintList(ColorStateList.valueOf(
                menuOpen ? theme.onPrimaryContainer : theme.onSurfaceVariant));
        fab.setCompatElevation(0f);
        fab.setCompatHoveredFocusedTranslationZ(0f);
        fab.setCompatPressedTranslationZ(0f);
        AppTheme.removeShadow(fab);
        fab.setContentDescription(activity.getString(contentDescriptionRes));
    }

    public void release() {
        main.removeCallbacks(applySearch);
        searchContainer.animate().cancel();
        if (searchWidthAnimator != null) {
            searchWidthAnimator.cancel();
            searchWidthAnimator = null;
        }
    }

    private void applyGlassSurfaces() {
        // A low-alpha Primary Container ties the controls together without a grey cast.
        dock.setCardBackgroundColor(
                AppTheme.translucent(theme.primaryContainer, AppTheme.DOCK_ALPHA));
        dock.setStrokeColor(Color.TRANSPARENT);
        dock.setStrokeWidth(0);
        float segmentRadius = theme.dpF(16);
        dock.setShapeAppearanceModel(dock.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(segmentRadius)
                .build());
        AppTheme.removeShadow(dock);

        fab.setShapeAppearanceModel(fab.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(segmentRadius)
                .build());

        // Search stays a separate centred surface using Primary Container directly, so it keeps
        // dynamic colour without the grey cast of a neutral Surface Container layer.
        searchContainer.setCardBackgroundColor(
                AppTheme.translucent(theme.primaryContainer, AppTheme.GLASS_ALPHA));
        searchContainer.setStrokeColor(Color.TRANSPARENT);
        searchContainer.setStrokeWidth(0);
        searchContainer.setShapeAppearanceModel(searchContainer.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(theme.dpF(24))
                .build());
        AppTheme.removeShadow(searchContainer);

        searchInput.setTextColor(theme.onPrimaryContainer);
        searchInput.setHintTextColor(theme.onPrimaryContainer);
        updateSearchToggleStyle(false);
    }

    private void dispatchSearchQuery() {
        callbacks.onSearchQueryChanged(pendingQuery);
    }

    private void wireSearch() {
        searchToggle.setOnClickListener(v -> toggleSearch());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                pendingQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                main.removeCallbacks(applySearch);
                main.postDelayed(applySearch, SEARCH_DEBOUNCE_MS);
                if (searchExpanded) {
                    updateSearchWidth(s, true);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void wireFilters() {
        FilterMode[] modes = FilterMode.values();
        for (int i = 0; i < filterCards.length; i++) {
            final FilterMode mode = modes[i];
            filterCards[i].setOnClickListener(v -> selectFilter(mode));
        }
        updateFilterStyle();
    }

    private void wireFab() {
        // 统一为本应用的线性描边风格（与 ic_search / ic_status_* 同为 2.2-2.6 白描边 + 代码 tint），
        // 取代平台自带的 android.R.drawable.ic_menu_manage（扳手位图风格、颜色与 dock 不搭）。
        fab.setImageResource(R.drawable.ic_fab_tune);
        fab.setOnClickListener(v -> {
            if (searchExpanded) {
                toggleSearch();
                main.postDelayed(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        callbacks.onFabMenuRequested();
                    }
                }, searchReopenDelayMs);
            } else {
                callbacks.onFabMenuRequested();
            }
        });
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
        filterCards[1].setShapeAppearanceModel(fcmShape.build());
        searchToggleContainer.setShapeAppearanceModel(searchShape.build());
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
