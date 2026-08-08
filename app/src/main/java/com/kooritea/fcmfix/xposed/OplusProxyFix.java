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
                for (int i = 0; i < param.args.length; i++) {
                    if (param.args[i] instanceof String) {
                        String s = (String) param.args[i];
                        if (isFCMAction(s)) {
                            action = s;
                        } else if (s.contains(".") && !s.contains(" ")) {
                            pkgName = s;
                        }
                    }
                }
                if (param.args.length >= 7) {
                    if (param.args[5] instanceof String) pkgName = (String) param.args[5];
                    if (param.args[6] instanceof String) action = (String) param.args[6];
                }
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
        
        try {
            XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", target, ws, tag, "FCMFix");
            printLog("unfreeze(pkg,4) " + target);
            return;
        } catch (Throwable ignored) {}
        
        try {
            XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", target, ws, tag);
            printLog("unfreeze(pkg,3) " + target);
        } catch (Throwable ignored) {}
    }

    private void startHookRegisterGmsRestrictObserver() {
        XposedHelpers.findAndHookMethod(
                "com.android.server.hans.scene.OplusBgSceneManager", classLoader,
                "registerGmsRestrictObserver", XC_MethodReplacement.returnConstant(null));
    }

    private void startHookUpdateGmsRestrict() {
        XposedHelpers.findAndHookMethod(
                "com.android.server.hans.scene.OplusBgSceneManager", classLoader,
                "updateGmsRestrict", XC_MethodReplacement.returnConstant(null));
    }

    private void startHookIsGoogleRestricInfoOn() {
        XposedHelpers.findAndHookMethod(
                "com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", classLoader,
                "isGoogleRestricInfoOn", int.class, XC_MethodReplacement.returnConstant(false));
    }

    // ==================== 新增：fcmfuck 的暴力绕过 ====================
    private void startHookOplusAutoStartRestrictions() {
        hookReturnConstant("com.android.server.am.BroadcastQueueInjector", "checkApplicationAutoStart", Boolean.TRUE);
        hookReturnConstant("com.android.server.am.BroadcastQueueImpl", "checkApplicationAutoStart", Boolean.TRUE);
        hookReturnConstant("com.android.server.am.BroadcastQueueModernStubImpl", "checkApplicationAutoStart", Boolean.TRUE);
        hookReturnConstant("com.android.server.am.AutoStartManagerServiceStubImpl", "isAllowStartService", Boolean.TRUE);
        
        hookReturnConstant("com.android.server.am.BroadcastQueueModernStubImpl", "checkReceiverIfRestricted", Boolean.FALSE);
        hookReturnConstant("com.android.server.am.SmartPowerService", "shouldInterceptBroadcast", Boolean.FALSE);
        hookReturnConstant("com.android.server.am.OplusAppStartupManager", "shouldPreventSendReceiverReal", Boolean.FALSE);
    }

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
                                
                                intent.addFlags(0x20);
                                unfreeze(targetPkg);
                                
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof Integer && (Integer) param.args[i] == -1) {
                                        param.args[i] = 11;
                                    }
                                }
                            }
                        });
                        printLog("hooked broadcastIntentLocked in " + className);
                    }
                }
            } catch (Throwable t) {}
        }
    }

    private void startHookBroadcastWithFeature() {
        try {
            Class<?> amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader);
            Field mContextField = amsClass.getDeclaredField("mContext");
            mContextField.setAccessible(true);
            
            String methodName = Build.VERSION.SDK_INT >= 31 ? "broadcastIntentWithFeature" : "broadcastIntent";
            
            for (Method m : amsClass.getDeclaredMethods()) {
                if (methodName.equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Intent intent = findIntentInArgs(param.args);
                            if (intent == null) return;
                            
                            String action = intent.getAction();
                            if (!"com.google.android.c2dm.intent.RECEIVE".equals(action) && !isFCMAction(action)) return;
                            
                            String targetPkg = getPackageFromIntent(intent);
                            if (targetPkg == null || !targetIsAllow(targetPkg)) return;
                            
                            if ((intent.getFlags() & 0x20) == 0) {
                                intent.addFlags(0x20);
                            }
                            
                            if (Build.VERSION.SDK_INT >= 31) {
                                try {
                                    if (s_context == null) {
                                        s_context = (Context) mContextField.get(param.thisObject);
                                    }
                                    if (s_context != null && s_powerExemptionManager == null) {
                                        // 通过反射获取 PowerExemptionManager 实例和方法
                                        Class<?> pemClass = classLoader.loadClass("android.os.PowerExemptionManager");
                                        s_powerExemptionManager = XposedHelpers.callMethod(s_context, "getSystemService", pemClass);
                                        if (s_powerExemptionManager != null) {
                                            s_addToTempAllowListMethod = pemClass.getDeclaredMethod("addToTemporaryAllowList", String.class, int.class, String.class, long.class);
                                            s_addToTempAllowListMethod.setAccessible(true);
                                        }
                                    }
                                    if (s_addToTempAllowListMethod != null && s_powerExemptionManager != null) {
                                        s_addToTempAllowListMethod.invoke(s_powerExemptionManager, targetPkg, 0x66, "GOOGLE_C2DM", 2000L);
                                        printLog("addToTemporaryAllowList: " + targetPkg);
                                    }
                                } catch (Throwable t) {
                                    printLog("PowerExemption error: " + t.getMessage());
                                }
                            }
                        }
                    });
                    printLog("hooked " + methodName + " for PowerExemption");
                    break;
                }
            }
        } catch (Throwable t) {
            printLog("hookBroadcastWithFeature error: " + t.getMessage());
        }
    }

    // ==================== 新增：辅助方法 ====================
    private void hookReturnConstant(String className, String methodName, Object returnValue) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(returnValue));
                }
            }
        } catch (Throwable t) {}
    }

    private Intent findIntentInArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Intent) return (Intent) arg;
        }
        return null;
    }

    private String getPackageFromIntent(Intent intent) {
        if (intent == null) return null;
        if (intent.getComponent() != null) return intent.getComponent().getPackageName();
        return intent.getPackage();
    }
    
    // 补充获取 UID 的方法 (通过反射 AppGlobals，system_server 专用)
    private static int getTargetUidFromPackageName(String pkgName) {
        try {
            Class<?> appGlobalsClass = XposedHelpers.findClass("com.android.server.pm.pkg.AppGlobals", null);
            Object pm = XposedHelpers.callStaticMethod(appGlobalsClass, "getPackageManager");
            if (pm != null) {
                return (int) XposedHelpers.callMethod(pm, "getPackageUid", pkgName, 0);
            }
        } catch (Throwable t) {
            // 降级尝试
            try {
                if (s_context != null) {
                    return s_context.getPackageManager().getPackageUid(pkgName, 0);
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }
}
