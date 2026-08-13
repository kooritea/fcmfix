package sumicya.fcmself;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

import sumicya.fcmself.util.IceboxUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private AppListAdapter appListAdapter;
    private static XposedService xposedService;
    private FloatingActionButton mainFab;
    private TextView statsView;
    private View headerSafe;
    private boolean lastConfigError = false;

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
                    runOnUiThread(() -> {
                        loadConfigFromRemotePreferences();
                        refreshFabStatus();
                        if (appListAdapter != null) {
                            appListAdapter.notifyDataSetChanged();
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
        this.allowList.addAll(pref.getStringSet("allowList", new HashSet<>()));
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

    /** 绿=已连接 · 黄=有报错码/诊断异常 · 灰=未连接 */
    private void refreshFabStatus() {
        if (mainFab == null) {
            return;
        }
        int color;
        if (xposedService == null) {
            color = ContextCompat.getColor(this, R.color.status_disconnected);
        } else if (lastConfigError || hasFcmDiagnosticsIssue()) {
            color = ContextCompat.getColor(this, R.color.status_error);
        } else {
            color = ContextCompat.getColor(this, R.color.status_connected);
        }
        mainFab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
    }

    /**
     * FCM 诊断相关：GMS 缺失、Diagnostics 组件不可用、或远程配置记录了错误码时视为黄。
     */
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

    /** 毛玻璃感：半透明 surface，无描边；不自模糊文字 */
    private void applyHeaderBlur() {
        if (headerSafe == null) {
            return;
        }
        try {
            int base = ContextCompat.getColor(this, android.R.color.transparent);
            // 取 surface 色并加 alpha，形成叠在列表上的磨砂条
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, tv, true);
            int surface = tv.data;
            int withAlpha = Color.argb(0xE6, Color.red(surface), Color.green(surface), Color.blue(surface));
            headerSafe.setBackground(new ColorDrawable(withAlpha));
            headerSafe.setElevation(0f);
        } catch (Throwable t) {
            headerSafe.setAlpha(0.94f);
        }
        // API 31+ 窗口级背景模糊（系统支持时生效）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getWindow().setBackgroundBlurRadius(24);
            } catch (Throwable ignored) {
            }
        }
    }

    private void updateStats() {
        if (statsView == null || appListAdapter == null) {
            return;
        }
        int enabled = 0;
        int totalFcm = 0;
        for (AppInfo info : appListAdapter.mAppList) {
            if (info.includeFcm) {
                totalFcm++;
                if (info.isAllow) {
                    enabled++;
                }
            }
        }
        statsView.setText(enabled + " - " + totalFcm);
    }

    private void showFabMenu() {
        if (mainFab == null) {
            return;
        }
        PopupMenu popup = new PopupMenu(this, mainFab);
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

    @SuppressLint("NotifyDataSetChanged")
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
                if (appListAdapter != null) {
                    for (AppInfo appInfo : appListAdapter.mAppList) {
                        if (appInfo.includeFcm) {
                            addAppInAllowList(appInfo.packageName);
                            appInfo.isAllow = true;
                        }
                    }
                    appListAdapter.notifyDataSetChanged();
                    updateStats();
                }
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

    private class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public Boolean isAllow = false;
        public Boolean includeFcm = false;

        public AppInfo(PackageInfo packageInfo) {
            this.name = packageInfo.applicationInfo.loadLabel(getPackageManager()).toString();
            this.packageName = packageInfo.packageName;
            this.icon = packageInfo.applicationInfo.loadIcon(getPackageManager());
        }
    }

    private class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
        private final List<AppInfo> mAppList;

        class ViewHolder extends RecyclerView.ViewHolder {
            View appView;
            ImageView icon;
            TextView name;
            TextView status;

            public ViewHolder(View view) {
                super(view);
                appView = view;
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                status = view.findViewById(R.id.status);
            }
        }

        public AppListAdapter() {
            Set<String> allowListSet = new HashSet<>(allowList);
            List<AppInfo> _allowList = new ArrayList<>();
            List<AppInfo> _notAllowList = new ArrayList<>();
            List<AppInfo> _notFoundFcm = new ArrayList<>();
            PackageManager packageManager = getPackageManager();
            for (PackageInfo packageInfo : packageManager.getInstalledPackages(
                    PackageManager.GET_RECEIVERS
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                boolean flag = false;
                AppInfo appInfo = new AppInfo(packageInfo);
                if (packageInfo.receivers != null) {
                    for (ActivityInfo receiverInfo : packageInfo.receivers) {
                        if (receiverInfo.name.equals("com.google.firebase.iid.FirebaseInstanceIdReceiver")
                                || receiverInfo.name.equals("com.google.android.gms.measurement.AppMeasurementReceiver")) {
                            flag = true;
                            appInfo.includeFcm = true;
                            break;
                        }
                    }
                } else {
                    continue;
                }
                if (allowListSet.contains(appInfo.packageName)) {
                    appInfo.isAllow = true;
                    _allowList.add(appInfo);
                } else {
                    if (flag) {
                        _notAllowList.add(appInfo);
                    } else {
                        _notFoundFcm.add(appInfo);
                    }
                }
            }
            class SortName implements Comparator<AppInfo> {
                final Collator localCompare = Collator.getInstance(Locale.getDefault());

                @Override
                public int compare(AppInfo a1, AppInfo a2) {
                    return localCompare.compare(a1.name, a2.name);
                }
            }
            final SortName sortName = new SortName();
            _allowList.sort(sortName);
            _notAllowList.sort(sortName);
            _notFoundFcm.sort(sortName);
            _allowList.addAll(_notAllowList);
            _allowList.addAll(_notFoundFcm);
            this.mAppList = _allowList;
            if (_allowList.isEmpty()
                    || (_allowList.size() == 1 && "sumicya.fcmself".equals(_allowList.get(0).packageName))) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("请在系统设置中授予读取应用列表权限")
                        .setMessage("或直接编辑" + getApplicationContext().getFilesDir().getAbsolutePath()
                                + "/config.json(需重启生效)")
                        .setPositiveButton("确定", (dialog, which) -> {})
                        .show();
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        @NonNull
        @Override
        public AppListAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
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
                appListAdapter.notifyDataSetChanged();
                updateStats();
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull AppListAdapter.ViewHolder holder, int position) {
            AppInfo appInfo = mAppList.get(position);
            holder.icon.setImageDrawable(appInfo.icon);
            holder.name.setText(appInfo.name);
            // ✓ 已启用 · × 有 FCM 未启用 · - 未检测到 FCM
            if (appInfo.includeFcm) {
                if (appInfo.isAllow) {
                    holder.status.setText("✓");
                    holder.status.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_connected));
                } else {
                    holder.status.setText("×");
                    holder.status.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_error));
                }
            } else {
                holder.status.setText("-");
                holder.status.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_disconnected));
            }
        }

        @Override
        public int getItemCount() {
            return mAppList.size();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        headerSafe = findViewById(R.id.header_safe);
        statsView = findViewById(R.id.stats);
        mainFab = findViewById(R.id.main_fab);
        applyHeaderBlur();

        try {
            mainFab.setImageResource(android.R.drawable.ic_menu_manage);
        } catch (Throwable ignored) {
        }
        mainFab.setOnClickListener(v -> showFabMenu());
        refreshFabStatus();

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));

        initXposedService();

        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            appListAdapter = new AppListAdapter();
            recyclerView.setAdapter(appListAdapter);
            findViewById(R.id.progress_bar).setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            updateStats();
            refreshFabStatus();
        }, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFabStatus();
    }

    @Nullable
    @Override
    public View onCreateView(@Nullable View parent, @NonNull String name,
                             @NonNull Context context, @NonNull AttributeSet attrs) {
        return super.onCreateView(parent, name, context, attrs);
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
