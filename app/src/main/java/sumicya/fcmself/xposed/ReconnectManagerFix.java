package sumicya.fcmself.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

/**
 * GMS heartbeat / reconnect interval fix. Uses reflective detection of GMS timer internals and
 * persists the discovered paths in a local SharedPreferences file. Detection re-runs when GMS
 * version changes. This remains the most fragile subsystem; failures degrade gracefully.
 */
public class ReconnectManagerFix extends XposedModule {

    private Class<?> GcmChimeraService;
    private String GcmChimeraServiceLogMethodName;
    private boolean startHookFlag = false;
    private boolean hooksInstalled = false;
    private boolean logReceiverRegistered = false;

    // Cached from SharedPreferences after successful resolution
    private String cachedAlarmTypePath;
    private volatile String[] cachedAlarmTypePathParts;
    private volatile long cachedHbInterval;
    private volatile long cachedConnInterval;
    private volatile Field cachedMaxLongField;

    private static final Handler RECONNECT_HANDLER = new Handler(Looper.getMainLooper());
    private final Object reconnectCheckLock = new Object();
    private ReconnectCheck pendingHeartbeatCheck;
    private ReconnectCheck pendingConnectionCheck;

    private final class ReconnectCheck implements Runnable {
        private final boolean heartbeat;
        private final Object timerObject;
        private final Field nextTimeField;

        ReconnectCheck(boolean heartbeat, Object timerObject, Field nextTimeField) {
            this.heartbeat = heartbeat;
            this.timerObject = timerObject;
            this.nextTimeField = nextTimeField;
        }

