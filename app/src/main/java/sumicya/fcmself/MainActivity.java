package sumicya.fcmself;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import sumicya.fcmself.core.Contract;
import sumicya.fcmself.data.AppCatalog;
import sumicya.fcmself.data.ConfigStore;
import sumicya.fcmself.data.PackageChangeWatcher;
import sumicya.fcmself.data.XposedServiceBridge;
import sumicya.fcmself.health.HookHealthMonitor;
import sumicya.fcmself.model.AppFilter;
import sumicya.fcmself.model.AppInfo;
import sumicya.fcmself.model.FilterMode;
import sumicya.fcmself.ui.AppListAdapter;
import sumicya.fcmself.ui.AppTheme;
import sumicya.fcmself.ui.BottomDock;
import sumicya.fcmself.ui.FabMenuController;
import sumicya.fcmself.ui.FeedbackBar;
import sumicya.fcmself.ui.WindowChrome;
import sumicya.fcmself.util.IceboxUtils;

/**
 * The single screen of the module: it wires the components together and owns nothing else.
 *
 * <p>Scanning, configuration, health polling, theming, the grid, the dock and the FAB menu each
 * live in their own class ({@code data}, {@code health}, {@code model}, {@code ui}); this Activity
 * only reacts to their callbacks and keeps the current filter state.
 */
