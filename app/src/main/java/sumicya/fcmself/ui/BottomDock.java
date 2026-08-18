package sumicya.fcmself.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;

import sumicya.fcmself.R;
import sumicya.fcmself.model.FilterMode;

/**
 * Simplified bottom control rail (scheme A):
 * filter segments with counters, a fixed-width search field that fades in/out,
 * and the menu FAB. No width animation, no segment corner merging.
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

        applySurfaces();
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

    /** Updates both segment counters and repaints the segmented control. */
    public void bindCounts(int allCount, int fcmCount) {
        filterCounts[0].setText(activity.getString(R.string.compact_count, allCount));
        filterCounts[1].setText(activity.getString(R.string.compact_count, fcmCount));
        updateFilterStyle();
    }

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
    }

    private void applySurfaces() {
        float radius = theme.dpF(16);
        dock.setCardBackgroundColor(
                AppTheme.translucent(theme.primaryContainer, AppTheme.DOCK_ALPHA));
        dock.setStrokeColor(Color.TRANSPARENT);
        dock.setStrokeWidth(0);
        dock.setShapeAppearanceModel(dock.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(radius)
                .build());
        AppTheme.removeShadow(dock);

        fab.setShapeAppearanceModel(fab.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(radius)
                .build());

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
        fab.setImageResource(R.drawable.ic_fab_tune);
        fab.setOnClickListener(v -> {
            if (searchExpanded) {
                toggleSearch();
                main.postDelayed(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        callbacks.onFabMenuRequested();
                    }
                }, exitMs);
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
        callbacks.onFilterChanged(mode);
    }

    private void updateFilterStyle() {
        int selectedIndex = filterMode.ordinal();
        float radius = theme.dpF(16);
        for (int i = 0; i < filterCards.length; i++) {
            boolean selected = i == selectedIndex;
            int content = selected ? theme.onPrimaryContainer : theme.onSurfaceVariant;
            theme.applyPrimarySurface(filterCards[i], selected, Color.TRANSPARENT);
            filterCards[i].setShapeAppearanceModel(filterCards[i].getShapeAppearanceModel()
                    .toBuilder()
                    .setAllCornerSizes(radius)
                    .build());
            filterLabels[i].setTextColor(content);
            filterCounts[i].setTextColor(content);
            filterCards[i].setContentDescription(
                    filterLabels[i].getText() + "，" + filterCounts[i].getText()
                            + (selected ? "，当前筛选" : ""));
        }
        searchToggleContainer.setShapeAppearanceModel(
                searchToggleContainer.getShapeAppearanceModel()
                        .toBuilder()
                        .setAllCornerSizes(radius)
                        .build());
    }

    private void updateSearchToggleStyle(boolean active) {
        theme.applyPrimarySurface(searchToggleContainer, active, Color.TRANSPARENT);
        searchToggle.setImageResource(active ? R.drawable.ic_status_close : R.drawable.ic_search);
        searchToggle.setImageTintList(ColorStateList.valueOf(
                active ? theme.onPrimaryContainer : theme.onSurfaceVariant));
    }

    private void toggleSearch() {
        InputMethodManager keyboard =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        searchContainer.animate().cancel();
        if (!searchExpanded) {
            searchExpanded = true;
            updateSearchToggleStyle(true);
            searchContainer.setVisibility(View.VISIBLE);
            searchContainer.setAlpha(0f);
            searchContainer.animate()
                    .alpha(1f)
                    .setDuration(enterMs)
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
                    .setDuration(exitMs)
                    .withEndAction(() -> {
                        searchContainer.setVisibility(View.GONE);
                        searchContainer.setAlpha(1f);
                    })
                    .start();
        }
    }
}