        @Override
        public void run() {
            synchronized (reconnectCheckLock) {
                ReconnectCheck current = heartbeat
                        ? pendingHeartbeatCheck
                        : pendingConnectionCheck;
                if (current != this) {
                    return;
                }
                if (heartbeat) {
                    pendingHeartbeatCheck = null;
                } else {
                    pendingConnectionCheck = null;
                }
            }
            try {
                long nextConnectionTime = XposedHelpers.getLongField(
                        timerObject, nextTimeField.getName());
                if (nextConnectionTime != 0
                        && nextConnectionTime - SystemClock.elapsedRealtime() < -60_000L) {
                    context.sendBroadcast(new Intent(
                            "com.google.android.intent.action.GCM_RECONNECT"));
                    printLog("Send broadcast GCM_RECONNECT", true);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public ReconnectManagerFix(ClassLoader classLoader) {
        super(classLoader);
        this.startHookGcmServiceStart();
        reportHookHealth("ReconnectManagerFix", "等待 GMS 计时器配置");
    }

    private void scheduleReconnectCheck(
            String alarmType, Object timerObject, Field nextTimeField, long delayMs) {
        boolean heartbeat = "GCM_HB_ALARM".equals(alarmType);
        ReconnectCheck next = new ReconnectCheck(heartbeat, timerObject, nextTimeField);
        synchronized (reconnectCheckLock) {
            ReconnectCheck previous = heartbeat
                    ? pendingHeartbeatCheck
                    : pendingConnectionCheck;
            if (previous != null) {
                RECONNECT_HANDLER.removeCallbacks(previous);
            }
            if (heartbeat) {
                pendingHeartbeatCheck = next;
            } else {
                pendingConnectionCheck = next;
            }
        }
        if (!RECONNECT_HANDLER.postDelayed(next, Math.max(0L, delayMs))) {
            synchronized (reconnectCheckLock) {
                if (heartbeat && pendingHeartbeatCheck == next) {
                    pendingHeartbeatCheck = null;
                } else if (!heartbeat && pendingConnectionCheck == next) {
                    pendingConnectionCheck = null;
                }
            }
        }
    }

    @Override
    protected void onCanReadConfig() throws Throwable {
        if (startHookFlag) {
            this.checkVersion();
            onUpdateConfig();
        } else {
            startHookFlag = true;
        }
    }

    private void startHookGcmServiceStart() {
        this.GcmChimeraService = XposedHelpers.findClass(
                "com.google.android.gms.gcm.GcmChimeraService", classLoader);
        try {
            for (Method method : this.GcmChimeraService.getMethods()) {
                if (method.getParameterTypes().length == 2
                        && method.getParameterTypes()[0] == String.class
                        && method.getParameterTypes()[1] == Object[].class) {
                    this.GcmChimeraServiceLogMethodName = method.getName();
                    break;
                }
            }
            XposedHelpers.findAndHookMethod(this.GcmChimeraService, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!logReceiverRegistered) {
                        IntentFilter intentFilter = new IntentFilter("sumicya.fcmself.log");
                        // minSdk 36: always use the Tiramisu+ overload.
                        context.registerReceiver(
                                logBroadcastReceive,
                                intentFilter,
                                Context.RECEIVER_EXPORTED);
                        logReceiverRegistered = true;
                    }
                    if (startHookFlag) {
                        checkVersion();
                    } else {
                        startHookFlag = true;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(this.GcmChimeraService, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    if (logReceiverRegistered) {
                        try {
                            context.unregisterReceiver(logBroadcastReceive);
                        } catch (Throwable ignored) {
                        } finally {
                            logReceiverRegistered = false;
                        }
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("GcmChimeraService hook 失败");
        }
    }

    public static final String configVersion = "v3";

    private void checkVersion() throws Throwable {
        final SharedPreferences sharedPreferences =
                context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        String versionName = pi.versionName != null ? pi.versionName : "";
        long versionCode = pi.getLongVersionCode();
        // Very old GMS builds are no longer supported (personal build policy).
        if (versionCode < 213916046) {
            printLog("当前为过旧 GMS，已禁用重连修复功能");
            reportHookHealth("ReconnectManagerFix", "GMS 过旧，已禁用");
            return;
        }
        if (!sharedPreferences.getBoolean("isInit", false)
                || !configVersion.equals(sharedPreferences.getString("config_version", ""))) {
            printLog("fcmfix_config init", true);
            sharedPreferences.edit()
                    .putBoolean("isInit", true)
                    .putBoolean("enable", false)
                    .putLong("heartbeatInterval", 0L)
                    .putLong("reconnInterval", 0L)
                    .putString("gms_version", versionName)
                    .putLong("gms_version_code", versionCode)
                    .putString("config_version", configVersion)
                    .putString("timer_class", "")
                    .putString("timer_settimeout_method", "")
                    .putString("timer_alarm_type_property", "")
                    .apply();
            printLog("正在更新hook位置", true);
            findAndUpdateHookTarget(sharedPreferences);
            return;
        }
        String configuredVersionName = sharedPreferences.getString("gms_version", "");
        long configuredVersionCode = sharedPreferences.getLong("gms_version_code", 0);
        if (!versionName.equals(configuredVersionName) || versionCode != configuredVersionCode) {
            printLog("gms已更新: " + configuredVersionName + "(" + configuredVersionCode + ")"
                    + "->" + versionName + "(" + versionCode + ")", true);
            sharedPreferences.edit()
                    .putString("gms_version", versionName)
                    .putLong("gms_version_code", versionCode)
                    .putBoolean("enable", false)
                    .apply();
            printLog("正在更新hook位置", true);
            findAndUpdateHookTarget(sharedPreferences);
            return;
        }
        if (!sharedPreferences.getBoolean("enable", false)) {
            printLog("当前配置文件enable标识为false，FCMFIX退出", true);
            reportHookHealth("ReconnectManagerFix", "探测未成功，功能关闭");
            return;
        }
        startHook();
    }

    protected void startHook() {
        if (hooksInstalled) {
            // Refresh cached intervals only
            SharedPreferences prefs = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
            cachedHbInterval = prefs.getLong("heartbeatInterval", 0L);
            cachedConnInterval = prefs.getLong("reconnInterval", 0L);
            updateCachedAlarmTypePath(prefs.getString("timer_alarm_type_property", ""));
            return;
        }

        final SharedPreferences sharedPreferences =
                context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        updateCachedAlarmTypePath(sharedPreferences.getString("timer_alarm_type_property", ""));
        cachedHbInterval = sharedPreferences.getLong("heartbeatInterval", 0L);
        cachedConnInterval = sharedPreferences.getLong("reconnInterval", 0L);

        String timerClassName = sharedPreferences.getString("timer_class", "");
        String setTimeoutMethod = sharedPreferences.getString("timer_settimeout_method", "");
        printLog("timer_class: " + timerClassName, true);
        printLog("timer_alarm_type_property: " + cachedAlarmTypePath, true);
        printLog("timer_settimeout_method: " + setTimeoutMethod, true);

        if (timerClassName.isEmpty() || setTimeoutMethod.isEmpty()) {
            printLog("reconnect fix skipped: missing timer_class/timer_settimeout_method in config", true);
            return;
        }
        final Class<?> timerClazz = XposedHelpers.findClassIfExists(timerClassName, classLoader);
        if (timerClazz == null) {
            printLog("reconnect fix skipped: timer class no longer present: " + timerClassName, true);
            return;
        }
        try {
            timerClazz.getDeclaredMethod(setTimeoutMethod, long.class);
        } catch (NoSuchMethodException e) {
            printLog("reconnect fix skipped: no long.setTimeout method " + setTimeoutMethod
                    + " on " + timerClassName, true);
            return;
        }

        XposedHelpers.findAndHookMethod(timerClazz, "toString", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                if (cachedHbInterval <= 1000 && cachedConnInterval <= 1000) {
                    return;
                }
                String alarmType = getCachedAlarmType(param.thisObject);
                if ("GCM_HB_ALARM".equals(alarmType) || "GCM_CONN_ALARM".equals(alarmType)) {
                    param.setResult(param.getResult() + "[fcmfix locked]");
                }
            }
        });

        XposedHelpers.findAndHookMethod(timerClazz, setTimeoutMethod, long.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                String alarmType = getCachedAlarmType(param.thisObject);
                if ("GCM_HB_ALARM".equals(alarmType)) {
                    if (cachedHbInterval > 1000) {
                        param.args[0] = cachedHbInterval;
                    }
                } else if ("GCM_CONN_ALARM".equals(alarmType)) {
                    if (cachedConnInterval > 1000) {
                        param.args[0] = cachedConnInterval;
                    }
                }
            }

            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                String alarmType = getCachedAlarmType(param.thisObject);
                if (!"GCM_HB_ALARM".equals(alarmType) && !"GCM_CONN_ALARM".equals(alarmType)) {
                    return;
                }
                Field maxField = cachedMaxLongField;
                if (maxField == null) {
                    long maxFieldValue = 0L;
                    for (Field field : timerClazz.getDeclaredFields()) {
                        if (field.getType() == long.class) {
                            long fieldValue = (long) XposedHelpers.getObjectField(
                                    param.thisObject, field.getName());
                            if (maxField == null || fieldValue > maxFieldValue) {
                                maxField = field;
                                maxFieldValue = fieldValue;
                            }
                        }
                    }
                    if (maxField != null) {
                        cachedMaxLongField = maxField;
                    }
                }
                if (maxField == null) {
                    return;
                }
                long delay = (long) param.args[0] + 5_000L;
                scheduleReconnectCheck(alarmType, param.thisObject, maxField, delay);
            }
        });

        hooksInstalled = true;
        reportHookHealth("ReconnectManagerFix", "GMS 心跳/重连 Hook 已安装，检查已去重");
    }

    private void updateCachedAlarmTypePath(String path) {
        cachedAlarmTypePath = path;
        cachedAlarmTypePathParts = path.isEmpty() ? null : path.split("\\.");
    }

    private String getCachedAlarmType(Object timerObject) {
        String[] pathParts = cachedAlarmTypePathParts;
        if (pathParts == null) {
            return null;
        }
        Object value = timerObject;
        try {
            for (String fieldName : pathParts) {
                value = XposedHelpers.getObjectField(value, fieldName);
            }
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private final BroadcastReceiver logBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (!"sumicya.fcmself.log".equals(intent.getAction())) {
                return;
            }
            if (GcmChimeraServiceLogMethodName == null) {
                XposedBridge.log("[fcmfix] " + intent.getStringExtra("text"));
                return;
            }
            try {
                XposedHelpers.callStaticMethod(GcmChimeraService, GcmChimeraServiceLogMethodName,
                        new Class<?>[]{String.class, Object[].class},
                        "[fcmfix] " + intent.getStringExtra("text"), null);
            } catch (Throwable e) {
                XposedBridge.log("输出日志到fcm失败： [fcmfix] " + intent.getStringExtra("text"));
            }
        }
    };

    private void findAndUpdateHookTarget(final SharedPreferences sharedPreferences) {
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        try {
            Class<?> heartbeatChimeraAlarm = XposedHelpers.findClass(
                    "com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm", classLoader);
            Class<?> timerClass = heartbeatChimeraAlarm.getConstructors()[0].getParameterTypes()[3];
            if (timerClass.getDeclaredMethods().length == 0) {
                timerClass = timerClass.getSuperclass();
            }
            editor.putString("timer_class", timerClass.getName());
            for (Method method : timerClass.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0] == long.class
                        && Modifier.isFinal(method.getModifiers())
                        && Modifier.isPublic(method.getModifiers())) {
                    editor.putString("timer_settimeout_method", method.getName());
                    break;
                }
            }
            for (final Field timerClassField : timerClass.getDeclaredFields()) {
                if (Modifier.isFinal(timerClassField.getModifiers())
                        && Modifier.isPublic(timerClassField.getModifiers())) {
                    final Class<?> alarmClass = timerClassField.getType();
                    final boolean[] isFinish = {false};
                    Constructor<?> alarmClassConstructor = null;
                    for (Constructor<?> constructor : alarmClass.getConstructors()) {
                        Class<?>[] pts = constructor.getParameterTypes();
                        if (pts.length >= 3
                                && pts[0] == Context.class
                                && pts[1] == int.class
                                && pts[2] == String.class) {
                            if (alarmClassConstructor == null
                                    || pts.length > alarmClassConstructor.getParameterCount()) {
                                alarmClassConstructor = constructor;
                            }
                        }
                    }
                    if (alarmClassConstructor == null) {
                        throw new Throwable("未找到构造函数");
                    }
                    XposedBridge.hookMethod(alarmClassConstructor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            if (isFinish[0]) {
                                return;
                            }
                            for (Field field : alarmClass.getDeclaredFields()) {
                                if (field.getType() == String.class
                                        && Modifier.isFinal(field.getModifiers())
                                        && Modifier.isPrivate(field.getModifiers())) {
                                    if (param.args[2] != null
                                            && XposedHelpers.getObjectField(
                                            param.thisObject, field.getName()) == param.args[2]) {
                                        String path = timerClassField.getName() + "." + field.getName();
                                        sharedPreferences.edit()
                                                .putString("timer_alarm_type_property", path)
                                                .putBoolean("enable", true)
                                                .apply();
                                        updateCachedAlarmTypePath(path);
                                        isFinish[0] = true;
                                        printLog("更新hook位置成功", true);
                                        sendNotification("自动更新配置文件成功");
                                        startHook();
                                        return;
                                    }
                                }
                            }
                            printLog("自动寻找hook点失败: 未找到目标方法", true);
                        }
                    });
                    break;
                }
            }
        } catch (Throwable e) {
            editor.putBoolean("enable", false);
            printLog("自动寻找hook点失败: " + e, true);
            this.sendNotification("自动更新配置文件失败",
                    "未能找到hook点，已禁用重连修复和固定心跳功能。");
            reportHookHealth("ReconnectManagerFix", "探测失败，已禁用");
        }
        editor.apply();
    }

}
