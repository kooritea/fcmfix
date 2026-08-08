package com.kooritea.fcmfix.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.WorkSource;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XC_MethodReplacement;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class OplusProxyFix extends XposedModule {

    private static Object s_oplusProxyWakeLock = null;
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;
    private static Context s_context = null;
    private static Object s_powerExemptionManager = null;
    private static Method s_addToTempAllowListMethod = null;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHookOplusProxyWakeLock();
            this.startHookOplusProxyBroadcast();
        } catch (Throwable e) {
            printLog("hook error OplusProxy:" + e.getMessage());
        }
        try {
            this.startHookRegisterGmsRestrictObserver();
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver:" + e.getMessage());
        }
        try {
            this.startHookUpdateGmsRestrict();
        } catch (Throwable e) {
            printLog("hook error updateGmsRestrict:" + e.getMessage());
        }
        try {
            this.startHookIsGoogleRestricInfoOn();
        } catch (Throwable e) {
            printLog("hook error isGoogleRestricInfoOn:" + e.getMessage());
        }
        // ===== 新增：fcmfuck 的暴力绕过 =====
        try {
            this.startHookOplusAutoStartRestrictions();
        } catch (Throwable e) {
            printLog("hook error AutoStartRestrictions:" + e.getMessage());
        }
        try {
            this.startHookBroadcastIntentLocked();
        } catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
        try {
            this.startHookBroadcastWithFeature();
        } catch (Throwable e) {
            printLog("hook error broadcastWithFeature:" + e.getMessage());
        }
    }

    // ==================== 原有方法 ====================

    private void startHookOplusProxyWakeLock() throws Exception {
        Class<?> wakeLockClass = XposedHelpers.findClass(
                "com.android.server.power.OplusProxyWakeLock", classLoader);
        for (Constructor<?> ctor : wakeLockClass.getDeclaredConstructors()) {
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    s_oplusProxyWakeLock = param.thisObject;
                }
            });
        }
    }

    private void startHookOplusProxyBroadcast() throws Exception {
        Class<?> oplusProxyBroadcastClass = XposedHelpers.findClass(
                "com.android.server.am.OplusProxyBroadcast", classLoader);
        Class<?> resultEnum = XposedHelpers.findClass(
                "com.android.server.am.OplusProxyBroadcast$RESULT", classLoader);
        Object notIncludeValue = XposedHelpers.getStaticObjectField(resultEnum, "NOT_INCLUDE");

        XposedBridge.hookAllMethods(oplusProxyBroadcastClass, "shouldProxy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length < 7) return;
                String pkgName = null;
                String action = null;
                // 精确匹配：第5个参数是pkgName，第6个是action
                if (param.args[5] instanceof String) pkgName = (String) param.args[5];
                if (param.args[6] instanceof String) action = (String) param.args[6];
                if (action != null && isFCMAction(action) && pkgName != null && targetIsAllow(pkgName)) {
                    printLog("shouldProxy bypass: pkg=" + pkgName + ", action=" + action);
                    param.setResult(notIncludeValue);
                }
            }
        });
    }

    public static void unfreeze(String target) {
        if (s_oplusProxyWakeLock == null) return;

        WorkSource ws = new WorkSource();
        String tag = "FCMXX";
        int uid = getTargetUidFromPackageName(target);

        // 尝试 1: 传 UID, 4参数 (fcmfix 原版)
        if (uid >= 0) {
            try {
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                printLog("unfreeze(uid,4) " + target);
                return;
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                printLog("unfreeze(uid,3) " + target);
                return;
            } catch (Throwable ignored) {}
        }

        // 尝试 2: 传包名, 4参数 (fcmfuck 方式)
        try {
            XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", target, ws, tag, "FCMFix");
            printLog("unfreeze(pkg,4) " + target);
            return;
        } catch (Throwable ignored) {}

        // 尝试 3: 传包名, 3参数
        try {
            XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", target, ws, tag);
            printLog("unfreeze(pkg,3) " + target);
        } catch (Throwable ignored) {}
    }

    private void startHookRegisterGmsRestrictObserver() {
        XposedHelpers.findAndHookMethod(
                "com.android.server.hans.scene.OplusBgSceneManager", classLoader,
                "registerGmsRestrictObserver", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookUpdateGmsRestrict() {
        XposedHelpers.findAndHookMethod(
                "com.android.server.hans.scene.OplusBgSceneManager", classLoader,
                "updateGmsRestrict", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookIsGoogleRestricInfoOn() {
        XposedHelpers.findAndHookMethod(
                "com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", classLoader,
                "isGoogleRestricInfoOn", int.class, XC_MethodReplacement.returnConstant(false));
    }

    // ==================== 新增：fcmfuck 的暴力绕过 ====================

    /**
     * 绕过 OPPO/ColorOS 的 7 个自启动/广播拦截点
     * 对应 fcmfuck e.smali 中的 e() 方法
     */
    private void startHookOplusAutoStartRestrictions() {
        // 强制允许自启动 (返回 TRUE)
        hookReturnConstant("com.android.server.am.BroadcastQueueInjector",
                "checkApplicationAutoStart", Boolean.TRUE);
        hookReturnConstant("com.android.server.am.BroadcastQueueImpl",
                "checkApplicationAutoStart", Boolean.TRUE);
        hookReturnConstant("com.android.server.am.BroadcastQueueModernStubImpl",
                "checkApplicationAutoStart", Boolean.TRUE);
        hookReturnConstant("com.android.server.am.AutoStartManagerServiceStubImpl",
                "isAllowStartService", Boolean.TRUE);

        // 强制取消拦截 (返回 FALSE)
        hookReturnConstant("com.android.server.am.BroadcastQueueModernStubImpl",
                "checkReceiverIfRestricted", Boolean.FALSE);
        hookReturnConstant("com.android.server.am.SmartPowerService",
                "shouldInterceptBroadcast", Boolean.FALSE);
        hookReturnConstant("com.android.server.am.OplusAppStartupManager",
                "shouldPreventSendReceiverReal", Boolean.FALSE);
    }

    /**
     * Hook broadcastIntentLocked，在广播分发时：
     * 1. 添加 FLAG_INCLUDE_STOPPED_PACKAGES
     * 2. 调用 unfreeze 解冻目标应用
     * 3. 将值为 -1 的 Integer 参数改为 11
     * 对应 fcmfuck e.smali 中的 l() 方法 (p0==2 分支)
     */
    private void startHookBroadcastIntentLocked() {
        String[] targetClasses = {
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        for (String className : targetClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                for (Method m : clazz.getDeclaredMethods()) {
                    if ("broadcastIntentLocked".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Intent intent = findIntentInArgs(param.args);
                                if (intent == null) return;
                                if (!isFCMAction(intent.getAction())) return;

                                String targetPkg = getPackageFromIntent(intent);
                                if (targetPkg == null || !targetIsAllow(targetPkg)) return;

                                // 1. 添加 FLAG_INCLUDE_STOPPED_PACKAGES
                                intent.addFlags(0x20);

                                // 2. 解冻
                                unfreeze(targetPkg);

                                // 3. 将值为 -1 的 Integer 参数改为 11
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof Integer
                                            && (Integer) param.args[i] == -1) {
                                        param.args[i] = 11;
                                    }
                                }
                            }
                        });
                        printLog("hooked broadcastIntentLocked in " + className);
                    }
                }
            } catch (Throwable t) {
                // 非 OPPO 设备找不到类，静默跳过
            }
        }
    }

    /**
     * Hook broadcastIntentWithFeature / broadcastIntent
     * 当 GMS 发送 C2DM 广播时，将目标应用临时加入电池白名单 (2秒)
     * 对应 fcmfuck e.smali 中的 k() 方法
     */
    private void startHookBroadcastWithFeature() {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", classLoader);

            // 获取 mContext 字段
            Field mContextField = amsClass.getDeclaredField("mContext");
            mContextField.setAccessible(true);

            String methodName = Build.VERSION.SDK_INT >= 31
                    ? "broadcastIntentWithFeature" : "broadcastIntent";

            for (Method m : amsClass.getDeclaredMethods()) {
                if (methodName.equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Intent intent = findIntentInArgs(param.args);
                            if (intent == null) return;

                            String action = intent.getAction();
                            if (!"com.google.android.c2dm.intent.RECEIVE".equals(action)
                                    && !isFCMAction(action)) return;

                            String targetPkg = getPackageFromIntent(intent);
                            if (targetPkg == null || !targetIsAllow(targetPkg)) return;

                            // 添加 FLAG_INCLUDE_STOPPED_PACKAGES
                            if ((intent.getFlags() & 0x20) == 0) {
                                intent.addFlags(0x20);
                            }

                            // 临时电池白名单 (Android 12+)
                            if (Build.VERSION.SDK_INT >= 31) {
                                addToTemporaryAllowList(targetPkg, param.thisObject, mContextField);
                            }
                        }
                    });
                    printLog("hooked " + methodName + " for PowerExemption");
                    break; // 只 hook 第一个匹配的
                }
            }
        } catch (Throwable t) {
            printLog("hookBroadcastWithFeature error: " + t.getMessage());
        }
    }

    /**
     * 通过反射调用 PowerExemptionManager.addToTemporaryAllowList
     * 避免直接 new PowerExemptionManager 导致编译报错
     */
    private void addToTemporaryAllowList(String targetPkg, Object amsInstance, Field mContextField) {
        try {
            if (s_context == null) {
                s_context = (Context) mContextField.get(amsInstance);
            }
            if (s_context == null) return;

            if (s_powerExemptionManager == null) {
                // 通过反射获取 PowerExemptionManager 实例
                Class<?> pemClass = classLoader.loadClass("android.os.PowerExemptionManager");
                // 尝试通过 Context.getSystemService 获取
                try {
                    s_powerExemptionManager = XposedHelpers.callMethod(
                            s_context, "getSystemService", pemClass);
                } catch (Throwable t) {
                    // 降级：通过反射构造
                    Constructor<?> ctor = pemClass.getDeclaredConstructor(Context.class);
                    ctor.setAccessible(true);
                    s_powerExemptionManager = ctor.newInstance(s_context);
                }

                if (s_powerExemptionManager != null) {
                    s_addToTempAllowListMethod = pemClass.getDeclaredMethod(
                            "addToTemporaryAllowList",
                            String.class, int.class, String.class, long.class);
                    s_addToTempAllowListMethod.setAccessible(true);
                }
            }

            if (s_addToTempAllowListMethod != null && s_powerExemptionManager != null) {
                s_addToTempAllowListMethod.invoke(
                        s_powerExemptionManager, targetPkg, 0x66, "GOOGLE_C2DM", 2000L);
                printLog("addToTemporaryAllowList: " + targetPkg);
            }
        } catch (Throwable t) {
            // 静默失败，不影响其他功能
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 通用方法：将指定类的所有同名方法替换为返回固定值
     */
    private void hookReturnConstant(String className, String methodName, Object returnValue) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(returnValue));
                }
            }
        } catch (Throwable t) {
            // 找不到类说明不是 OPPO 设备，静默跳过
        }
    }

    /**
     * 从方法参数中查找 Intent 对象
     */
    private Intent findIntentInArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    /**
     * 从 Intent 中获取目标包名
     */
    private String getPackageFromIntent(Intent intent) {
        if (intent == null) return null;
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        return intent.getPackage();
    }
}
