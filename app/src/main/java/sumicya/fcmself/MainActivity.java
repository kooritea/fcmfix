package sumicya.fcmself;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import sumicya.fcmself.util.IceboxUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final Object PAYLOAD_STATUS = new Object();

    private enum FilterMode { ALL, FCM_ONLY, ENABLED }

    private AppListAdapter appListAdapter;
    private static XposedService xposedService;
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

    private Boolean cachedDiagIssue;
    private long diagCacheAtMs;
    private static final long DIAG_CACHE_MS = 15_000L;

    private List<AppInfo> fullAppList = new ArrayList<>();

    Set<String> allowList = new HashSet<>();
    JSONObject config = new JSONObject();

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

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    xposedService = service;
                    lastConfigError = false;
                    cachedDiagIssue = null;
                    runOnUiThread(() -> {
                        loadConfigFromRemotePreferences();
                        refreshFabStatus();
                        if (appListAdapter != null) {
                            for (AppInfo info : fullAppList) {
                                info.isAllow = allowList.contains(info.packageName);
                            }
                            applyFilter();
                            updateStats();
                        }
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                    runOnUiThread(() -> refreshFabStatus());
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "initXposedService: " + e);
        }
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
        this.allowList.clear();
        Set<String> remote = pref.getStringSet("allowList", null);
        if (remote != null) {
            this.allowList.addAll(remote);
        }
        try {
            this.config.put("allowList", new JSONArray(this.allowList));
            this.config.put("disableAutoCleanNotification", pref.getBoolean("disableAutoCleanNotification", false));
            this.config.put("includeIceBoxDisableApp", pref.getBoolean("includeIceBoxDisableApp", false));
            this.config.put("noResponseNotification", pref.getBoolean("noResponseNotification", false));
            lastConfigError = false;
        } catch (JSONException e) {
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
        long now = System.currentTimeMillis();
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

    private void applyHeaderScrim() {
        if (headerSafe == null) {
            return;
        }
        try {
            TypedValue tv = new TypedValue();
            int attr = com.google.android.material.R.attr.colorSurface;
            if (!getTheme().resolveAttribute(attr, tv, true)) {
                getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
            }
            int surface = tv.data;
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

    private boolean onFabMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        try {
            if (id == 1) {
                config.put("disableAutoCleanNotification", !item.isChecked());
                updateConfig();
                return true;
            }
            if (id == 2) {
                config.put("includeIceBoxDisableApp", !item.isChecked());
                updateConfig();
                return true;
            }
            if (id == 3) {
                config.put("noResponseNotification", !item.isChecked());
                updateConfig();
                return true;
            }
            if (id == 4) {
                for (AppInfo appInfo : fullAppList) {
                    if (appInfo.includeFcm) {
                        allowList.add(appInfo.packageName);
                        appInfo.isAllow = true;
                    }
                }
                updateConfig();
                applyFilter();
                updateStats();
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
        final Drawable icon;
        boolean isAllow;
        final boolean includeFcm;

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
            final TextView packageName;
            final TextView status;

            ViewHolder(View view) {
                super(view);
                appView = view;
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                packageName = view.findViewById(R.id.package_name);
                status = view.findViewById(R.id.status);
            }
        }

        AppListAdapter() {
            setHasStableIds(true);
        }

        void replaceList(List<AppInfo> list) {
            mAppList.clear();
            mAppList.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
            return mAppList.get(position).packageName.hashCode();
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
                if (appInfo.isAllow) {
                    deleteAppInAllowList(appInfo.packageName);
                } else {
                    addAppInAllowList(appInfo.packageName);
                }
                appInfo.isAllow = !appInfo.isAllow;
                if (filterMode == FilterMode.ENABLED && !appInfo.isAllow) {
                    applyFilter();
                } else {
                    notifyItemChanged(position, PAYLOAD_STATUS);
                }
                updateStats();
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo appInfo = mAppList.get(position);
            holder.icon.setImageDrawable(appInfo.icon);
            holder.name.setText(appInfo.name);
            holder.packageName.setText(appInfo.packageName);
            bindStatus(holder, appInfo);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position);
                return;
            }
            bindStatus(holder, mAppList.get(position));
        }

        private void bindStatus(ViewHolder holder, AppInfo appInfo) {
            // Use same visual family as Material Chip check (✓) with theme primary
            if (appInfo.includeFcm) {
                if (appInfo.isAllow) {
                    holder.status.setText("\u2713");
                    holder.status.setTextColor(colorPrimary);
                } else {
                    holder.status.setText("\u2717");
                    holder.status.setTextColor(colorError);
                }
            } else {
                holder.status.setText("\u2013");
                holder.status.setTextColor(colorDisconnected);
            }
        }

        @Override
        public int getItemCount() {
            return mAppList.size();
        }
    }

    private List<AppInfo> buildAppList() {
        Set<String> allowListSet = new HashSet<>(allowList);
        List<AppInfo> allow = new ArrayList<>();
        List<AppInfo> notAllow = new ArrayList<>();
        List<AppInfo> noFcm = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Drawable defaultIcon = pm.getDefaultActivityIcon();

        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(
                    PackageManager.GET_RECEIVERS
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        } catch (Throwable t) {
            Log.e(TAG, "getInstalledPackages: " + t);
            return new ArrayList<>();
        }

        for (PackageInfo packageInfo : packages) {
            if (packageInfo.receivers == null || packageInfo.applicationInfo == null) {
                continue;
            }
            boolean hasFcm = false;
            for (ActivityInfo receiverInfo : packageInfo.receivers) {
                String n = receiverInfo.name;
                if (n == null) {
                    continue;
                }
                if ("com.google.firebase.iid.FirebaseInstanceIdReceiver".equals(n)
                        || "com.google.android.gms.measurement.AppMeasurementReceiver".equals(n)
                        || n.contains("FirebaseMessaging")
                        || n.endsWith(".FirebaseInstanceIdReceiver")) {
                    hasFcm = true;
                    break;
                }
            }

            String label;
            try {
                label = packageInfo.applicationInfo.loadLabel(pm).toString();
            } catch (Throwable t) {
                label = packageInfo.packageName;
            }
            Drawable icon;
            try {
                icon = packageInfo.applicationInfo.loadIcon(pm);
            } catch (Throwable t) {
                icon = defaultIcon;
            }

            boolean isAllow = allowListSet.contains(packageInfo.packageName);
            AppInfo info = new AppInfo(label, packageInfo.packageName, icon, hasFcm, isAllow);
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
        BG.execute(() -> {
            List<AppInfo> list = buildAppList();
            MAIN_HANDLER.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                fullAppList = list;
                if (appListAdapter == null) {
                    appListAdapter = new AppListAdapter();
                    recyclerView.setAdapter(appListAdapter);
                }
                applyFilter();
                findViewById(R.id.progress_bar).setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                updateStats();
                refreshFabStatus();

                if (list.isEmpty()
                        || (list.size() == 1 && "sumicya.fcmself".equals(list.get(0).packageName))) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("请在系统设置中授予读取应用列表权限")
                            .setMessage("或直接编辑" + getApplicationContext().getFilesDir().getAbsolutePath()
                                    + "/config.json(需重启生效)")
                            .setPositiveButton("确定", (dialog, which) -> {})
                            .show();
                }
            });
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_main);

        colorConnected = ContextCompat.getColor(this, R.color.status_connected);
        colorError = ContextCompat.getColor(this, R.color.status_error);
        colorDisconnected = ContextCompat.getColor(this, R.color.status_disconnected);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)) {
            colorPrimary = tv.data;
        } else {
            colorPrimary = colorConnected;
        }

        headerSafe = findViewById(R.id.header_safe);
        statsView = findViewById(R.id.stats);
        mainFab = findViewById(R.id.main_fab);
        filterChips = findViewById(R.id.filter_chips);
        applyHeaderScrim();
        setupFilterChips();

        try {
            mainFab.setImageResource(android.R.drawable.ic_menu_manage);
        } catch (Throwable ignored) {
        }
        mainFab.setOnClickListener(v -> showFabMenu());
        refreshFabStatus();

        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(24);
        recyclerView.setClipToPadding(false);

        initXposedService();

        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }

        MAIN_HANDLER.postDelayed(this::loadAppListAsync, 200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFabStatus();
    }

    private void addAppInAllowList(String packageName) {
        this.allowList.add(packageName);
        this.updateConfig();
    }

    private void deleteAppInAllowList(String packageName) {
        this.allowList.remove(packageName);
        this.updateConfig();
    }

    private void updateConfig() {
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
            this.sendBroadcast(new Intent("sumicya.fcmself.update.config"));
            refreshFabStatus();
        } catch (Throwable e) {
            Log.e(TAG, "updateConfig: " + e);
            lastConfigError = true;
            refreshFabStatus();
            new AlertDialog.Builder(this).setTitle("更新配置文件失败").setMessage(e.getMessage()).show();
        }
    }
}
