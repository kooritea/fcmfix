package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;

import sumicya.fcmself.util.XposedUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

public class ReconnectManagerFix extends XposedModule {
    // TODO(experimental): 这是整套代码里最复杂的自配置 hook（反射探测 GMS 内部、写本地
    //   fcmfix_config、动态找 hook 点）。已做防御性加固，但整体结构仍可考虑更彻底的重构。
    //   需真机验证：GMS 升级后的重新探测、heartbeat/reconn 间隔语义、以及 across 版本差异。

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

    public ReconnectManagerFix(ClassLoader classLoader) {
        super(classLoader);
        this.addButton();
        this.startHookGcmServiceStart();
        reportHookHealth("ReconnectManagerFix", "等待 GMS 计时器配置");
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
        this.GcmChimeraService = XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService", classLoader);
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
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!logReceiverRegistered) {
                        IntentFilter intentFilter = new IntentFilter("sumicya.fcmself.log");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            context.registerReceiver(
                                    logBroadcastReceive,
                                    intentFilter,
                                    Context.RECEIVER_EXPORTED);
                        } else {
                            context.registerReceiver(logBroadcastReceive, intentFilter);
                        }
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
        final SharedPreferences sharedPreferences = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        String versionName = pi.versionName != null ? pi.versionName : "";
        long versionCode = pi.getLongVersionCode();
        if (versionCode < 213916046) {
            printLog("当前为旧版GMS，请使用0.4.1版本FCMFIX，禁用重连修复功能");
            return;
        }
        if (!sharedPreferences.getBoolean("isInit", false) || !configVersion.equals(sharedPreferences.getString("config_version", ""))) {
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
            return;
        }
        startHook();
    }

    protected void startHook() {
        // TODO(experimental): cachedHbInterval / cachedConnInterval（来自 heartbeatInterval /
        //   reconnInterval 配置）的语义与默认值可实验性调参，需真机观察心跳/重连实际效果。
        if (hooksInstalled) {
            // Refresh cached intervals only
            SharedPreferences prefs = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
            cachedHbInterval = prefs.getLong("heartbeatInterval", 0L);
            cachedConnInterval = prefs.getLong("reconnInterval", 0L);
            updateCachedAlarmTypePath(prefs.getString("timer_alarm_type_property", ""));
            return;
        }

        final SharedPreferences sharedPreferences = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        updateCachedAlarmTypePath(sharedPreferences.getString("timer_alarm_type_property", ""));
        cachedHbInterval = sharedPreferences.getLong("heartbeatInterval", 0L);
        cachedConnInterval = sharedPreferences.getLong("reconnInterval", 0L);

        String timerClassName = sharedPreferences.getString("timer_class", "");
        String setTimeoutMethod = sharedPreferences.getString("timer_settimeout_method", "");
        printLog("timer_class: " + timerClassName, true);
        printLog("timer_alarm_type_property: " + cachedAlarmTypePath, true);
        printLog("timer_settimeout_method: " + setTimeoutMethod, true);

        // Defensive: a stale/blank target (e.g. GMS updated, or detection never ran) must degrade
        // gracefully instead of throwing. The next GMS restart re-runs detection and re-hooks.
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
            // Verify the target method still exists before hooking so a signature change surfaces
            // as a clear message rather than a raw NoSuchMethodError mid-start.
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
                // Resolve the max-long field once and cache
                Field maxField = cachedMaxLongField;
                if (maxField == null) {
                    long maxFieldValue = 0L;
                    for (Field field : timerClazz.getDeclaredFields()) {
                        if (field.getType() == long.class) {
                            long fieldValue = (long) XposedHelpers.getObjectField(param.thisObject, field.getName());
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
                final Field finalMaxField = maxField;
                final Object timerObj = param.thisObject;
                long delay = (long) param.args[0] + 5000;
                RECONNECT_HANDLER.postDelayed(() -> {
                    try {
                        long nextConnectionTime = XposedHelpers.getLongField(timerObj, finalMaxField.getName());
                        if (nextConnectionTime != 0 && nextConnectionTime - SystemClock.elapsedRealtime() < -60000) {
                            context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                            printLog("Send broadcast GCM_RECONNECT", true);
                        }
                    } catch (Throwable ignored) {
                    }
                }, delay);
            }
        });

        hooksInstalled = true;
        reportHookHealth("ReconnectManagerFix", "GMS 心跳/重连 Hook 已安装");
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
            Class<?> heartbeatChimeraAlarm = XposedHelpers.findClass("com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm", classLoader);
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
                if (Modifier.isFinal(timerClassField.getModifiers()) && Modifier.isPublic(timerClassField.getModifiers())) {
                    final Class<?> alarmClass = timerClassField.getType();
                    final boolean[] isFinish = {false};
                    Constructor<?> alarmClassConstructor = null;
                    for (Constructor<?> constructor : alarmClass.getConstructors()) {
                        Class<?>[] pts = constructor.getParameterTypes();
                        if (pts.length >= 3
                                && pts[0] == Context.class && pts[1] == int.class && pts[2] == String.class) {
                            if (alarmClassConstructor == null || pts.length > alarmClassConstructor.getParameterCount()) {
                                alarmClassConstructor = constructor;
                            }
                        }
                    }
                    if (alarmClassConstructor == null) throw new Throwable("未找到构造函数");
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
                                            && XposedHelpers.getObjectField(param.thisObject, field.getName()) == param.args[2]) {
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
            this.sendNotification("自动更新配置文件失败", "未能找到hook点，已禁用重连修复和固定心跳功能。");
        }
        editor.apply();
    }

    private void addButton() {
        XposedHelpers.findAndHookMethod("com.google.android.gms.gcm.GcmChimeraDiagnostics", classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
            @SuppressLint("SetTextI18n")
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                if (!(param.thisObject instanceof ContextWrapper)) {
                    return;
                }
                Object windowValue = XposedHelpers.callMethod(param.thisObject, "getWindow");
                if (!(windowValue instanceof Window)) {
                    return;
                }
                View content = ((Window) windowValue).getDecorView()
                        .findViewById(android.R.id.content);
                if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) {
                    return;
                }
                View firstChild = ((ViewGroup) content).getChildAt(0);
                if (!(firstChild instanceof LinearLayout)
                        || ((LinearLayout) firstChild).getChildCount() == 0) {
                    return;
                }
                View buttonContainer = ((LinearLayout) firstChild).getChildAt(0);
                if (!(buttonContainer instanceof LinearLayout)) {
                    return;
                }
                LinearLayout linearLayout = (LinearLayout) buttonContainer;
                ContextWrapper activity = (ContextWrapper) param.thisObject;

                Button reConnectButton = new Button(activity);
                reConnectButton.setText("RECONNECT");
                reConnectButton.setOnClickListener(view -> {
                    context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                    printLog("Send broadcast GCM_RECONNECT", true);
                });
                linearLayout.addView(reConnectButton);

                Button openFcmFixButton = new Button(activity);
                openFcmFixButton.setText("打开FCMFIX");
                openFcmFixButton.setOnClickListener(view -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage("sumicya.fcmself");
                    intent.setComponent(new ComponentName("sumicya.fcmself", "sumicya.fcmself.MainActivity"));
                    context.startActivity(intent);
                });
                linearLayout.addView(openFcmFixButton);
            }
        });
    }
}
