package com.kooritea.fcmfix.xposed;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public class OplusDeviceIdleFix extends XposedModule {

    private static final String OPLUS_DEVICE_IDLE_HELPER = "com.android.server.OplusDeviceIdleHelper";

    public OplusDeviceIdleFix(ClassLoader classLoader) {
        super(classLoader);
        startHook();
    }

    private void startHook() {
        Class<?> helperClass = XposedHelpers.findClassIfExists(OPLUS_DEVICE_IDLE_HELPER, classLoader);
        if (helperClass == null) {
            printLog("OplusDeviceIdleHelper not found");
            return;
        }

        for (Method method : helperClass.getDeclaredMethods()) {
            String methodName = method.getName();
            if (!"getNewWhiteList".equals(methodName) && !"getNewWhiteListLocked".equals(methodName)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new RestoreDefaultWhitelistHook());
                printLog("hook OplusDeviceIdleHelper." + methodName);
            } catch (Throwable e) {
                printLog("skip OplusDeviceIdleHelper." + methodName + ": " + e.getMessage());
            }
        }
    }

    private static final class RestoreDefaultWhitelistHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!applyWhitelistFromArgs(param)) {
                return;
            }
            param.setResult(null);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object result = param.getResult();
            if (!(result instanceof List)) {
                return;
            }
            applyWhitelist((List) result, param.thisObject);
            param.setResult(result);
        }

        private static boolean applyWhitelistFromArgs(MethodHookParam param) {
            if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof List)) {
                return false;
            }
            applyWhitelist((List) param.args[0], param.thisObject);
            return true;
        }

        private static void applyWhitelist(List whiteList, Object helper) {
            List defaultWhitelist = getDefaultWhitelist(helper);
            whiteList.clear();
            whiteList.addAll(defaultWhitelist);
            callHelperMethod(helper, "getCustomizeWhiteList", whiteList);
            callHelperMethod(helper, "addNfcJapanFelica", whiteList);
        }

        private static List getDefaultWhitelist(Object helper) {
            try {
                Object defaultWhitelist = XposedHelpers.getObjectField(helper, "mDefaultWhitelist");
                if (defaultWhitelist instanceof List) {
                    return (List) defaultWhitelist;
                }
            } catch (Throwable e) {
                XposedModule.printLog("restore default battery optimization whitelist missing mDefaultWhitelist: " + e.getMessage());
            }
            return Collections.emptyList();
        }

        private static void callHelperMethod(Object helper, String methodName, List whiteList) {
            try {
                XposedHelpers.callMethod(helper, methodName, whiteList);
            } catch (Throwable e) {
                XposedBridge.log("[fcmfix] [android] skip OplusDeviceIdleHelper." + methodName + ": " + e.getMessage());
            }
        }
    }
}