public class MainActivity extends AppCompatActivity implements
        AppCatalog.Listener,
        XposedServiceBridge.Listener,
        AppListAdapter.Host,
        BottomDock.Callbacks,
        FabMenuController.Host,
        HookHealthMonitor.StatusProvider,
        PackageChangeWatcher.Callbacks {

    private static final String TAG = "MainActivity";
    /** Diagnostics probing touches the package manager; re-check at most this often. */
    private static final long DIAGNOSTICS_CACHE_MS = 15_000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private ConfigStore configStore;
    private AppTheme theme;
    private BottomDock dock;
    private FeedbackBar feedbackBar;
    private FabMenuController fabMenu;
    private HookHealthMonitor healthMonitor;
    private PackageChangeWatcher packageWatcher;
    private AppListAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyHint;

    private List<AppInfo> apps = new ArrayList<>();
    private String searchQuery = "";
    private int spanCount = 3;
    private boolean fabMenuOpen;
    private Boolean cachedDiagnosticsIssue;
    private long diagnosticsCheckedAtMs;
    private View loadingContainer;
    private boolean hasReceivedFirstList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        // Draw under the status bar so the header scrim fills the top gap.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        theme = AppTheme.resolve(this);
        configStore = new ConfigStore(this);

        View header = findViewById(R.id.header_safe);
        recyclerView = findViewById(R.id.recycler_view);
        emptyHint = findViewById(R.id.empty_hint);
        loadingContainer = findViewById(R.id.loading_container);
        WindowChrome.applyHeaderScrim(header, AppTheme.resolveColor(
                this, com.google.android.material.R.attr.colorSurface, Color.WHITE));
        WindowChrome.applyInsets(header, recyclerView, findViewById(R.id.bottom_controls));

        feedbackBar = new FeedbackBar(findViewById(R.id.root), theme);
        dock = new BottomDock(this, theme, this);
        fabMenu = new FabMenuController(this, theme, this);
        healthMonitor = new HookHealthMonitor(this, this);
        packageWatcher = new PackageChangeWatcher(this);

        setUpGrid();
        refreshFabStatus();

        packageWatcher.register(this);
        AppCatalog.setListener(this);
        XposedServiceBridge.attach(this);
        requestIceboxPermission();

        main.post(this::loadApps);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppCatalog.setListener(this);
        // Registration can fail while the framework service is still starting, and returning from
        // background must re-read the authoritative remote configuration; attach() does both.
        XposedServiceBridge.attach(this);
        refreshFabStatus();
    }

    @Override
    protected void onDestroy() {
        AppCatalog.clearListener(this);
        XposedServiceBridge.detach(this);
        packageWatcher.unregister(this);
        healthMonitor.release();
        dock.release();
        feedbackBar.release();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void setUpGrid() {
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        spanCount = Math.max(2, Math.min(8, screenWidthDp / 112));
        recyclerView.setLayoutManager(new GridLayoutManager(this, spanCount));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(32);
        recyclerView.setClipToPadding(false);
        // Prefetch a row ahead while flinging.
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 24);
        recyclerView.setRecycledViewPool(pool);
        GridLayoutManager layoutManager = (GridLayoutManager) recyclerView.getLayoutManager();
        if (layoutManager != null) {
            layoutManager.setInitialPrefetchItemCount(spanCount * 2);
        }
        adapter = new AppListAdapter(this, theme, this);
        recyclerView.setAdapter(adapter);
    }

    private void requestIceboxPermission() {
        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this, new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
            // Icebox is optional; without it frozen apps simply are not woken.
        }
    }

    private void loadApps() {
        AppCatalog.load(this, configStore.allowListSnapshot());
    }

    // region AppCatalog.Listener / XposedServiceBridge.Listener

    @Override
    public boolean isActive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    public void onAppListReady(List<AppInfo> list, boolean initialLoad) {
        hasReceivedFirstList = true;
        apps = list;
        // The scan snapshotted the allow list on a background thread; the service may have
        // connected in the meantime. Re-apply the authoritative list so entries never render as a
        // cross just because the scan won the race.
        syncAllowState();
        if (loadingContainer != null) {
            loadingContainer.setVisibility(View.GONE);
        } else {
            View fallback = findViewById(R.id.progress_bar);
            if (fallback != null) {
                fallback.setVisibility(View.GONE);
            }
        }
        refreshFabStatus();

        if (initialLoad && looksLikeMissingPackageVisibility(list)) {
            new AlertDialog.Builder(this)
                    .setTitle("无法读取应用列表")
                    .setMessage("请在系统设置中允许 fcmself 读取应用列表，然后重新打开本应用。")
                    .setPositiveButton("确定", (dialog, which) -> {
                    })
                    .show();
        }
    }

    private static boolean looksLikeMissingPackageVisibility(List<AppInfo> list) {
        return list.isEmpty()
                || (list.size() == 1 && Contract.PACKAGE_SELF.equals(list.get(0).packageName));
    }

    @Override
    public void onXposedServiceConnected() {
        cachedDiagnosticsIssue = null;
        configStore.load();
        refreshFabStatus();
        syncAllowState();
    }

    @Override
    public void onXposedServiceLost() {
        refreshFabStatus();
    }

    // endregion

    // region PackageChangeWatcher.Callbacks

    @Override
    public void onAllPackagesChanged() {
        AppCatalog.invalidate();
        loadApps();
    }

    @Override
    public void onPackageChanged(String packageName, boolean removed) {
        AppCatalog.refreshPackage(this, packageName, removed, configStore.allowListSnapshot());
    }

    // endregion

    // region AppListAdapter.Host

    @Override
    public boolean isAllowListLoaded() {
        return configStore.isAllowListLoaded();
    }

    @Override
    public int spanCount() {
        return spanCount;
    }

    @Override
    public void onAppToggleRequested(AppInfo info, int position) {
        boolean stored = info.isAllow
                ? disallow(info.packageName)
                : allow(info.packageName);
        if (!stored) {
            // Persisting failed — keep the mark in sync with what is actually stored.
            return;
        }
        info.isAllow = !info.isAllow;
        adapter.notifyStatusChanged(position);
        updateCounts();
        final String packageName = info.packageName;
        final boolean allowed = info.isAllow;
        feedbackBar.showToggle(info, () -> undoToggle(packageName, allowed));
    }

    @Override
    public void onUnsupportedApp(AppInfo info) {
        feedbackBar.showUnsupported(getString(R.string.unsupported_app_message, info.name));
    }

    /**
     * Reverts a toggle and re-filters, so a tile that left a filtered view comes back instead of
     * silently disappearing.
     */
    private void undoToggle(String packageName, boolean allowed) {
        boolean reverted = allowed ? disallow(packageName) : allow(packageName);
        if (!reverted) {
            return;
        }
        for (AppInfo info : apps) {
            if (packageName.equals(info.packageName)) {
                info.isAllow = !allowed;
                break;
            }
        }
        applyFilter();
        updateCounts();
    }

    // endregion

    // region BottomDock.Callbacks

    @Override
    public void onFilterChanged(FilterMode mode) {
        applyFilter();
    }

    @Override
    public void onSearchQueryChanged(String query) {
        searchQuery = query;
        applyFilter();
    }

    @Override
    public void onFabMenuRequested() {
        feedbackBar.hide(true);
        fabMenuOpen = true;
        refreshFabStatus();
        fabMenu.show(dock.fab());
    }

    // endregion

    // region FabMenuController.Host

    @Override
    public boolean isSwitchEnabled(String key) {
        return configStore.getBoolean(key);
    }

    @Override
    public boolean setSwitchEnabled(String key, boolean value) {
        return afterWrite(configStore.setBoolean(key, value));
    }

    @Override
    public void onEnableAllFcm() {
        List<String> packages = new ArrayList<>();
        for (AppInfo info : apps) {
            if (info.includeFcm) {
                packages.add(info.packageName);
            }
        }
        afterWrite(configStore.allowAll(packages));
        syncAllowState();
    }

    @Override
    public void onOpenDiagnostics() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(Contract.PACKAGE_GMS);
        intent.setComponent(new ComponentName(
                Contract.PACKAGE_GMS, Contract.GMS_DIAGNOSTICS_ACTIVITY));
        try {
            startActivity(intent);
        } catch (Throwable e) {
            cachedDiagnosticsIssue = true;
            diagnosticsCheckedAtMs = SystemClock.elapsedRealtime();
            refreshFabStatus();
            new AlertDialog.Builder(this)
                    .setTitle("无法打开 FCM Diagnostics")
                    .setMessage(String.valueOf(e.getMessage()))
                    .show();
        }
    }

    @Override
    public void onHealthPanelOpened(TextView statusView) {
        healthMonitor.request(statusView::setText);
    }

    @Override
    public void onHealthPanelClosed() {
        healthMonitor.stop();
    }

    @Override
    public void onMenuDismissed() {
        healthMonitor.stop();
        fabMenuOpen = false;
        refreshFabStatus();
    }

    // endregion

    // region HookHealthMonitor.StatusProvider

    @Override
    public boolean isServiceConnected() {
        return XposedServiceBridge.isConnected();
    }

    @Override
    public boolean isConfigReady() {
        return configStore.isAllowListLoaded() && !configStore.hasError();
    }

    // endregion

    /** Re-applies the authoritative allow list onto the already built list and repaints. */
    private void syncAllowState() {
        for (AppInfo info : apps) {
            info.isAllow = configStore.isAllowed(info.packageName);
        }
        applyFilter();
        updateCounts();
    }

    private void applyFilter() {
        if (adapter == null) {
            return;
        }
        // 未完成首次扫描前不展示空态，避免“没有匹配的应用”闪现
        if (!hasReceivedFirstList) {
            emptyHint.setVisibility(View.GONE);
            recyclerView.setVisibility(View.GONE);
            if (loadingContainer != null) {
                loadingContainer.setVisibility(View.VISIBLE);
            }
            return;
        }
        List<AppInfo> filtered = AppFilter.apply(apps, dock.filterMode(), searchQuery);
        adapter.replaceList(filtered);
        boolean empty = filtered.isEmpty();
        emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateCounts() {
        dock.bindCounts(apps.size(), AppFilter.countFcm(apps));
    }

    private boolean allow(String packageName) {
        return afterWrite(configStore.allow(packageName));
    }

    private boolean disallow(String packageName) {
        return afterWrite(configStore.disallow(packageName));
    }

    private boolean afterWrite(boolean stored) {
        refreshFabStatus();
        if (!stored) {
            new AlertDialog.Builder(this)
                    .setTitle("更新配置文件失败")
                    .setMessage(configStore.lastErrorMessage())
                    .show();
        }
        return stored;
    }

    private void refreshFabStatus() {
        if (dock == null) {
            return;
        }
        final int description;
        if (!XposedServiceBridge.isConnected()) {
            description = R.string.fab_status_disconnected;
        } else if (configStore.hasError() || hasDiagnosticsIssueCached()) {
            description = R.string.fab_status_error;
        } else {
            description = R.string.fab_status_connected;
        }
        dock.updateFab(fabMenuOpen, description);
    }

    private boolean hasDiagnosticsIssueCached() {
        // Cache age must not depend on wall-clock changes (network time, manual adjustment).
        long now = SystemClock.elapsedRealtime();
        if (cachedDiagnosticsIssue != null && (now - diagnosticsCheckedAtMs) < DIAGNOSTICS_CACHE_MS) {
            return cachedDiagnosticsIssue;
        }
        boolean issue = hasDiagnosticsIssue();
        cachedDiagnosticsIssue = issue;
        diagnosticsCheckedAtMs = now;
        return issue;
    }

    private boolean hasDiagnosticsIssue() {
        try {
            PackageManager packageManager = getPackageManager();
            try {
                packageManager.getPackageInfo(Contract.PACKAGE_GMS, 0);
            } catch (PackageManager.NameNotFoundException e) {
                return true;
            }
            Intent diagnostics = new Intent();
            diagnostics.setComponent(new ComponentName(
                    Contract.PACKAGE_GMS, Contract.GMS_DIAGNOSTICS_ACTIVITY));
            if (diagnostics.resolveActivity(packageManager) == null) {
                return true;
            }
            String code = configStore.readDiagnosticString(Contract.KEY_LAST_FCM_ERROR_CODE);
            return code != null && !code.isEmpty() && !"0".equals(code);
        } catch (Throwable t) {
            Log.w(TAG, "hasDiagnosticsIssue: " + t);
            return true;
        }
    }
}
