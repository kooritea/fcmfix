package sumicya.fcmself;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import sumicya.fcmself.util.FcmReceiverDetector;
import sumicya.fcmself.util.IceboxUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String UPDATE_CONFIG_ACTION = "sumicya.fcmself.update.config";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fcmself-apps");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    /** Parallel icon decode — does not block the app-list scan thread. */
    private static final ExecutorService ICON_EXEC = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "fcmself-icon");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private static final Object PAYLOAD_STATUS = new Object();
    private static final Object PAYLOAD_ICON = new Object();

    private Drawable defaultAppIcon;

    private enum FilterMode { ALL, FCM_ONLY, ENABLED }

    private AppListAdapter appListAdapter;
    private static volatile XposedService xposedService;
    /**
     * XposedServiceHelper keeps a single static listener and consumes its binder cache on the
     * first registration, so the listener must be installed exactly once per process. Registering
     * again from a recreated Activity would never receive a second onServiceBind callback.
     */
    private static final AtomicBoolean SERVICE_LISTENER_REGISTERED = new AtomicBoolean(false);
    /** Activity currently able to render service updates; weak so a dead Activity is not leaked. */
    private static volatile WeakReference<MainActivity> activeInstance = new WeakReference<>(null);
    private FloatingActionButton mainFab;
    private TextView statsView;
    private View headerSafe;
    private RecyclerView recyclerView;
    private ChipGroup filterChips;
    private boolean lastConfigError = false;
    private FilterMode filterMode = FilterMode.ALL;

    private int colorConnected;
    private int colorError;
    private int colorDisconnected;
    private int colorPrimary;
    /** Precomputed once so item rebinds and toggles do not allocate a ColorStateList each time. */
    private ColorStateList tintPrimary;
    private ColorStateList tintError;
    private ColorStateList tintDisconnected;

    private Boolean cachedDiagIssue;
    private long diagCacheAtMs;
    private static final long DIAG_CACHE_MS = 15_000L;

    private List<AppInfo> fullAppList = new ArrayList<>();

    private final Set<String> allowList = new HashSet<>();
    /** True once the allowList has been read from the remote config at least once. */
    private boolean allowListLoaded = false;
    private final JSONObject config = new JSONObject();

    private SharedPreferences getRemotePreferencesOrNull() {
        if (xposedService == null) {
            return null;
        }
        try {
            return xposedService.getRemotePreferences("config");
        } catch (Throwable e) {
            Log.e(TAG, "getRemotePreferences: " + e);
            lastConfigError = true;
            return null;
        }
    }

    /**
     * Installs the process-wide service listener (only ever once) and immediately syncs this
     * Activity against a service that may already be bound from a previous Activity instance.
     */
    private void initXposedService() {
        activeInstance = new WeakReference<>(this);
        if (SERVICE_LISTENER_REGISTERED.compareAndSet(false, true)) {
            try {
                XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                    @Override
                    public void onServiceBind(@NonNull XposedService service) {
                        xposedService = service;
                        MAIN_HANDLER.post(() -> {
                            MainActivity act = activeInstance.get();
                            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                                act.onXposedServiceConnected();
                            }
                        });
                    }

                    @Override
                    public void onServiceDied(@NonNull XposedService service) {
                        if (xposedService == service) {
                            xposedService = null;
                        }
                        MAIN_HANDLER.post(() -> {
                            MainActivity act = activeInstance.get();
                            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                                act.refreshFabStatus();
                            }
                        });
                    }
                });
            } catch (Throwable e) {
                SERVICE_LISTENER_REGISTERED.set(false);
                Log.e(TAG, "initXposedService: " + e);
            }
        } else if (xposedService != null) {
            // Service was already bound before this Activity existed — no new callback is coming.
            onXposedServiceConnected();
        }
    }

    /** Called on the main thread whenever the remote config becomes readable. */
    private void onXposedServiceConnected() {
        lastConfigError = false;
        cachedDiagIssue = null;
        loadConfigFromRemotePreferences();
        refreshFabStatus();
        syncAllowStateIntoList();
    }

    /** Re-applies the current allowList onto the already-built app list. */
    private void syncAllowStateIntoList() {
        if (fullAppList.isEmpty()) {
            return;
        }
        for (AppInfo info : fullAppList) {
            info.isAllow = allowList.contains(info.packageName);
        }
        if (appListAdapter != null) {
            applyFilter();
        }
        updateStats();
    }

    private void ensureDefaultConfigValues() {
        try {
            if (!this.config.has("allowList")) {
                this.config.put("allowList", new JSONArray());
            }
            if (!this.config.has("disableAutoCleanNotification")) {
                this.config.put("disableAutoCleanNotification", false);
            }
            if (!this.config.has("includeIceBoxDisableApp")) {
                this.config.put("includeIceBoxDisableApp", false);
            }
            if (!this.config.has("noResponseNotification")) {
                this.config.put("noResponseNotification", false);
            }
        } catch (JSONException e) {
            Log.e(TAG, "ensureDefaultConfig: " + e);
        }
    }

    private void loadConfigFromRemotePreferences() {
        ensureDefaultConfigValues();
        SharedPreferences pref = getRemotePreferencesOrNull();
        if (pref == null) {
            return;
        }
        try {
            // Read every value before changing live state. Remote/binder preferences can throw a
            // type or transport error; a partial read must not clear checks or mix config versions.
            Set<String> remote = pref.getStringSet("allowList", null);
            boolean initialized = pref.getBoolean("init", false);
            boolean disableAutoClean = pref.getBoolean("disableAutoCleanNotification", false);
            boolean includeIceBox = pref.getBoolean("includeIceBoxDisableApp", false);
            boolean noResponseNotification = pref.getBoolean("noResponseNotification", false);

            Set<String> newAllowList = null;
            if (remote != null) {
                newAllowList = new HashSet<>(remote);
            } else if (initialized) {
                // Config exists but holds no entries — an empty allow list is authoritative.
                newAllowList = new HashSet<>();
            } else {
                // Fresh install or a temporarily unavailable value: preserve any known snapshot.
                Log.i(TAG, "loadRemoteConfig: config not initialised yet, keeping current allowList");
            }

            if (newAllowList != null) {
                this.allowList.clear();
                this.allowList.addAll(newAllowList);
                allowListLoaded = true;
            }
            this.config.put("allowList", new JSONArray(this.allowList));
            this.config.put("disableAutoCleanNotification", disableAutoClean);
            this.config.put("includeIceBoxDisableApp", includeIceBox);
            this.config.put("noResponseNotification", noResponseNotification);
            lastConfigError = false;
        } catch (Throwable e) {
            Log.e(TAG, "loadRemoteConfig: " + e);
            lastConfigError = true;
        }
    }

    private void refreshFabStatus() {
        if (mainFab == null) {
            return;
        }
        int color;
        if (xposedService == null) {
            color = colorDisconnected;
        } else if (lastConfigError || hasFcmDiagnosticsIssueCached()) {
            color = colorError;
        } else {
            color = colorConnected;
        }
        mainFab.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    private boolean hasFcmDiagnosticsIssueCached() {
        // Cache age must not depend on wall-clock changes (network time, manual adjustment).
        long now = SystemClock.elapsedRealtime();
        if (cachedDiagIssue != null && (now - diagCacheAtMs) < DIAG_CACHE_MS) {
            return cachedDiagIssue;
        }
        boolean issue = hasFcmDiagnosticsIssue();
        cachedDiagIssue = issue;
        diagCacheAtMs = now;
        return issue;
    }

    private boolean hasFcmDiagnosticsIssue() {
        try {
            PackageManager pm = getPackageManager();
            try {
                pm.getPackageInfo("com.google.android.gms", 0);
            } catch (PackageManager.NameNotFoundException e) {
                return true;
            }
            Intent diag = new Intent();
            diag.setComponent(new ComponentName(
                    "com.google.android.gms",
                    "com.google.android.gms.gcm.GcmDiagnostics"));
            if (diag.resolveActivity(pm) == null) {
                return true;
            }
            SharedPreferences pref = getRemotePreferencesOrNull();
            if (pref != null) {
                String code = pref.getString("lastFcmErrorCode", "");
                if (code != null && !code.isEmpty() && !"0".equals(code)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hasFcmDiagnosticsIssue: " + t);
            return true;
        }
        return false;
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                return ContextCompat.getColor(this, tv.resourceId);
            }
            return tv.data;
        }
        return fallback;
    }

    private void applyHeaderScrim() {
        if (headerSafe == null) {
            return;
        }
        try {
            int surface = resolveThemeColor(android.R.attr.colorBackground, Color.WHITE);
            int r = Color.red(surface);
            int g = Color.green(surface);
            int b = Color.blue(surface);

            int[] colors = new int[]{
                    Color.argb(0xF2, r, g, b),
                    Color.argb(0xE6, r, g, b),
                    Color.argb(0xB3, r, g, b),
                    Color.argb(0x59, r, g, b),
                    Color.argb(0x00, r, g, b)
            };
            GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
            gd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
            gd.setShape(GradientDrawable.RECTANGLE);
            headerSafe.setBackground(gd);
            headerSafe.setElevation(0f);
            headerSafe.setOutlineProvider(null);
            headerSafe.setClipToOutline(false);
        } catch (Throwable t) {
            headerSafe.setAlpha(0.96f);
        }
    }

    private void updateStats() {
        if (statsView == null) {
            return;
        }
        int enabled = 0;
        int totalFcm = 0;
        for (AppInfo info : fullAppList) {
            if (info.includeFcm) {
                totalFcm++;
                if (info.isAllow) {
                    enabled++;
                }
            }
        }
        statsView.setText("已启用 " + enabled + " / FCM " + totalFcm);
    }

    /**
     * Shows a short confirmation for a toggle and lets the user undo it. The undo re-applies the
     * inverse persistence and re-filters, so a tile that left a filtered view comes back instead
     * of silently disappearing. The leading icon and accent color follow the action (check/primary
     * when enabling, cross/error when disabling).
     */
    private void showToggleFeedback(AppInfo appInfo) {
        final boolean nowAllow = appInfo.isAllow;
        final String pkg = appInfo.packageName;
        final String name = appInfo.name;
        final int accent = nowAllow ? colorPrimary : colorError;
        String text = (nowAllow ? "已启用 FCM 唤醒：" : "已停用 FCM 唤醒：") + name;

        Snackbar snackbar = Snackbar.make(recyclerView, text, Snackbar.LENGTH_LONG);
        // Leading icon echoes the tile's status mark so the confirmation reads at a glance.
        snackbar.setLeadingIcon(nowAllow
                ? R.drawable.ic_status_check
                : R.drawable.ic_status_close);
        snackbar.setIconTint(accent);
        snackbar.setActionTextColor(accent);
        snackbar.setTextMaxLines(2);
        // Slightly longer than the default so the undo window feels comfortable.
        snackbar.setDuration(4_000);
        snackbar.setAction("撤销", v -> {
            boolean reverted = nowAllow
                    ? deleteAppInAllowList(pkg)
                    : addAppInAllowList(pkg);
            if (!reverted) {
                return;
            }
            for (AppInfo info : fullAppList) {
                if (pkg.equals(info.packageName)) {
                    info.isAllow = !nowAllow;
                    break;
                }
            }
            applyFilter();
            updateStats();
        });
        snackbar.show();
    }

    private void applyFilter() {
        if (appListAdapter == null) {
            return;
        }
        List<AppInfo> filtered = new ArrayList<>();
        for (AppInfo info : fullAppList) {
            switch (filterMode) {
                case FCM_ONLY:
                    if (info.includeFcm) {
                        filtered.add(info);
                    }
                    break;
                case ENABLED:
                    if (info.isAllow) {
                        filtered.add(info);
                    }
                    break;
                case ALL:
                default:
                    filtered.add(info);
                    break;
            }
        }
        appListAdapter.replaceList(filtered);
    }

    private void setupFilterChips() {
        if (filterChips == null) {
            return;
        }
        filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            int id = checkedIds.get(0);
            if (id == R.id.chip_fcm) {
                filterMode = FilterMode.FCM_ONLY;
            } else if (id == R.id.chip_enabled) {
                filterMode = FilterMode.ENABLED;
            } else {
                filterMode = FilterMode.ALL;
            }
            applyFilter();
        });
    }

    private void showFabMenu() {
        if (mainFab == null) {
            return;
        }
        Context themed = new ContextThemeWrapper(this, R.style.RoundedPopupMenu);
        PopupMenu popup = new PopupMenu(themed, mainFab);
        popup.getMenu().add(0, 1, 0, "阻止应用停止时自动清除通知").setCheckable(true);
        popup.getMenu().add(0, 2, 1, "允许唤醒被冰箱冻结的应用").setCheckable(true);
        popup.getMenu().add(0, 3, 2, "目标无响应时代发提示通知").setCheckable(true);
        popup.getMenu().add(0, 4, 3, "全选包含 FCM 的应用");
        popup.getMenu().add(0, 5, 4, "打开 FCM Diagnostics");

        try {
            popup.getMenu().findItem(1).setChecked(config.optBoolean("disableAutoCleanNotification", false));
            popup.getMenu().findItem(2).setChecked(config.optBoolean("includeIceBoxDisableApp", false));
            popup.getMenu().findItem(3).setChecked(config.optBoolean("noResponseNotification", false));
        } catch (Throwable ignored) {
        }

        popup.setOnMenuItemClickListener(this::onFabMenuItemClick);
        popup.show();
    }

    private void updateBooleanConfig(String key, boolean value) throws JSONException {
        boolean previous = config.optBoolean(key, false);
        config.put(key, value);
        if (!updateConfig()) {
            // Keep the in-memory menu state aligned with the last successfully persisted value.
            // Otherwise a later, unrelated save would silently persist this failed toggle.
            config.put(key, previous);
        }
    }

    private boolean onFabMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        try {
            if (id == 1) {
                updateBooleanConfig("disableAutoCleanNotification", !item.isChecked());
                return true;
            }
            if (id == 2) {
                updateBooleanConfig("includeIceBoxDisableApp", !item.isChecked());
                return true;
            }
            if (id == 3) {
                updateBooleanConfig("noResponseNotification", !item.isChecked());
                return true;
            }
            if (id == 4) {
                Set<String> before = new HashSet<>(allowList);
                for (AppInfo appInfo : fullAppList) {
                    if (appInfo.includeFcm) {
                        allowList.add(appInfo.packageName);
                    }
                }
                if (!updateConfig()) {
                    allowList.clear();
                    allowList.addAll(before);
                }
                syncAllowStateIntoList();
                return true;
            }
            if (id == 5) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setPackage("com.google.android.gms");
                intent.setComponent(new ComponentName(
                        "com.google.android.gms",
                        "com.google.android.gms.gcm.GcmDiagnostics"));
                try {
                    startActivity(intent);
                } catch (Throwable e) {
                    lastConfigError = true;
                    cachedDiagIssue = true;
                    refreshFabStatus();
                    new AlertDialog.Builder(this)
                            .setTitle("无法打开 FCM Diagnostics")
                            .setMessage(String.valueOf(e.getMessage()))
                            .show();
                }
                return true;
            }
        } catch (JSONException e) {
            Log.e(TAG, "onFabMenuItemClick: " + e);
        }
        return false;
    }

    private static class AppInfo {
        final String name;
        final String packageName;
        /** Lazily loaded; null until first bind requests it. */
        volatile Drawable icon;
        boolean isAllow;
        final boolean includeFcm;
        volatile boolean iconLoading;

        AppInfo(String name, String packageName, Drawable icon, boolean includeFcm, boolean isAllow) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.includeFcm = includeFcm;
            this.isAllow = isAllow;
        }
    }

    private class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
        private final List<AppInfo> mAppList = new ArrayList<>();

        class ViewHolder extends RecyclerView.ViewHolder {
            final View appView;
            final ImageView icon;
            final TextView name;
            final TextView appPkg;
            final ImageView status;

            ViewHolder(View view) {
                super(view);
                appView = view;
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                appPkg = view.findViewById(R.id.app_pkg);
                status = view.findViewById(R.id.status);
            }
        }

        AppListAdapter() {
            setHasStableIds(true);
        }

        void replaceList(List<AppInfo> list) {
            // DiffUtil animates add/remove/move so toggling (especially in a filtered view) eases
            // items in/out instead of them vanishing abruptly or the whole grid flashing.
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return mAppList.size();
                }

                @Override
                public int getNewListSize() {
                    return list.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return mAppList.get(oldItemPosition).packageName
                            .equals(list.get(newItemPosition).packageName);
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    AppInfo a = mAppList.get(oldItemPosition);
                    AppInfo b = list.get(newItemPosition);
                    return a.isAllow == b.isAllow && a.includeFcm == b.includeFcm;
                }
            }, true);
            mAppList.clear();
            mAppList.addAll(list);
            result.dispatchUpdatesTo(this);
        }

        @Override
        public long getItemId(int position) {
            // RecyclerView stable IDs are 64-bit; String.hashCode() needlessly limits package IDs
            // to 32 bits and can make colliding package names reuse the wrong view state.
            String packageName = mAppList.get(position).packageName;
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < packageName.length(); i++) {
                hash ^= packageName.charAt(i);
                hash *= 0x100000001b3L;
            }
            return hash;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.app_item, parent, false);
            final ViewHolder holder = new ViewHolder(view);
            holder.appView.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                AppInfo appInfo = mAppList.get(position);
                boolean ok = appInfo.isAllow
                        ? deleteAppInAllowList(appInfo.packageName)
                        : addAppInAllowList(appInfo.packageName);
                if (!ok) {
                    // Persisting failed — keep the mark in sync with what is actually stored.
                    return;
                }
                appInfo.isAllow = !appInfo.isAllow;
                if (filterMode == FilterMode.ENABLED && !appInfo.isAllow) {
                    applyFilter();
                } else {
                    notifyItemChanged(position, PAYLOAD_STATUS);
                }
                updateStats();
                showToggleFeedback(appInfo);
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo appInfo = mAppList.get(position);
            holder.name.setText(appInfo.name);
            holder.appPkg.setText(appInfo.packageName);
            bindStatus(holder, appInfo);
            bindIcon(holder, appInfo);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position);
                return;
            }
            AppInfo appInfo = mAppList.get(position);
            holder.icon.setTag(appInfo.packageName);
            for (Object payload : payloads) {
                if (payload == PAYLOAD_ICON) {
                    if (appInfo.icon != null
                            && appInfo.packageName.equals(holder.icon.getTag())) {
                        holder.icon.setImageDrawable(appInfo.icon);
                    }
                } else if (payload == PAYLOAD_STATUS) {
                    bindStatus(holder, appInfo);
                }
            }
        }

        private void bindIcon(ViewHolder holder, AppInfo appInfo) {
            // Tag guards against recycled view showing wrong icon
            holder.icon.setTag(appInfo.packageName);
            Drawable cached = appInfo.icon;
            if (cached != null) {
                holder.icon.setImageDrawable(cached);
                return;
            }
            holder.icon.setImageDrawable(defaultAppIcon != null
                    ? defaultAppIcon
                    : getPackageManager().getDefaultActivityIcon());
            if (appInfo.iconLoading) {
                return;
            }
            appInfo.iconLoading = true;
            final String pkg = appInfo.packageName;
            ICON_EXEC.execute(() -> {
                Drawable d;
                try {
                    d = getPackageManager().getApplicationIcon(pkg);
                } catch (Throwable t) {
                    d = defaultAppIcon;
                }
                appInfo.icon = d;
                appInfo.iconLoading = false;
                MAIN_HANDLER.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    // Find current index (list may have been filtered)
                    for (int i = 0, n = mAppList.size(); i < n; i++) {
                        if (pkg.equals(mAppList.get(i).packageName)) {
                            notifyItemChanged(i, PAYLOAD_ICON);
                            break;
                        }
                    }
                });
            });
        }

        private void bindStatus(ViewHolder holder, AppInfo appInfo) {
            // Vector marks are tinted so the icon follows the theme instead of being re-drawn as
            // an emoji glyph by the OEM font on the device.
            if (appInfo.includeFcm) {
                if (appInfo.isAllow) {
                    holder.status.setImageResource(R.drawable.ic_status_check);
                    holder.status.setImageTintList(tintPrimary);
                } else if (!allowListLoaded) {
                    // Config not read yet (service still binding after a cold start): "not allowed"
                    // is not known to be true, so show the neutral marker instead of a false ✗.
                    holder.status.setImageResource(R.drawable.ic_status_dash);
                    holder.status.setImageTintList(tintDisconnected);
                } else {
                    holder.status.setImageResource(R.drawable.ic_status_close);
                    holder.status.setImageTintList(tintError);
                }
            } else {
                holder.status.setImageResource(R.drawable.ic_status_dash);
                holder.status.setImageTintList(tintDisconnected);
            }
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            super.onViewRecycled(holder);
            holder.icon.setTag(null);
            holder.icon.setImageDrawable(defaultAppIcon);
        }

        @Override
        public int getItemCount() {
            return mAppList.size();
        }
    }

    private static boolean hasFcmComponent(ActivityInfo[] components) {
        if (components == null) {
            return false;
        }
        for (ActivityInfo c : components) {
            if (c != null && c.name != null && FcmReceiverDetector.isFcmComponent(c.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFcmComponent(ServiceInfo[] components) {
        if (components == null) {
            return false;
        }
        for (ServiceInfo c : components) {
            if (c != null && c.name != null && FcmReceiverDetector.isFcmComponent(c.name)) {
                return true;
            }
        }
        return false;
    }

    private List<AppInfo> buildAppList(Set<String> allowListSet) {
        List<AppInfo> allow = new ArrayList<>();
        List<AppInfo> notAllow = new ArrayList<>();
        List<AppInfo> noFcm = new ArrayList<>();
        PackageManager pm = getPackageManager();

        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(
                    PackageManager.GET_RECEIVERS
                            | PackageManager.GET_SERVICES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Throwable t) {
            Log.e(TAG, "getInstalledPackages: " + t);
            return new ArrayList<>();
        }

        for (PackageInfo packageInfo : packages) {
            if (packageInfo.applicationInfo == null) {
                continue;
            }
            // Some apps wire FCM through a service (FirebaseMessagingService subclass) with an
            // oddly named or missing receiver, so inspect both receivers and services.
            boolean hasFcm = hasFcmComponent(packageInfo.receivers)
                    || hasFcmComponent(packageInfo.services);

            String label;
            try {
                label = packageInfo.applicationInfo.loadLabel(pm).toString();
            } catch (Throwable t) {
                label = packageInfo.packageName;
            }
            // Icon loaded lazily on bind — scanning hundreds of packages stays fast
            boolean isAllow = allowListSet.contains(packageInfo.packageName);
            AppInfo info = new AppInfo(label, packageInfo.packageName, null, hasFcm, isAllow);
            if (isAllow) {
                allow.add(info);
            } else if (hasFcm) {
                notAllow.add(info);
            } else {
                noFcm.add(info);
            }
        }

        final Collator collator = Collator.getInstance(Locale.getDefault());
        Comparator<AppInfo> byName = (a, b) -> collator.compare(a.name, b.name);
        allow.sort(byName);
        notAllow.sort(byName);
        noFcm.sort(byName);
        allow.addAll(notAllow);
        allow.addAll(noFcm);
        return allow;
    }

    private void loadAppListAsync() {
        // Snapshot on the main thread — HashSet is not safe to read while another thread mutates it.
        final Set<String> allowSnapshot = new HashSet<>(allowList);
        BG.execute(() -> {
            List<AppInfo> list = buildAppList(allowSnapshot);
            MAIN_HANDLER.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                fullAppList = list;
                // The scan snapshotted allowList on a background thread; the service may have
                // connected (or reconnected) in the meantime. Re-apply the authoritative list so
                // entries never render as ✗ just because the scan won the race.
                for (AppInfo info : fullAppList) {
                    info.isAllow = allowList.contains(info.packageName);
                }
                if (appListAdapter == null) {
                    appListAdapter = new AppListAdapter();
                    recyclerView.setAdapter(appListAdapter);
                }
                applyFilter();
                View loading = findViewById(R.id.loading_container);
                if (loading != null) {
                    loading.setVisibility(View.GONE);
                } else {
                    findViewById(R.id.progress_bar).setVisibility(View.GONE);
                }
                recyclerView.setVisibility(View.VISIBLE);
                updateStats();
                refreshFabStatus();

                if (list.isEmpty()
                        || (list.size() == 1 && "sumicya.fcmself".equals(list.get(0).packageName))) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("无法读取应用列表")
                            .setMessage("请在系统设置中允许 fcmself 读取应用列表，然后重新打开本应用。")
                            .setPositiveButton("确定", (dialog, which) -> {})
                            .show();
                }
            });
        });
    }


    /**
     * Header extends into the status-bar region (fills the former empty strip).
     * Recycler top padding tracks real header height so content is not clipped.
     */
    private void setupEdgeToEdgeInsets() {
        if (headerSafe == null) {
            return;
        }
        final int basePadStart = headerSafe.getPaddingStart();
        final int basePadEnd = headerSafe.getPaddingEnd();
        final int basePadBottom = headerSafe.getPaddingBottom();
        final int extraTop = (int) (8 * getResources().getDisplayMetrics().density + 0.5f);

        ViewCompat.setOnApplyWindowInsetsListener(headerSafe, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPaddingRelative(basePadStart, bars.top + extraTop, basePadEnd, basePadBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(headerSafe);

        headerSafe.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (recyclerView == null) {
                return;
            }
            int h = v.getHeight();
            if (h <= 0) {
                return;
            }
            int pl = recyclerView.getPaddingLeft();
            int pr = recyclerView.getPaddingRight();
            int pb = recyclerView.getPaddingBottom();
            if (recyclerView.getPaddingTop() != h) {
                recyclerView.setPadding(pl, h, pr, pb);
            }
        });

        if (mainFab != null) {
            final int fabEnd = (int) (36 * getResources().getDisplayMetrics().density + 0.5f);
            final int fabBottom = (int) (24 * getResources().getDisplayMetrics().density + 0.5f);
            ViewCompat.setOnApplyWindowInsetsListener(mainFab, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                lp.rightMargin = fabEnd + bars.right;
                lp.bottomMargin = fabBottom + bars.bottom;
                v.setLayoutParams(lp);
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(mainFab);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        // Draw under status bar so the header scrim fills the top gap
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        colorConnected = ContextCompat.getColor(this, R.color.status_connected);
        colorError = ContextCompat.getColor(this, R.color.status_error);
        colorDisconnected = ContextCompat.getColor(this, R.color.status_disconnected);
        colorPrimary = resolveThemeColor(android.R.attr.colorPrimary, colorConnected);
        tintPrimary = ColorStateList.valueOf(colorPrimary);
        tintError = ColorStateList.valueOf(colorError);
        tintDisconnected = ColorStateList.valueOf(colorDisconnected);

        headerSafe = findViewById(R.id.header_safe);
        statsView = findViewById(R.id.stats);
        mainFab = findViewById(R.id.main_fab);
        filterChips = findViewById(R.id.filter_chips);
        applyHeaderScrim();
        setupFilterChips();
        setupEdgeToEdgeInsets();

        try {
            mainFab.setImageResource(android.R.drawable.ic_menu_manage);
        } catch (Throwable ignored) {
        }
        mainFab.setOnClickListener(v -> showFabMenu());
        refreshFabStatus();

        recyclerView = findViewById(R.id.recycler_view);
        defaultAppIcon = getPackageManager().getDefaultActivityIcon();
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(32);
        recyclerView.setDrawingCacheEnabled(false);
        recyclerView.setClipToPadding(false);
        // Prefetch a row ahead while flinging
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 24);
        recyclerView.setRecycledViewPool(pool);
        GridLayoutManager glm = (GridLayoutManager) recyclerView.getLayoutManager();
        if (glm != null) {
            glm.setInitialPrefetchItemCount(6);
        }

        initXposedService();

        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }

        MAIN_HANDLER.post(this::loadAppListAsync);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = new WeakReference<>(this);
        if (!SERVICE_LISTENER_REGISTERED.get()) {
            // Registration can fail while the framework service is still starting. Do not require
            // an Activity recreation before trying again.
            initXposedService();
        }
        // Returning from background (e.g. the task was swiped away and relaunched): the remote
        // config is the source of truth, so re-read it before repainting the check marks.
        if (xposedService != null) {
            loadConfigFromRemotePreferences();
            syncAllowStateIntoList();
        }
        refreshFabStatus();
    }

    @Override
    protected void onDestroy() {
        if (activeInstance.get() == this) {
            activeInstance = new WeakReference<>(null);
        }
        super.onDestroy();
    }

    /** @return true when the change was persisted; on false the in-memory list is rolled back. */
    private boolean addAppInAllowList(String packageName) {
        if (!this.allowList.add(packageName)) {
            return true;
        }
        if (this.updateConfig()) {
            return true;
        }
        this.allowList.remove(packageName);
        return false;
    }

    /** @return true when the change was persisted; on false the in-memory list is rolled back. */
    private boolean deleteAppInAllowList(String packageName) {
        if (!this.allowList.remove(packageName)) {
            return true;
        }
        if (this.updateConfig()) {
            return true;
        }
        this.allowList.add(packageName);
        return false;
    }

    private boolean updateConfig() {
        try {
            SharedPreferences pref = getRemotePreferencesOrNull();
            if (pref == null) {
                throw new IllegalStateException("XposedService 未连接，无法写入远程配置");
            }
            this.config.put("allowList", new JSONArray(this.allowList));
            boolean saved = pref.edit()
                    .putBoolean("init", true)
                    .putStringSet("allowList", new HashSet<>(this.allowList))
                    .putBoolean("disableAutoCleanNotification",
                            this.config.getBoolean("disableAutoCleanNotification"))
                    .putBoolean("includeIceBoxDisableApp",
                            this.config.getBoolean("includeIceBoxDisableApp"))
                    .putBoolean("noResponseNotification",
                            this.config.getBoolean("noResponseNotification"))
                    .commit();
            if (!saved) {
                throw new IllegalStateException("配置写入失败");
            }
            lastConfigError = false;
            allowListLoaded = true;
            this.sendBroadcast(new Intent(UPDATE_CONFIG_ACTION));
            refreshFabStatus();
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "updateConfig: " + e);
            lastConfigError = true;
            refreshFabStatus();
            new AlertDialog.Builder(this).setTitle("更新配置文件失败").setMessage(e.getMessage()).show();
            return false;
        }
    }
}
