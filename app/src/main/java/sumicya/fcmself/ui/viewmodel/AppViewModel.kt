package sumicya.fcmself.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sumicya.fcmself.core.Contract
import sumicya.fcmself.data.AppCatalog
import sumicya.fcmself.data.ConfigStore
import sumicya.fcmself.data.PackageChangeWatcher
import sumicya.fcmself.data.XposedServiceBridge
import sumicya.fcmself.health.HookHealthMonitor
import sumicya.fcmself.model.AppFilter
import sumicya.fcmself.model.AppInfo
import sumicya.fcmself.model.FilterMode

/**
 * UI state for the entire app, backed by the existing Java data layer.
 *
 * This ViewModel bridges the legacy callback-based architecture (AppCatalog.Listener,
 * XposedServiceBridge.Listener, PackageChangeWatcher.Callbacks) with Compose's
 * reactive StateFlow model.
 */
class AppViewModel(application: Application) : AndroidViewModel(application),
    AppCatalog.Listener,
    XposedServiceBridge.Listener,
    PackageChangeWatcher.Callbacks,
    HookHealthMonitor.StatusProvider {

    // ── App list state ──────────────────────────────────────────────────────

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _filterMode = MutableStateFlow(FilterMode.ALL)
    val filterMode: StateFlow<FilterMode> = _filterMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filteredApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val filteredApps: StateFlow<List<AppInfo>> = _filteredApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Service connection state ────────────────────────────────────────────

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _configReady = MutableStateFlow(false)
    val configReady: StateFlow<Boolean> = _configReady.asStateFlow()

    private val _configError = MutableStateFlow(false)
    val configError: StateFlow<Boolean> = _configError.asStateFlow()

    private val _configErrorMessage = MutableStateFlow<String?>(null)
    val configErrorMessage: StateFlow<String?> = _configErrorMessage.asStateFlow()

    // ── FCM event log state ─────────────────────────────────────────────────

    data class FcmLogEntry(
        val id: Long = System.nanoTime(),
        val timestamp: Long = System.currentTimeMillis(),
        val packageName: String,
        val action: String,
        val details: String = "",
    )

    private val _fcmLog = MutableStateFlow<List<FcmLogEntry>>(emptyList())
    val fcmLog: StateFlow<List<FcmLogEntry>> = _fcmLog.asStateFlow()

    // ── Health state ────────────────────────────────────────────────────────

    private val _healthStatus = MutableStateFlow<String>("")
    val healthStatus: StateFlow<String> = _healthStatus.asStateFlow()

    // ── Internal state ──────────────────────────────────────────────────────

    private val configStore = ConfigStore(application)
    private val packageWatcher = PackageChangeWatcher(this)
    private val healthMonitor = HookHealthMonitor(getApplication(), this)
    private var allApps: List<AppInfo> = emptyList()

    /** Captures FCM event broadcasts from system_server (BroadcastFix). */
    private val fcmEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(Contract.EXTRA_FCM_PACKAGE)
            val action = intent.getStringExtra(Contract.EXTRA_FCM_ACTION)
            val fix = intent.getStringExtra(Contract.EXTRA_FCM_FIX)
            val details = buildString {
                if (fix != null) append("修补: $fix")
                if (action != null) {
                    if (isNotEmpty()) append(" | ")
                    append("动作: $action")
                }
            }
            if (pkg != null) {
                logEvent(pkg, details.ifEmpty { "FCM 事件已处理" })
            }
        }
    }

    init {
        XposedServiceBridge.attach(this)
        AppCatalog.setListener(this)

        // Watch for package changes so the grid auto-refreshes
        packageWatcher.register(getApplication())

        // Register for real-time FCM event broadcasts from system_server
        registerFcmEventReceiver()

        // Trigger initial load
        viewModelScope.launch(Dispatchers.Main) {
            loadApps()
        }
    }

    override fun onCleared() {
        super.onCleared()
        AppCatalog.clearListener(this)
        XposedServiceBridge.detach(this)
        packageWatcher.unregister(getApplication())
        unregisterFcmEventReceiver()
        healthMonitor.release()
    }

    private fun registerFcmEventReceiver() {
        try {
            val filter = IntentFilter(Contract.FCM_EVENT_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getApplication<Application>().registerReceiver(
                    fcmEventReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                getApplication<Application>().registerReceiver(fcmEventReceiver, filter)
            }
        } catch (e: Exception) {
            android.util.Log.w("AppViewModel", "注册 FCM 事件接收器失败", e)
        }
    }

    private fun unregisterFcmEventReceiver() {
        try {
            getApplication<Application>().unregisterReceiver(fcmEventReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ── AppCatalog.Listener ─────────────────────────────────────────────────

    override fun isActive(): Boolean = true

    override fun onAppListReady(list: List<AppInfo>, initialLoad: Boolean) {
        allApps = list
        _apps.value = list
        _isLoading.value = false
        applyFilter()
        syncAllowState()
        logEvent("system", "应用列表已加载 (${list.size} 个)")
    }

    // ── XposedServiceBridge.Listener ────────────────────────────────────────

    override fun onXposedServiceConnected() {
        _serviceConnected.value = true
        configStore.load()
        _configReady.value = configStore.isAllowListLoaded() && !configStore.hasError()
        _configError.value = configStore.hasError()
        _configErrorMessage.value = configStore.lastErrorMessage()
        syncAllowState()
        logEvent("system", "Xposed 服务已连接")
    }

    override fun onXposedServiceLost() {
        _serviceConnected.value = false
        _configReady.value = false
        logEvent("system", "Xposed 服务已断开")
    }

    // ── PackageChangeWatcher.Callbacks ─────────────────────────────────────

    override fun onAllPackagesChanged() {
        AppCatalog.invalidate()
        loadApps()
        logEvent("system", "应用列表已刷新（批量变更）")
    }

    override fun onPackageChanged(packageName: String, removed: Boolean) {
        AppCatalog.refreshPackage(
            getApplication(),
            packageName,
            removed,
            configStore.allowListSnapshot(),
        )
        logEvent(packageName, if (removed) "已卸载" else "已更新")
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
        applyFilter()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilter()
    }

    fun toggleApp(info: AppInfo) {
        val stored = if (info.isAllow) {
            configStore.disallow(info.packageName)
        } else {
            configStore.allow(info.packageName)
        }
        if (stored) {
            info.isAllow = !info.isAllow
            syncAllowState()
            logEvent(
                info.packageName,
                if (info.isAllow) "已允许唤醒" else "已禁止唤醒",
            )
        }
    }

    fun enableAllFcm() {
        val fcmPackages = allApps
            .filter { it.includeFcm && !it.isAllow }
            .map { it.packageName }
        if (fcmPackages.isEmpty()) return
        configStore.allowAll(fcmPackages)
        syncAllowState()
        logEvent("system", "已启用全部 ${fcmPackages.size} 个 FCM 应用")
    }

    fun openDiagnostics(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(Contract.PACKAGE_GMS)
                setComponent(ComponentName(
                    Contract.PACKAGE_GMS, Contract.GMS_DIAGNOSTICS_ACTIVITY,
                ))
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _configError.value = true
            _configErrorMessage.value = "无法打开 FCM Diagnostics: ${e.message}"
        }
    }

    // ── HookHealthMonitor.StatusProvider ─────────────────────────────

    override fun isServiceConnected(): Boolean = _serviceConnected.value

    override fun isConfigReady(): Boolean = _configReady.value

    /**
     * Sends a nonce-tagged health request to all hooked processes and streams
     * the rendered report into [healthStatus] when responses arrive.
     */
    fun requestHealthCheck() {
        _healthStatus.value = "正在检查…"
        healthMonitor.request(object : HookHealthMonitor.Output {
            override fun onHealthText(text: String) {
                _healthStatus.value = text
            }
        })
    }

    fun isSwitchEnabled(key: String): Boolean = configStore.getBoolean(key)

    fun setSwitchEnabled(key: String, value: Boolean): Boolean {
        val result = configStore.setBoolean(key, value)
        if (result) {
            logEvent("system", "设置 ${key} = $value")
        }
        return result
    }

    // ── Logging ─────────────────────────────────────────────────────────────

    fun logEvent(packageName: String, details: String, action: String = "") {
        val entry = FcmLogEntry(
            packageName = packageName,
            action = action,
            details = details,
        )
        _fcmLog.value = listOf(entry) + _fcmLog.value.take(999)
    }

    fun clearLog() {
        _fcmLog.value = emptyList()
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private fun loadApps() {
        AppCatalog.load(getApplication(), configStore.allowListSnapshot())
    }

    fun refreshApps() {
        AppCatalog.load(getApplication(), configStore.allowListSnapshot())
    }

    private fun syncAllowState() {
        for (info in allApps) {
            info.isAllow = configStore.isAllowed(info.packageName)
        }
        applyFilter()
    }

    private fun applyFilter() {
        _filteredApps.value = AppFilter.apply(
            allApps,
            _filterMode.value,
            _searchQuery.value,
        )
    }
}