package sumicya.fcmself;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textfield.TextInputEditText;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import sumicya.fcmself.util.FcmReceiverDetector;
import sumicya.fcmself.util.IceboxUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String UPDATE_CONFIG_ACTION = "sumicya.fcmself.update.config";
    private static final String HEALTH_REQUEST_ACTION = "sumicya.fcmself.health.request";
    private static final String HEALTH_RESPONSE_ACTION = "sumicya.fcmself.health.response";
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
    private static final Object PAYLOAD_SHAPE = new Object();
    /** Bounded process cache: scrolling through hundreds of apps must not retain every icon. */
    private static final LruCache<String, Drawable> APP_ICON_CACHE = new LruCache<>(96);

    private Drawable defaultAppIcon;

    private enum FilterMode { ALL, FCM_ONLY }

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
    /** Static by design: XposedServiceHelper retains this process-wide listener indefinitely. */
    private static final XposedServiceHelper.OnServiceListener XPOSED_SERVICE_LISTENER =
            new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    xposedService = service;
                    MAIN_HANDLER.post(() -> {
                        MainActivity activity = activeInstance.get();
                        if (activity != null
                                && !activity.isFinishing()
                                && !activity.isDestroyed()) {
                            activity.onXposedServiceConnected();
                        }
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                    MAIN_HANDLER.post(() -> {
                        MainActivity activity = activeInstance.get();
                        if (activity != null
                                && !activity.isFinishing()
                                && !activity.isDestroyed()) {
                            activity.refreshFabStatus();
                        }
                    });
                }
            };
    private static final long FEEDBACK_SHORT_MS = 2_400L;
    private static final long FEEDBACK_LONG_MS = 4_000L;

    private FloatingActionButton mainFab;
    private boolean fabMenuOpen;
    private MaterialCardView feedbackContainer;
    private ImageView feedbackIcon;
    private TextView feedbackText;
    private TextView feedbackAction;
    private Runnable feedbackUndo;
    private int feedbackGeneration;
    private final Runnable hideFeedbackTask = () -> hideFeedback(false);
    private TextView allStatsView;
    private TextView fcmStatsView;
    private View headerSafe;
    private RecyclerView recyclerView;
    private int gridSpanCount = 3;
    private MaterialCardView[] filterCards;
    private TextView[] filterLabels;
    private View bottomControls;
    private MaterialCardView bottomDock;
    private MaterialCardView searchContainer;
    private MaterialCardView searchToggleContainer;
    private TextInputEditText searchInput;
    private ImageButton searchToggle;
    private ValueAnimator searchWidthAnimator;
    private boolean searchExpanded;
    private TextView emptyHint;
    private boolean lastConfigError = false;
    private FilterMode filterMode = FilterMode.ALL;
    private String searchQuery = "";
    private String pendingSearchQuery = "";
    private final Runnable applySearch = () -> {
        searchQuery = pendingSearchQuery;
        applyFilter();
    };

    private int colorError;
    private int colorOnSurface;
    private int colorOnSurfaceVariant;
    private int colorSurfaceContainerLow;
    private int colorSurfaceContainerHigh;
    private int fabConnectedContainer;
    private int fabConnectedContent;
    private int translucentPrimaryContainer;
    private int colorErrorContainer;
    private int colorOnErrorContainer;
    /** Precomputed once so item rebinds and toggles do not allocate a ColorStateList each time. */
    private ColorStateList tintDisconnected;
    private ColorStateList tintOnPrimaryContainer;
    private ColorStateList tintOnErrorContainer;

    private Boolean cachedDiagIssue;
    private long diagCacheAtMs;
    private static final long DIAG_CACHE_MS = 15_000L;

    private List<AppInfo> fullAppList = new ArrayList<>();

    /**
     * Structural app-list cache shared across Activity instances (rotation / recreation). The
     * expensive scan (getInstalledPackages + label loading + FCM component matching) only needs to
     * happen once per process; recreations rebuild lightweight AppInfo wrappers from this snapshot.
     * Entries keep isAllow=false and carry no icon state, so the cache itself is never mutated by
     * a live Activity. Kept in the same (sorted/grouped) order buildAppList produced.
     */
    private static final Object APPS_CACHE_LOCK = new Object();
    private static List<AppInfo> sCachedAppList;
    /** Coalesces rotations/recreations while the first package scan is still running. */
    private static boolean sAppListLoadInProgress;
    /** Invalidates a scan if packages change while PackageManager is still enumerating them. */
    private static int sAppListGeneration;

    private final Set<String> allowList = new HashSet<>();
    /** True once the allowList has been read from the remote config at least once. */
    private boolean allowListLoaded = false;
    private final JSONObject config = new JSONObject();
    private boolean packageReceiverRegistered;
    private boolean healthReceiverRegistered;
    private String healthNonce;
    private TextView healthStatusView;
    private final Map<String, HealthResponse> healthResponses = new HashMap<>();

    private static final class HealthResponse {
        final boolean bootComplete;
        final boolean configReady;
        final List<String> modules;
        final List<String> details;

        HealthResponse(
                boolean bootComplete,
                boolean configReady,
                List<String> modules,
                List<String> details) {
            this.bootComplete = bootComplete;
            this.configReady = configReady;
            this.modules = modules;
            this.details = details;
        }
    }

    private final BroadcastReceiver healthResponseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!HEALTH_RESPONSE_ACTION.equals(intent.getAction())
                    || healthNonce == null
                    || !healthNonce.equals(intent.getStringExtra("nonce"))) {
                return;
            }
            String process = intent.getStringExtra("process");
            ArrayList<String> modules = intent.getStringArrayListExtra("modules");
            ArrayList<String> details = intent.getStringArrayListExtra("details");
            if (process != null) {
                healthResponses.put(process, new HealthResponse(
                        intent.getBooleanExtra("bootComplete", false),
                        intent.getBooleanExtra("configReady", false),
                        modules != null ? modules : new ArrayList<>(),
                        details != null ? details : new ArrayList<>()));
                updateHealthPanel();
            }
        }
    };

    private final BroadcastReceiver packageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart()
                    : null;
            if (packageName == null) {
                APP_ICON_CACHE.evictAll();
                invalidateAppListCache();
                loadAppListAsync();
                return;
            }
            APP_ICON_CACHE.remove(packageName);
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction()) && replacing) {
                // PACKAGE_ADDED/REPLACED will provide the new metadata moments later.
                return;
            }
            updatePackageAsync(
                    packageName,
                    Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction()));
        }
    };

    private void registerHealthResponseReceiver() {
        if (healthReceiverRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(HEALTH_RESPONSE_ACTION);
            ContextCompat.registerReceiver(
                    this,
                    healthResponseReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED);
            healthReceiverRegistered = true;
        } catch (Throwable e) {
            Log.w(TAG, "registerHealthResponseReceiver: " + e);
        }
    }

    private void requestHookHealth(TextView statusView) {
        registerHealthResponseReceiver();
        healthStatusView = statusView;
        healthStatusView.setText(R.string.hook_health_querying);
        healthNonce = UUID.randomUUID().toString();
        healthResponses.clear();

        Intent request = new Intent(HEALTH_REQUEST_ACTION);
        request.putExtra("nonce", healthNonce);
        sendBroadcast(request);
        String expectedNonce = healthNonce;
        MAIN_HANDLER.postDelayed(() -> {
            if (expectedNonce.equals(healthNonce)) {
                updateHealthPanel();
            }
        }, 700L);
    }

    private void updateHealthPanel() {
        if (healthStatusView == null) {
            return;
        }
        StringBuilder status = new StringBuilder();
        status.append("框架服务：")
                .append(xposedService != null ? "已连接" : "未连接")
                .append('\n')
                .append("配置：")
                .append(allowListLoaded && !lastConfigError ? "正常" : "未就绪")
                .append("\n\n");
        appendHealthProcess(status, "android", "system_server");
        appendHealthProcess(status, "com.miui.powerkeeper", "PowerKeeper");
        appendHealthProcess(status, "com.google.android.gms", "GMS");
        status.append("\n已加载不代表所有 OEM Hook 均命中。");
        healthStatusView.setText(status.toString());
    }

    private void appendHealthProcess(StringBuilder status, String process, String label) {
        HealthResponse response = healthResponses.get(process);
        status.append(label).append("：");
        if (response == null) {
            status.append("未响应");
        } else {
            status.append(response.bootComplete && response.configReady ? "就绪" : "初始化中");
            if (!response.details.isEmpty()) {
                for (String detail : response.details) {
                    int separator = detail.indexOf('\u0000');
                    if (separator > 0) {
                        status.append("\n  ")
                                .append(detail, 0, separator)
                                .append("：")
                                .append(detail.substring(separator + 1));
                    }
                }
            } else if (!response.modules.isEmpty()) {
                status.append("\n  ").append(String.join("、", response.modules));
            }
        }
        status.append('\n');
    }

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
                XposedServiceHelper.registerListener(XPOSED_SERVICE_LISTENER);
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
        // Keep the main action visually stable; connection diagnostics are conveyed through the
        // accessible label rather than changing the button palette underneath the user's finger.
        mainFab.setBackgroundTintList(ColorStateList.valueOf(
                fabMenuOpen ? translucentPrimaryContainer : Color.TRANSPARENT));
        mainFab.setImageTintList(ColorStateList.valueOf(
                fabMenuOpen ? fabConnectedContent : colorOnSurfaceVariant));
        mainFab.setCompatElevation(0f);
        mainFab.setCompatHoveredFocusedTranslationZ(0f);
        mainFab.setCompatPressedTranslationZ(0f);
        removeViewShadow(mainFab);
        int description;
        if (xposedService == null) {
            description = R.string.fab_status_disconnected;
        } else if (lastConfigError || hasFcmDiagnosticsIssueCached()) {
            description = R.string.fab_status_error;
        } else {
            description = R.string.fab_status_connected;
        }
        mainFab.setContentDescription(getString(description));
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

    private static void removeViewShadow(View view) {
        view.setElevation(0f);
        view.setTranslationZ(0f);
        view.setStateListAnimator(null);
    }

    private void applyPrimarySurface(
            MaterialCardView card, boolean active, int inactiveContainer) {
        card.setCardBackgroundColor(active ? translucentPrimaryContainer : inactiveContainer);
        card.setCardElevation(0f);
        card.setMaxCardElevation(0f);
        removeViewShadow(card);
    }

    private void setupGlassSurfaces() {
        // A low-alpha Primary Container ties the controls together without restoring a grey cast.
        int dockColor = ColorUtils.setAlphaComponent(fabConnectedContainer, 112);
        bottomDock.setCardBackgroundColor(dockColor);
        bottomDock.setStrokeColor(Color.TRANSPARENT);
        bottomDock.setStrokeWidth(0);
        float segmentRadius = 16 * getResources().getDisplayMetrics().density;
        bottomDock.setShapeAppearanceModel(bottomDock.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(segmentRadius)
                .build());
        removeViewShadow(bottomDock);

        // The two outside controls remain independent rounded components. Only the middle FCM and
        // search controls merge their touching corners when both are active.
        mainFab.setShapeAppearanceModel(mainFab.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(segmentRadius)
                .build());

        // Search remains a separate centered M3E surface and uses Primary Container directly, so
        // it retains dynamic color without the grey cast from a neutral Surface Container layer.
        int glassColor = ColorUtils.setAlphaComponent(fabConnectedContainer, 184);
        searchContainer.setCardBackgroundColor(glassColor);
        searchContainer.setStrokeColor(Color.TRANSPARENT);
        searchContainer.setStrokeWidth(0);
        float searchRadius = 24 * getResources().getDisplayMetrics().density;
        searchContainer.setShapeAppearanceModel(searchContainer.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(searchRadius)
                .build());
        removeViewShadow(searchContainer);

        searchInput.setTextColor(fabConnectedContent);
        searchInput.setHintTextColor(fabConnectedContent);
        updateSearchToggleStyle(false);
    }

    private void updateSearchToggleStyle(boolean active) {
        applyPrimarySurface(searchToggleContainer, active, Color.TRANSPARENT);
        searchToggle.setImageResource(
                active ? R.drawable.ic_status_close : R.drawable.ic_search);
        searchToggle.setImageTintList(ColorStateList.valueOf(
                active ? fabConnectedContent : colorOnSurfaceVariant));
        updateMiddleControlShapes();
    }

    private void updateMiddleControlShapes() {
        if (filterCards == null || filterCards.length < 2 || searchToggleContainer == null) {
            return;
        }
        float radius = 16 * getResources().getDisplayMetrics().density;
        boolean merge = filterMode == FilterMode.FCM_ONLY && searchExpanded;

        ShapeAppearanceModel.Builder fcmShape = filterCards[1].getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(radius);
        ShapeAppearanceModel.Builder searchShape = searchToggleContainer.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(radius);
        if (merge) {
            fcmShape.setTopRightCornerSize(0f)
                    .setBottomRightCornerSize(0f);
            searchShape.setTopLeftCornerSize(0f)
                    .setBottomLeftCornerSize(0f);
        }
        filterCards[1].setShapeAppearanceModel(fcmShape.build());
        searchToggleContainer.setShapeAppearanceModel(searchShape.build());
    }

    private void applyHeaderScrim() {
        if (headerSafe == null) {
            return;
        }
        try {
            int surface = resolveThemeColor(
                    com.google.android.material.R.attr.colorSurface, Color.WHITE);
            int r = Color.red(surface);
            int g = Color.green(surface);
            int b = Color.blue(surface);

            int[] colors = new int[]{
                    Color.argb(0xF2, r, g, b),
                    Color.argb(0xE8, r, g, b),
                    Color.argb(0xC0, r, g, b),
                    Color.argb(0x60, r, g, b),
                    Color.argb(0x00, r, g, b)
            };
            float[] offsets = {0f, 0.50f, 0.72f, 0.90f, 1f};
            GradientDrawable gd = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, colors);
            gd.setColors(colors, offsets);
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
        int totalFcm = 0;
        for (AppInfo info : fullAppList) {
            if (info.includeFcm) {
                totalFcm++;
            }
        }
        if (allStatsView != null) {
            allStatsView.setText(getString(R.string.compact_count, fullAppList.size()));
        }
        if (fcmStatsView != null) {
            fcmStatsView.setText(getString(R.string.compact_count, totalFcm));
        }
        updateFilterTabStyle();
    }

    private void updateFeedbackWidth(String text) {
        float density = getResources().getDisplayMetrics().density;
        int desiredWidth = Math.round(feedbackText.getPaint().measureText(text))
                + Math.round(112 * density);
        int minimumWidth = Math.round(160 * density);
        int maximumWidth = Math.round(280 * density);
        desiredWidth = Math.max(minimumWidth, Math.min(desiredWidth, maximumWidth));

        ViewGroup.LayoutParams params = feedbackContainer.getLayoutParams();
        if (params.width != desiredWidth) {
            params.width = desiredWidth;
            feedbackContainer.setLayoutParams(params);
        }
    }

    private void showFeedback(
            String text,
            int iconRes,
            int containerColor,
            int contentColor,
            long durationMs,
            Runnable undoAction) {
        MAIN_HANDLER.removeCallbacks(hideFeedbackTask);
        feedbackGeneration++;
        feedbackUndo = undoAction;
        feedbackContainer.animate().cancel();
        feedbackContainer.setCardBackgroundColor(containerColor);
        feedbackContainer.setStrokeWidth(0);
        removeViewShadow(feedbackContainer);
        feedbackIcon.setImageResource(iconRes);
        feedbackIcon.setImageTintList(ColorStateList.valueOf(contentColor));
        feedbackText.setText(text);
        feedbackText.setTextColor(contentColor);
        updateFeedbackWidth(text);
        feedbackAction.setTextColor(contentColor);
        feedbackAction.setEnabled(undoAction != null);
        // Keep an equal-width trailing rail even without Undo, so the message remains truly centred.
        feedbackAction.setVisibility(undoAction != null ? View.VISIBLE : View.INVISIBLE);

        if (feedbackContainer.getVisibility() != View.VISIBLE) {
            feedbackContainer.setAlpha(0f);
            feedbackContainer.setVisibility(View.VISIBLE);
            feedbackContainer.animate().alpha(1f).setDuration(140L).start();
        } else {
            feedbackContainer.setAlpha(1f);
        }
        MAIN_HANDLER.postDelayed(hideFeedbackTask, durationMs);
    }

    private void hideFeedback(boolean immediate) {
        MAIN_HANDLER.removeCallbacks(hideFeedbackTask);
        feedbackUndo = null;
        int generation = ++feedbackGeneration;
        feedbackContainer.animate().cancel();
        if (feedbackContainer.getVisibility() != View.VISIBLE) {
            feedbackContainer.setAlpha(1f);
            return;
        }
        if (immediate) {
            feedbackContainer.setVisibility(View.GONE);
            feedbackContainer.setAlpha(1f);
            return;
        }
        feedbackContainer.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (generation == feedbackGeneration) {
                        feedbackContainer.setVisibility(View.GONE);
                        feedbackContainer.setAlpha(1f);
                    }
                })
                .start();
    }

    /**
     * Shows a short confirmation for a toggle and lets the user undo it. The undo re-applies the
     * inverse persistence and re-filters, so a tile that left a filtered view comes back instead
     * of silently disappearing.
     */
    private void showToggleFeedback(AppInfo appInfo) {
        final boolean nowAllow = appInfo.isAllow;
        final String pkg = appInfo.packageName;
        final String name = appInfo.name;
        String text = (nowAllow ? "已启用：" : "已停用：") + name;

        showFeedback(
                text,
                nowAllow ? R.drawable.ic_status_check : R.drawable.ic_status_close,
                ColorUtils.setAlphaComponent(
                        nowAllow ? fabConnectedContainer : colorErrorContainer, 184),
                nowAllow ? fabConnectedContent : colorOnErrorContainer,
                FEEDBACK_LONG_MS,
                () -> {
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
    }

    private void showUnsupportedAppFeedback(AppInfo appInfo) {
        showFeedback(
                getString(R.string.unsupported_app_message, appInfo.name),
                R.drawable.ic_status_dash,
                ColorUtils.setAlphaComponent(colorSurfaceContainerHigh, 184),
                colorOnSurface,
                FEEDBACK_SHORT_MS,
                null);
    }

    private void applyFilter() {
        if (appListAdapter == null) {
            return;
        }
        List<AppInfo> filtered = new ArrayList<>(fullAppList.size());
        for (AppInfo info : fullAppList) {
            if (!searchQuery.isEmpty()
                    && !info.searchableName.contains(searchQuery)
                    && !info.searchablePackage.contains(searchQuery)) {
                continue;
            }
            switch (filterMode) {
                case FCM_ONLY:
                    if (info.includeFcm) {
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
        if (emptyHint != null) {
            boolean showEmpty = filtered.isEmpty();
            emptyHint.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
            recyclerView.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        }
    }

    private void updateSearchWidth(CharSequence query, boolean animate) {
        float density = getResources().getDisplayMetrics().density;
        CharSequence measuredText = query.length() == 0
                ? getString(R.string.search_hint)
                : query;
        int desiredWidth = Math.round(searchInput.getPaint().measureText(
                measuredText.toString())) + Math.round(40 * density);
        int minimumWidth = Math.round(144 * density);
        int maximumWidth = Math.round(280 * density);
        desiredWidth = Math.max(minimumWidth, Math.min(desiredWidth, maximumWidth));

        ViewGroup.LayoutParams params = searchContainer.getLayoutParams();
        int currentWidth = searchContainer.getWidth() > 0
                ? searchContainer.getWidth()
                : params.width;
        if (searchWidthAnimator != null) {
            searchWidthAnimator.cancel();
        }
        if (!animate || currentWidth <= 0 || currentWidth == desiredWidth) {
            params.width = desiredWidth;
            searchContainer.setLayoutParams(params);
            return;
        }
        searchWidthAnimator = ValueAnimator.ofInt(currentWidth, desiredWidth);
        searchWidthAnimator.setDuration(160L);
        searchWidthAnimator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams animatedParams = searchContainer.getLayoutParams();
            animatedParams.width = (int) animation.getAnimatedValue();
            searchContainer.setLayoutParams(animatedParams);
        });
        searchWidthAnimator.start();
    }

    private void setupSearch() {
        searchToggle.setOnClickListener(v -> toggleSearch());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                pendingSearchQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                MAIN_HANDLER.removeCallbacks(applySearch);
                MAIN_HANDLER.postDelayed(applySearch, 120L);
                if (searchExpanded) {
                    updateSearchWidth(s, true);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void toggleSearch() {
        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        searchContainer.animate().cancel();
        float offset = 12 * getResources().getDisplayMetrics().density;
        if (!searchExpanded) {
            searchExpanded = true;
            updateSearchWidth(searchInput.getText(), false);
            updateSearchToggleStyle(true);
            searchContainer.setVisibility(View.VISIBLE);
            searchContainer.setPivotY(48 * getResources().getDisplayMetrics().density);
            searchContainer.setAlpha(0f);
            searchContainer.setScaleX(0.94f);
            searchContainer.setScaleY(0.82f);
            searchContainer.setTranslationY(offset);
            searchContainer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(200L)
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
                    .setDuration(160L)
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

    private void setupFilterTabs() {
        if (filterCards == null || filterLabels == null) {
            return;
        }
        FilterMode[] modes = FilterMode.values();
        for (int i = 0; i < filterCards.length; i++) {
            final FilterMode mode = modes[i];
            filterCards[i].setOnClickListener(v -> selectFilter(mode));
        }
        updateFilterTabStyle();
    }

    private void selectFilter(FilterMode mode) {
        if (filterMode == mode) {
            return;
        }
        filterMode = mode;
        updateFilterTabStyle();
        animateSelection(filterCards[mode.ordinal()]);
        applyFilter();
    }

    private static void animateSelection(View view) {
        view.animate().cancel();
        view.setScaleX(0.94f);
        view.setScaleY(0.94f);
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .start();
    }

    private void updateFilterTabStyle() {
        if (filterCards == null || filterLabels == null) {
            return;
        }
        TextView[] counts = {allStatsView, fcmStatsView};
        int selectedIndex = filterMode.ordinal();
        float segmentRadius = 16 * getResources().getDisplayMetrics().density;
        for (int i = 0; i < filterCards.length; i++) {
            boolean selected = i == selectedIndex;
            int content = selected ? fabConnectedContent : colorOnSurfaceVariant;
            applyPrimarySurface(filterCards[i], selected, Color.TRANSPARENT);
            if (i == 0) {
                filterCards[i].setShapeAppearanceModel(filterCards[i]
                        .getShapeAppearanceModel()
                        .toBuilder()
                        .setAllCornerSizes(segmentRadius)
                        .build());
            }
            filterLabels[i].setTextColor(content);
            if (counts[i] != null) {
                counts[i].setTextColor(content);
                filterCards[i].setContentDescription(
                        filterLabels[i].getText() + "，" + counts[i].getText()
                                + (selected ? "，当前筛选" : ""));
            }
        }
        updateMiddleControlShapes();
    }

    private int calculateFabMenuWidth(
            LayoutInflater inflater, LinearLayout items, int horizontalMargin) {
        View probe = inflater.inflate(R.layout.fab_menu_item, items, false);
        TextView label = probe.findViewById(R.id.menu_label);
        int[] labels = {
                R.string.fab_menu_disable_auto_clean,
                R.string.fab_menu_include_icebox,
                R.string.fab_menu_no_response,
                R.string.fab_menu_select_all_fcm,
                R.string.fab_menu_diagnostics,
                R.string.fab_menu_hook_health
        };
        float longestText = 0f;
        for (int labelRes : labels) {
            longestText = Math.max(
                    longestText,
                    label.getPaint().measureText(getString(labelRes)));
        }
        float density = getResources().getDisplayMetrics().density;
        int desired = Math.round(longestText) + Math.round(80 * density);
        int minimum = Math.round(208 * density);
        int screenMaximum = getResources().getDisplayMetrics().widthPixels
                - horizontalMargin * 2;
        int maximum = Math.min(Math.round(280 * density), screenMaximum);
        minimum = Math.min(minimum, maximum);
        return Math.max(minimum, Math.min(desired, maximum));
    }

    private void showFabMenu() {
        if (mainFab == null) {
            return;
        }
        hideFeedback(true);
        LayoutInflater inflater = LayoutInflater.from(this);
        ViewGroup popupParent = (ViewGroup) mainFab.getParent();
        View content = inflater.inflate(R.layout.fab_menu_popup, popupParent, false);
        content.setClipToOutline(true);
        LinearLayout items = content.findViewById(R.id.fab_menu_items);
        int horizontalMargin = Math.round(8 * getResources().getDisplayMetrics().density);
        int popupWidth = calculateFabMenuWidth(inflater, items, horizontalMargin);
        int expandedPopupWidth = Math.min(
                Math.round(280 * getResources().getDisplayMetrics().density),
                getResources().getDisplayMetrics().widthPixels - horizontalMargin * 2);
        PopupWindow popup = new PopupWindow(
                content, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(0f);
        popup.setAnimationStyle(R.style.Animation_Fcmself_FabMenu);
        popup.setClippingEnabled(true);

        addToggleFabMenuItem(
                items,
                R.string.fab_menu_disable_auto_clean,
                "disableAutoCleanNotification");
        addToggleFabMenuItem(
                items,
                R.string.fab_menu_include_icebox,
                "includeIceBoxDisableApp");
        addToggleFabMenuItem(
                items,
                R.string.fab_menu_no_response,
                "noResponseNotification");
        addActionFabMenuItem(
                items, R.string.fab_menu_select_all_fcm, R.drawable.ic_status_check, v -> {
            enableAllFcmApps();
            popup.dismiss();
        });
        addActionFabMenuItem(
                items, R.string.fab_menu_diagnostics, R.drawable.ic_open_external, v -> {
            popup.dismiss();
            openFcmDiagnostics();
        });
        View healthRow = addActionFabMenuItem(
                items, R.string.fab_menu_hook_health, R.drawable.ic_health, null);
        View healthPanel = inflater.inflate(R.layout.hook_health_panel, items, false);
        MaterialCardView healthCard = (MaterialCardView) healthPanel;
        TextView healthStatus = healthPanel.findViewById(R.id.hook_health_status);
        healthCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(
                fabConnectedContainer, 184));
        healthCard.setStrokeWidth(0);
        healthCard.setCardElevation(0f);
        healthCard.setMaxCardElevation(0f);
        removeViewShadow(healthCard);
        healthStatus.setTextColor(fabConnectedContent);
        items.addView(healthPanel);
        ScrollView menuScroll = content.findViewById(R.id.fab_menu_scroll);
        healthRow.setOnClickListener(v -> {
            boolean expand = healthPanel.getVisibility() != View.VISIBLE;
            healthPanel.setVisibility(expand ? View.VISIBLE : View.GONE);
            if (expand) {
                requestHookHealth(healthStatus);
            } else {
                healthStatusView = null;
                healthNonce = null;
            }
            applyFabMenuEdgeShapes(items);
            positionFabPopup(
                    popup,
                    content,
                    expand ? expandedPopupWidth : popupWidth,
                    horizontalMargin);
            if (expand) {
                healthPanel.post(() -> menuScroll.smoothScrollTo(0, healthPanel.getBottom()));
            }
        });
        popup.setOnDismissListener(() -> {
            healthStatusView = null;
            healthNonce = null;
            fabMenuOpen = false;
            refreshFabStatus();
        });
        applyFabMenuEdgeShapes(items);

        fabMenuOpen = true;
        refreshFabStatus();
        positionFabPopup(popup, content, popupWidth, horizontalMargin);
    }

    private void positionFabPopup(
            PopupWindow popup, View content, int popupWidth, int horizontalMargin) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] location = new int[2];
        mainFab.getLocationInWindow(location);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // The content-sized popup remains centred on the connected control rail.
        int x = (screenWidth - popupWidth) / 2;
        int maximumX = screenWidth - popupWidth - horizontalMargin;
        x = Math.max(horizontalMargin, Math.min(x, maximumX));
        int verticalGap = Math.round(8 * getResources().getDisplayMetrics().density);
        int availableHeight = Math.max(
                1, location[1] - verticalGap - horizontalMargin);
        int popupHeight = Math.min(content.getMeasuredHeight(), availableHeight);
        int y = Math.max(horizontalMargin, location[1] - popupHeight - verticalGap);
        if (popup.isShowing()) {
            popup.update(x, y, popupWidth, popupHeight);
        } else {
            popup.setHeight(popupHeight);
            popup.showAtLocation(mainFab, Gravity.NO_GRAVITY, x, y);
        }
    }

    private void applyFabMenuEdgeShapes(LinearLayout items) {
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
        float radius = 16 * getResources().getDisplayMetrics().density;
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

    private View inflateFabMenuItem(LinearLayout parent, int labelRes) {
        View row = LayoutInflater.from(this).inflate(R.layout.fab_menu_item, parent, false);
        ((TextView) row.findViewById(R.id.menu_label)).setText(labelRes);
        parent.addView(row);
        return row;
    }

    private void bindToggleFabMenuState(View row, boolean checked) {
        MaterialCardView rowCard = (MaterialCardView) row;
        MaterialCardView stateContainer = row.findViewById(R.id.menu_state_container);
        MaterialCardView labelContainer = row.findViewById(R.id.menu_label_container);
        ImageView symbol = row.findViewById(R.id.menu_symbol);
        TextView label = row.findViewById(R.id.menu_label);
        int content = checked ? fabConnectedContent : colorOnErrorContainer;
        applyPrimarySurface(rowCard, checked, colorErrorContainer);
        // Child segments stay transparent; the row owns one continuous, shadow-free tonal surface.
        stateContainer.setCardBackgroundColor(Color.TRANSPARENT);
        labelContainer.setCardBackgroundColor(Color.TRANSPARENT);
        symbol.setImageResource(checked
                ? R.drawable.ic_status_check
                : R.drawable.ic_status_close);
        symbol.setImageTintList(ColorStateList.valueOf(content));
        label.setTextColor(content);
        row.setContentDescription(label.getText() + "，" + (checked ? "已开启" : "已关闭"));
    }

    private void addToggleFabMenuItem(LinearLayout parent, int labelRes, String configKey) {
        View row = inflateFabMenuItem(parent, labelRes);
        bindToggleFabMenuState(row, config.optBoolean(configKey, false));
        row.setOnClickListener(v -> {
            boolean next = !config.optBoolean(configKey, false);
            if (updateBooleanConfig(configKey, next)) {
                bindToggleFabMenuState(row, next);
            }
        });
    }

    private View addActionFabMenuItem(
            LinearLayout parent, int labelRes, int iconRes, View.OnClickListener listener) {
        View row = inflateFabMenuItem(parent, labelRes);
        MaterialCardView rowCard = (MaterialCardView) row;
        MaterialCardView stateContainer = row.findViewById(R.id.menu_state_container);
        MaterialCardView labelContainer = row.findViewById(R.id.menu_label_container);
        ImageView symbol = row.findViewById(R.id.menu_symbol);
        TextView label = row.findViewById(R.id.menu_label);
        // Actions share the exact same translucent primary surface as an enabled toggle.
        int content = fabConnectedContent;
        applyPrimarySurface(rowCard, true, colorSurfaceContainerHigh);
        stateContainer.setCardBackgroundColor(Color.TRANSPARENT);
        labelContainer.setCardBackgroundColor(Color.TRANSPARENT);
        symbol.setImageResource(iconRes);
        symbol.setImageTintList(ColorStateList.valueOf(content));
        label.setTextColor(content);
        row.setContentDescription(label.getText());
        if (listener != null) {
            row.setOnClickListener(listener);
        }
        return row;
    }

    private boolean updateBooleanConfig(String key, boolean value) {
        boolean previous = config.optBoolean(key, false);
        try {
            config.put(key, value);
            if (updateConfig()) {
                return true;
            }
            // Keep the in-memory menu state aligned with the last successfully persisted value.
            config.put(key, previous);
        } catch (JSONException e) {
            Log.e(TAG, "updateBooleanConfig: " + e);
        }
        return false;
    }

    private void enableAllFcmApps() {
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
    }

    private void openFcmDiagnostics() {
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
    }

    private static class AppInfo {
        final String name;
        final String packageName;
        final String searchableName;
        final String searchablePackage;
        /** Precomputed once instead of hashing the package on every RecyclerView ID lookup. */
        final long stableId;
        boolean isAllow;
        final boolean includeFcm;
        volatile boolean iconLoading;

        AppInfo(String name, String packageName, boolean includeFcm, boolean isAllow) {
            this.name = name;
            this.packageName = packageName;
            this.searchableName = name.toLowerCase(Locale.ROOT);
            this.searchablePackage = packageName.toLowerCase(Locale.ROOT);
            this.stableId = stableIdForPackage(packageName);
            this.includeFcm = includeFcm;
            this.isAllow = isAllow;
        }

        private static long stableIdForPackage(String packageName) {
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < packageName.length(); i++) {
                hash ^= packageName.charAt(i);
                hash *= 0x100000001b3L;
            }
            return hash;
        }
    }

    private class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
        private final List<AppInfo> mAppList = new ArrayList<>();
        /** Current filtered-list index by package; makes asynchronous icon completion O(1). */
        private final Map<String, Integer> mPositions = new HashMap<>();
        /** Last status actually submitted to RecyclerView, independent of mutable AppInfo objects. */
        private final Map<String, Integer> mRenderedStatus = new HashMap<>();

        class ViewHolder extends RecyclerView.ViewHolder {
            final MaterialCardView appView;
            final ImageView icon;
            final TextView name;
            final TextView appPkg;
            final ImageView status;

            ViewHolder(View view) {
                super(view);
                appView = (MaterialCardView) view;
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
            // Most refreshes only update allow-state while retaining the same filtered packages.
            // Skip DiffUtil's O(n) comparison in that common path and issue status-only payloads.
            if (hasSameItemOrder(list)) {
                for (int i = 0; i < list.size(); i++) {
                    AppInfo previous = mAppList.get(i);
                    AppInfo current = list.get(i);
                    int oldStatus = mRenderedStatus.getOrDefault(
                            previous.packageName, statusState(previous));
                    int newStatus = statusState(current);
                    mAppList.set(i, current);
                    mRenderedStatus.put(current.packageName, newStatus);
                    if (!previous.name.equals(current.name)
                            || previous.includeFcm != current.includeFcm) {
                        notifyItemChanged(i);
                    } else if (oldStatus != newStatus) {
                        notifyItemChanged(i, PAYLOAD_STATUS);
                    }
                }
                return;
            }

            // AppInfo is intentionally mutable. Snapshot the old list and rendered statuses before
            // replacing it, otherwise DiffUtil can compare an already-mutated object with itself
            // and miss an undo/config status change.
            final List<AppInfo> oldList = new ArrayList<>(mAppList);
            final Map<String, Integer> oldStatuses = new HashMap<>(mRenderedStatus);
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return oldList.size();
                }

                @Override
                public int getNewListSize() {
                    return list.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return oldList.get(oldItemPosition).packageName
                            .equals(list.get(newItemPosition).packageName);
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    AppInfo oldItem = oldList.get(oldItemPosition);
                    AppInfo newItem = list.get(newItemPosition);
                    return oldItem.name.equals(newItem.name)
                            && oldItem.includeFcm == newItem.includeFcm
                            && oldStatuses.getOrDefault(
                                    oldItem.packageName, statusState(oldItem))
                            == statusState(newItem);
                }

                @Override
                public Object getChangePayload(int oldItemPosition, int newItemPosition) {
                    AppInfo oldItem = oldList.get(oldItemPosition);
                    AppInfo newItem = list.get(newItemPosition);
                    if (oldItem.name.equals(newItem.name)
                            && oldItem.includeFcm == newItem.includeFcm) {
                        return PAYLOAD_STATUS;
                    }
                    return null;
                }
            }, true);
            mAppList.clear();
            mAppList.addAll(list);
            rebuildSubmittedState();
            result.dispatchUpdatesTo(this);
            if (!mAppList.isEmpty()) {
                notifyItemRangeChanged(0, mAppList.size(), PAYLOAD_SHAPE);
            }
        }

        private boolean hasSameItemOrder(List<AppInfo> list) {
            if (mAppList.size() != list.size()) {
                return false;
            }
            for (int i = 0; i < list.size(); i++) {
                if (!mAppList.get(i).packageName.equals(list.get(i).packageName)) {
                    return false;
                }
            }
            return true;
        }

        private int statusState(AppInfo appInfo) {
            if (appInfo.isAllow) {
                return 1;
            }
            if (!appInfo.includeFcm || !allowListLoaded) {
                return 0;
            }
            return -1;
        }

        private void rebuildSubmittedState() {
            mPositions.clear();
            mRenderedStatus.clear();
            for (int i = 0; i < mAppList.size(); i++) {
                AppInfo item = mAppList.get(i);
                mPositions.put(item.packageName, i);
                mRenderedStatus.put(item.packageName, statusState(item));
            }
        }

        private void notifyStatusChanged(int position) {
            AppInfo item = mAppList.get(position);
            mRenderedStatus.put(item.packageName, statusState(item));
            notifyItemChanged(position, PAYLOAD_STATUS);
        }

        @Override
        public long getItemId(int position) {
            return mAppList.get(position).stableId;
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
                if (!appInfo.includeFcm && !appInfo.isAllow) {
                    showUnsupportedAppFeedback(appInfo);
                    return;
                }
                boolean ok = appInfo.isAllow
                        ? deleteAppInAllowList(appInfo.packageName)
                        : addAppInAllowList(appInfo.packageName);
                if (!ok) {
                    // Persisting failed — keep the mark in sync with what is actually stored.
                    return;
                }
                appInfo.isAllow = !appInfo.isAllow;
                notifyStatusChanged(position);
                updateStats();
                showToggleFeedback(appInfo);
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo appInfo = mAppList.get(position);
            // RecyclerView holders must never inherit an interrupted press-scale animation.
            holder.appView.animate().cancel();
            holder.appView.setScaleX(1f);
            holder.appView.setScaleY(1f);
            holder.name.setText(appInfo.name);
            holder.appPkg.setText(appInfo.packageName);
            bindCardShape(holder, position);
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
                    Drawable icon = APP_ICON_CACHE.get(appInfo.packageName);
                    if (icon != null && appInfo.packageName.equals(holder.icon.getTag())) {
                        holder.icon.setImageDrawable(icon);
                    }
                } else if (payload == PAYLOAD_STATUS) {
                    bindStatus(holder, appInfo);
                } else if (payload == PAYLOAD_SHAPE) {
                    bindCardShape(holder, position);
                }
            }
        }

        private void bindIcon(ViewHolder holder, AppInfo appInfo) {
            // Tag guards against recycled view showing wrong icon
            holder.icon.setTag(appInfo.packageName);
            Drawable cached = APP_ICON_CACHE.get(appInfo.packageName);
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
            final PackageManager packageManager = getApplicationContext().getPackageManager();
            final Drawable fallbackIcon = defaultAppIcon;
            final int iconGeneration;
            synchronized (APPS_CACHE_LOCK) {
                iconGeneration = sAppListGeneration;
            }
            final WeakReference<AppListAdapter> adapterRef = new WeakReference<>(this);
            ICON_EXEC.execute(() -> {
                Drawable loadedIcon;
                try {
                    loadedIcon = packageManager.getApplicationIcon(pkg);
                } catch (Throwable t) {
                    loadedIcon = fallbackIcon;
                }
                final boolean stillCurrent;
                synchronized (APPS_CACHE_LOCK) {
                    stillCurrent = iconGeneration == sAppListGeneration;
                }
                if (loadedIcon != null && stillCurrent) {
                    APP_ICON_CACHE.put(pkg, loadedIcon);
                }
                appInfo.iconLoading = false;
                MAIN_HANDLER.post(() -> {
                    AppListAdapter adapter = adapterRef.get();
                    if (adapter == null) {
                        return;
                    }
                    Integer position = adapter.mPositions.get(pkg);
                    if (position != null
                            && position >= 0
                            && position < adapter.mAppList.size()
                            && pkg.equals(adapter.mAppList.get(position).packageName)) {
                        adapter.notifyItemChanged(position, PAYLOAD_ICON);
                    }
                });
            });
        }

        private void bindCardShape(ViewHolder holder, int position) {
            int itemCount = mAppList.size();
            if (itemCount == 0 || position < 0 || position >= itemCount) {
                return;
            }
            int last = itemCount - 1;
            int topRight = Math.min(gridSpanCount - 1, last);
            int bottomLeft = last - (last % gridSpanCount);
            float radius = 16 * getResources().getDisplayMetrics().density;
            ShapeAppearanceModel.Builder shape = holder.appView.getShapeAppearanceModel()
                    .toBuilder()
                    .setAllCornerSizes(0f);
            if (position == 0) {
                shape.setTopLeftCornerSize(radius);
            }
            if (position == topRight) {
                shape.setTopRightCornerSize(radius);
            }
            if (position == bottomLeft) {
                shape.setBottomLeftCornerSize(radius);
            }
            if (position == last) {
                shape.setBottomRightCornerSize(radius);
            }
            holder.appView.setShapeAppearanceModel(shape.build());
        }

        private void bindCardAppearance(ViewHolder holder, AppInfo appInfo) {
            if (appInfo.isAllow) {
                applyPrimarySurface(holder.appView, true, colorSurfaceContainerLow);
                holder.name.setTextColor(fabConnectedContent);
                holder.appPkg.setTextColor(fabConnectedContent);
            } else if (appInfo.includeFcm && allowListLoaded) {
                applyPrimarySurface(holder.appView, false, colorErrorContainer);
                holder.name.setTextColor(colorOnErrorContainer);
                holder.appPkg.setTextColor(colorOnErrorContainer);
            } else {
                applyPrimarySurface(holder.appView, false, colorSurfaceContainerLow);
                holder.name.setTextColor(colorOnSurface);
                holder.appPkg.setTextColor(colorOnSurfaceVariant);
            }
        }

        private void bindStatus(ViewHolder holder, AppInfo appInfo) {
            bindCardAppearance(holder, appInfo);
            // Vector marks are tinted so the icon follows the theme instead of being re-drawn as
            // an emoji glyph by the OEM font on the device. Manually allowed apps keep a check even
            // when heuristic component scanning did not identify FCM; otherwise the Enabled filter
            // and the tile would disagree about the same allow-list entry.
            final int description;
            if (appInfo.isAllow) {
                holder.status.setImageResource(R.drawable.ic_status_check);
                holder.status.setImageTintList(tintOnPrimaryContainer);
                description = R.string.app_status_enabled_desc;
            } else if (appInfo.includeFcm && allowListLoaded) {
                holder.status.setImageResource(R.drawable.ic_status_close);
                holder.status.setImageTintList(tintOnErrorContainer);
                description = R.string.app_status_disabled_desc;
            } else {
                // Config not read yet (service still binding after a cold start), or no FCM
                // component was detected: show the neutral marker instead of a false cross.
                holder.status.setImageResource(R.drawable.ic_status_dash);
                holder.status.setImageTintList(tintDisconnected);
                description = appInfo.includeFcm
                        ? R.string.app_status_pending_desc
                        : R.string.app_status_unsupported_desc;
            }
            holder.appView.setContentDescription(getString(description, appInfo.name));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            super.onViewRecycled(holder);
            holder.appView.animate().cancel();
            holder.appView.setScaleX(1f);
            holder.appView.setScaleY(1f);
            holder.icon.setTag(null);
            holder.icon.setImageDrawable(defaultAppIcon);
        }

        @Override
        public int getItemCount() {
            return mAppList.size();
        }
    }

    private static final String[] FCM_INTENT_ACTIONS = {
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.android.c2dm.intent.REGISTRATION",
            "com.google.firebase.MESSAGING_EVENT",
            "com.google.firebase.INSTANCE_ID_EVENT"
    };

    private static void collectResolvedPackages(
            Set<String> packages, List<ResolveInfo> resolvedComponents) {
        if (resolvedComponents == null) {
            return;
        }
        for (ResolveInfo resolved : resolvedComponents) {
            if (resolved == null) {
                continue;
            }
            if (resolved.activityInfo != null && resolved.activityInfo.packageName != null) {
                packages.add(resolved.activityInfo.packageName);
            }
            if (resolved.serviceInfo != null && resolved.serviceInfo.packageName != null) {
                packages.add(resolved.serviceInfo.packageName);
            }
        }
    }

    /**
     * Component class names are only a heuristic: apps commonly name their Firebase service
     * simply "PushService". Querying manifest intent filters catches those obfuscated/custom names
     * without opening every APK individually.
     */
    private static Set<String> queryFcmIntentPackages(
            PackageManager pm, String packageName) {
        Set<String> packages = new HashSet<>();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        for (String action : FCM_INTENT_ACTIONS) {
            Intent intent = new Intent(action);
            if (packageName != null) {
                intent.setPackage(packageName);
            }
            try {
                collectResolvedPackages(packages, pm.queryBroadcastReceivers(intent, flags));
            } catch (Throwable t) {
                Log.w(TAG, "query FCM receivers failed for " + action + ": " + t);
            }
            try {
                collectResolvedPackages(packages, pm.queryIntentServices(intent, flags));
            } catch (Throwable t) {
                Log.w(TAG, "query FCM services failed for " + action + ": " + t);
            }
        }
        return packages;
    }

    private static boolean hasFcmComponent(ActivityInfo[] components) {
        if (components == null) {
            return false;
        }
        for (ActivityInfo component : components) {
            if (component != null && FcmReceiverDetector.isFcmComponent(component.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFcmComponent(ServiceInfo[] components) {
        if (components == null) {
            return false;
        }
        for (ServiceInfo component : components) {
            if (component != null && FcmReceiverDetector.isFcmComponent(component.name)) {
                return true;
            }
        }
        return false;
    }

    private static AppInfo createAppInfo(
            PackageManager pm,
            PackageInfo packageInfo,
            Set<String> allowListSet,
            Set<String> packagesResolvedForFcm) {
        if (packageInfo.applicationInfo == null) {
            return null;
        }
        // Accept only strong evidence: a matching manifest action or an explicit
        // Firebase/GCM component name. Generic push names and permission-only hints are ignored.
        boolean hasFcm = packagesResolvedForFcm.contains(packageInfo.packageName)
                || hasFcmComponent(packageInfo.receivers)
                || hasFcmComponent(packageInfo.services);
        String label;
        try {
            label = packageInfo.applicationInfo.loadLabel(pm).toString();
        } catch (Throwable t) {
            label = packageInfo.packageName;
        }
        boolean isAllow = allowListSet.contains(packageInfo.packageName);
        return new AppInfo(label, packageInfo.packageName, hasFcm, isAllow);
    }

    private static void sortAppList(List<AppInfo> list, Set<String> allowListSet) {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        list.sort((left, right) -> {
            int leftGroup = allowListSet.contains(left.packageName) ? 0 : left.includeFcm ? 1 : 2;
            int rightGroup = allowListSet.contains(right.packageName) ? 0 : right.includeFcm ? 1 : 2;
            if (leftGroup != rightGroup) {
                return Integer.compare(leftGroup, rightGroup);
            }
            return collator.compare(left.name, right.name);
        });
    }

    private static List<AppInfo> buildAppList(
            PackageManager pm, Set<String> allowListSet) {
        List<AppInfo> result = new ArrayList<>();

        Set<String> packagesResolvedForFcm = queryFcmIntentPackages(pm, null);
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
            AppInfo info = createAppInfo(
                    pm, packageInfo, allowListSet, packagesResolvedForFcm);
            if (info != null) {
                result.add(info);
            }
        }
        sortAppList(result, allowListSet);
        return result;
    }

    private static AppInfo loadSingleApp(
            PackageManager pm, String packageName, Set<String> allowListSet) {
        try {
            int flags = PackageManager.GET_RECEIVERS
                    | PackageManager.GET_SERVICES
                    | PackageManager.MATCH_DISABLED_COMPONENTS;
            PackageInfo packageInfo = pm.getPackageInfo(packageName, flags);
            Set<String> resolvedForFcm = queryFcmIntentPackages(pm, packageName);
            return createAppInfo(pm, packageInfo, allowListSet, resolvedForFcm);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "loadSingleApp " + packageName + ": " + t);
            return null;
        }
    }

    private void updatePackageAsync(String packageName, boolean removed) {
        final boolean requireFullScan;
        synchronized (APPS_CACHE_LOCK) {
            requireFullScan = sCachedAppList == null || sAppListLoadInProgress;
            sAppListGeneration++;
            if (requireFullScan) {
                sCachedAppList = null;
            }
        }
        if (requireFullScan) {
            loadAppListAsync();
            return;
        }

        final PackageManager pm = getApplicationContext().getPackageManager();
        final Set<String> allowSnapshot = new HashSet<>(allowList);
        BG.execute(() -> {
            AppInfo refreshed = removed ? null : loadSingleApp(pm, packageName, allowSnapshot);
            final List<AppInfo> result;
            synchronized (APPS_CACHE_LOCK) {
                if (sCachedAppList == null) {
                    result = null;
                } else {
                    List<AppInfo> updated = structuralCopy(sCachedAppList);
                    updated.removeIf(item -> packageName.equals(item.packageName));
                    if (refreshed != null) {
                        updated.add(refreshed);
                    }
                    sortAppList(updated, allowSnapshot);
                    sCachedAppList = structuralCopy(updated);
                    result = sCachedAppList;
                }
            }
            MAIN_HANDLER.post(() -> {
                MainActivity activity = activeInstance.get();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (result == null) {
                    activity.loadAppListAsync();
                } else {
                    activity.applyAppList(structuralCopy(result), false);
                }
            });
        });
    }

    private static void invalidateAppListCache() {
        synchronized (APPS_CACHE_LOCK) {
            sCachedAppList = null;
            sAppListGeneration++;
        }
    }

    private void registerPackageChangeReceiver() {
        if (packageReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(packageChangeReceiver, filter);
            }
            packageReceiverRegistered = true;
        } catch (Throwable e) {
            Log.w(TAG, "registerPackageChangeReceiver: " + e);
        }
    }

    /** Rebuilds mutable AppInfo wrappers from the cached snapshot; never touches the cache itself. */
    private static List<AppInfo> structuralCopy(List<AppInfo> source) {
        List<AppInfo> copy = new ArrayList<>(source.size());
        for (AppInfo a : source) {
            copy.add(new AppInfo(a.name, a.packageName, a.includeFcm, false));
        }
        return copy;
    }

    private void loadAppListAsync() {
        // Reuse a completed scan when this process already built it (e.g. rotation/recreation),
        // so we skip the expensive getInstalledPackages + label + FCM-matching pass. If a scan is
        // already running, its completion is delivered to the latest live Activity via
        // activeInstance; rotations therefore do not queue duplicate package-manager work.
        final List<AppInfo> cached;
        final int scanGeneration;
        synchronized (APPS_CACHE_LOCK) {
            cached = sCachedAppList;
            scanGeneration = sAppListGeneration;
            if (cached == null) {
                if (sAppListLoadInProgress) {
                    return;
                }
                sAppListLoadInProgress = true;
            }
        }
        if (cached != null) {
            applyAppList(structuralCopy(cached), false);
            return;
        }

        // Snapshot state and the application-scoped PackageManager before leaving the main thread.
        // The worker no longer retains a destroyed Activity during a potentially slow package scan.
        final Set<String> allowSnapshot = new HashSet<>(allowList);
        final PackageManager packageManager = getApplicationContext().getPackageManager();
        BG.execute(() -> {
            List<AppInfo> scanned;
            try {
                scanned = buildAppList(packageManager, allowSnapshot);
            } catch (Throwable t) {
                Log.e(TAG, "buildAppList: " + t);
                scanned = new ArrayList<>();
            }
            final List<AppInfo> result;
            final boolean invalidated;
            synchronized (APPS_CACHE_LOCK) {
                invalidated = scanGeneration != sAppListGeneration;
                if (!invalidated && sCachedAppList == null) {
                    sCachedAppList = structuralCopy(scanned);
                }
                result = invalidated ? null : sCachedAppList;
                sAppListLoadInProgress = false;
            }
            MAIN_HANDLER.post(() -> {
                MainActivity activity = activeInstance.get();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (invalidated) {
                    // A package changed during enumeration. Discard the stale snapshot and start
                    // one coalesced scan against the new PackageManager state.
                    activity.loadAppListAsync();
                    return;
                }
                final boolean stillCurrent;
                synchronized (APPS_CACHE_LOCK) {
                    stillCurrent = scanGeneration == sAppListGeneration;
                }
                if (!stillCurrent) {
                    activity.loadAppListAsync();
                    return;
                }
                activity.applyAppList(structuralCopy(result), true);
            });
        });
    }

    /**
     * Called on the main thread once the app list is ready.
     *
     * @param initialLoad true for the first scan; the "cannot read app list" warning is only shown
     *                    then, so a recreated Activity (from cache) does not re-alert the user.
     */
    private void applyAppList(List<AppInfo> list, boolean initialLoad) {
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
        View loading = findViewById(R.id.loading_container);
        if (loading != null) {
            loading.setVisibility(View.GONE);
        } else {
            findViewById(R.id.progress_bar).setVisibility(View.GONE);
        }
        // applyFilter() also decides whether to show the empty-state hint instead of the grid.
        applyFilter();
        updateStats();
        refreshFabStatus();

        if (initialLoad
                && (list.isEmpty()
                || (list.size() == 1 && "sumicya.fcmself".equals(list.get(0).packageName)))) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("无法读取应用列表")
                    .setMessage("请在系统设置中允许 fcmself 读取应用列表，然后重新打开本应用。")
                    .setPositiveButton("确定", (dialog, which) -> {})
                    .show();
        }
    }


    /**
     * Header extends into the status-bar region. A compact 8dp fade ends before the first row, so
     * Fcmself remains readable without letting the grid's top corners enter the safe area.
     */
    private void setupEdgeToEdgeInsets() {
        if (headerSafe == null) {
            return;
        }
        final int basePadStart = headerSafe.getPaddingStart();
        final int basePadEnd = headerSafe.getPaddingEnd();
        final int basePadBottom = headerSafe.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(headerSafe, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPaddingRelative(basePadStart, bars.top, basePadEnd, basePadBottom);
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
            // The first row starts after the full header; no rounded card edge is drawn under the
            // compact gradient tail.
            int contentTop = h;
            int pl = recyclerView.getPaddingLeft();
            int pr = recyclerView.getPaddingRight();
            int pb = recyclerView.getPaddingBottom();
            if (recyclerView.getPaddingTop() != contentTop) {
                recyclerView.setPadding(pl, contentTop, pr, pb);
            }
        });

        if (bottomControls != null) {
            ViewGroup.MarginLayoutParams initial =
                    (ViewGroup.MarginLayoutParams) bottomControls.getLayoutParams();
            final int baseBottomMargin = initial.bottomMargin;
            ViewCompat.setOnApplyWindowInsetsListener(bottomControls, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                lp.bottomMargin = baseBottomMargin + bars.bottom;
                v.setLayoutParams(lp);
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(bottomControls);
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

        int fallbackPrimary = ContextCompat.getColor(this, R.color.colorPrimary);
        colorError = resolveThemeColor(
                android.R.attr.colorError,
                ContextCompat.getColor(this, R.color.status_error));
        colorOnSurface = resolveThemeColor(
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY);
        colorOnSurfaceVariant = resolveThemeColor(
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(this, R.color.status_disconnected));
        colorSurfaceContainerLow = resolveThemeColor(
                com.google.android.material.R.attr.colorSurfaceContainerLow, Color.WHITE);
        colorSurfaceContainerHigh = resolveThemeColor(
                com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.WHITE);
        fabConnectedContainer = resolveThemeColor(
                com.google.android.material.R.attr.colorPrimaryContainer, fallbackPrimary);
        fabConnectedContent = resolveThemeColor(
                com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE);
        // Keep the M3E dynamic Primary Container itself translucent. Do not pre-composite it onto
        // a neutral surface: that introduces a grey cast and defeats the lightweight glass effect.
        translucentPrimaryContainer =
                ColorUtils.setAlphaComponent(fabConnectedContainer, 184);
        colorErrorContainer = resolveThemeColor(
                com.google.android.material.R.attr.colorErrorContainer, colorError);
        colorOnErrorContainer = resolveThemeColor(
                com.google.android.material.R.attr.colorOnErrorContainer, Color.WHITE);
        tintDisconnected = ColorStateList.valueOf(colorOnSurfaceVariant);
        tintOnPrimaryContainer = ColorStateList.valueOf(fabConnectedContent);
        tintOnErrorContainer = ColorStateList.valueOf(colorOnErrorContainer);

        headerSafe = findViewById(R.id.header_safe);
        allStatsView = findViewById(R.id.all_stats);
        fcmStatsView = findViewById(R.id.fcm_stats);
        mainFab = findViewById(R.id.main_fab);
        bottomControls = findViewById(R.id.bottom_controls);
        bottomDock = findViewById(R.id.bottom_dock);
        feedbackContainer = findViewById(R.id.feedback_container);
        feedbackIcon = findViewById(R.id.feedback_icon);
        feedbackText = findViewById(R.id.feedback_text);
        feedbackAction = findViewById(R.id.feedback_action);
        filterCards = new MaterialCardView[]{
                findViewById(R.id.filter_all),
                findViewById(R.id.filter_fcm)
        };
        filterLabels = new TextView[]{
                findViewById(R.id.filter_all_label),
                findViewById(R.id.filter_fcm_label)
        };
        searchContainer = findViewById(R.id.search_container);
        searchToggleContainer = findViewById(R.id.search_toggle_container);
        searchInput = findViewById(R.id.search_input);
        searchToggle = findViewById(R.id.search_toggle);
        emptyHint = findViewById(R.id.empty_hint);
        applyHeaderScrim();
        setupGlassSurfaces();
        setupSearch();
        setupFilterTabs();
        setupEdgeToEdgeInsets();
        feedbackAction.setOnClickListener(v -> {
            Runnable undo = feedbackUndo;
            hideFeedback(true);
            if (undo != null) {
                undo.run();
            }
        });

        try {
            mainFab.setImageResource(android.R.drawable.ic_menu_manage);
        } catch (Throwable ignored) {
        }
        mainFab.setOnClickListener(v -> {
            if (searchExpanded) {
                toggleSearch();
                MAIN_HANDLER.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showFabMenu();
                    }
                }, 160L);
            } else {
                showFabMenu();
            }
        });
        refreshFabStatus();

        recyclerView = findViewById(R.id.recycler_view);
        defaultAppIcon = getPackageManager().getDefaultActivityIcon();
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        gridSpanCount = Math.max(2, Math.min(8, screenWidthDp / 112));
        recyclerView.setLayoutManager(new GridLayoutManager(this, gridSpanCount));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(32);
        recyclerView.setClipToPadding(false);
        // Prefetch a row ahead while flinging
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 24);
        recyclerView.setRecycledViewPool(pool);
        GridLayoutManager glm = (GridLayoutManager) recyclerView.getLayoutManager();
        if (glm != null) {
            glm.setInitialPrefetchItemCount(gridSpanCount * 2);
        }
        registerPackageChangeReceiver();

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
        if (packageReceiverRegistered) {
            try {
                unregisterReceiver(packageChangeReceiver);
            } catch (Throwable ignored) {
            } finally {
                packageReceiverRegistered = false;
            }
        }
        if (healthReceiverRegistered) {
            try {
                unregisterReceiver(healthResponseReceiver);
            } catch (Throwable ignored) {
            } finally {
                healthReceiverRegistered = false;
            }
        }
        healthNonce = null;
        healthStatusView = null;
        MAIN_HANDLER.removeCallbacks(applySearch);
        MAIN_HANDLER.removeCallbacks(hideFeedbackTask);
        searchContainer.animate().cancel();
        if (searchWidthAnimator != null) {
            searchWidthAnimator.cancel();
            searchWidthAnimator = null;
        }
        hideFeedback(true);
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
