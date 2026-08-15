package sumicya.fcmself.xposed;

import android.content.ComponentName;
import android.content.Intent;

import sumicya.fcmself.util.XposedUtils;

import java.lang.reflect.Method;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

public class AutoStartFix extends XposedModule {

    public AutoStartFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHook();
            this.startHookRemovePowerPolicy();
        } catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    private static String packageFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        ComponentName component = intent.getComponent();
        return component != null ? component.getPackageName() : intent.getPackage();
    }

    private static Intent intentArg(XC_MethodHook.MethodHookParam param, int index) {
        if (index < 0 || index >= param.args.length || !(param.args[index] instanceof Intent)) {
            return null;
        }
        return (Intent) param.args[index];
    }

    private static Intent nestedIntentArg(XC_MethodHook.MethodHookParam param, int index) {
        if (index < 0 || index >= param.args.length || param.args[index] == null) {
            return null;
        }
        try {
            Object value = XposedHelpers.getObjectField(param.args[index], "intent");
            return value instanceof Intent ? (Intent) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String allowedFcmPackage(Intent intent) {
        if (!isFCMIntent(intent)) {
            return null;
        }
        String target = packageFromIntent(intent);
        return targetIsAllow(target) ? target : null;
    }

    private String allowedFcmPackageFromIntentPackage(Intent intent) {
        if (!isFCMIntent(intent)) {
            return null;
        }
        String target = intent.getPackage();
        return targetIsAllow(target) ? target : null;
    }

    private String allowedMessagingEventPackage(Intent intent) {
        if (intent == null || !"com.google.firebase.MESSAGING_EVENT".equals(intent.getAction())) {
            return null;
        }
        String target = packageFromIntent(intent);
        return targetIsAllow(target) ? target : null;
    }

    protected void startHook() {
        try {
            Class<?> BroadcastQueueInjector = XposedHelpers.findClass("com.android.server.am.BroadcastQueueInjector", classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueInjector, "checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Intent intent = nestedIntentArg(methodHookParam, 2);
                    String target = allowedFcmPackage(intent);
                    if (target != null) {
                        XposedHelpers.callStaticMethod(BroadcastQueueInjector, "checkAbnormalBroadcastInQueueLocked",
                                methodHookParam.args[1], methodHookParam.args[0]);
                        printLog("Allow Auto Start: " + target, true);
                        methodHookParam.setResult(true);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        }
        try {
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueImpl", classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl, "checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Intent intent = nestedIntentArg(methodHookParam, 1);
                    String target = allowedFcmPackage(intent);
                    if (target != null) {
                        XposedHelpers.callMethod(methodHookParam.thisObject, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[0]);
                        printLog("Allow Auto Start: " + target, true);
                        methodHookParam.setResult(true);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart");
        }

        try {
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernStubImpl", classLoader);
            printLog("start hook com.android.server.am.BroadcastQueueModernStubImpl.checkApplicationAutoStart");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl, "checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Intent intent = nestedIntentArg(methodHookParam, 1);
                    String target = allowedFcmPackage(intent);
                    if (target != null) {
                        printLog("[" + intent.getAction() + "]checkApplicationAutoStart package_name: " + target, true);
                        methodHookParam.setResult(true);
                    }
                }
            });

            printLog("start hook com.android.server.am.BroadcastQueueModernStubImpl.checkReceiverIfRestricted");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl, "checkReceiverIfRestricted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Intent intent = nestedIntentArg(methodHookParam, 1);
                    String target = allowedFcmPackage(intent);
                    if (target != null) {
                        printLog("BroadcastQueueModernStubImpl.checkReceiverIfRestricted package_name: " + target, true);
                        methodHookParam.setResult(false);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl");
        }

        try {
            Class<?> AutoStartManagerServiceStubImpl = XposedHelpers.findClass("com.android.server.am.AutoStartManagerServiceStubImpl", classLoader);
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Intent intent = intentArg(methodHookParam, 1);
                    String target = allowedFcmPackage(intent);
                    if (target != null) {
                        printLog("[" + intent.getAction() + "]AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
                        methodHookParam.setResult(true);
                    }
                }
            };

            printLog("start hook com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
            XC_MethodHook.Unhook unhook1 = XposedUtils.tryFindAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 3, methodHook);
            XC_MethodHook.Unhook unhook2 = XposedUtils.tryFindAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 4, methodHook);
            if (unhook1 == null && unhook2 == null) {
                throw new NoSuchMethodError();
            }
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
        }

        try {
            Class<?> SmartPowerService = XposedHelpers.findClass("com.android.server.am.SmartPowerService", classLoader);

            printLog("start hook com.android.server.am.SmartPowerService.shouldInterceptBroadcast");
            XposedUtils.findAndHookMethodAnyParam(SmartPowerService, "shouldInterceptBroadcast", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Intent intent = nestedIntentArg(methodHookParam, 1);
                    String target = allowedFcmPackage(intent);
                    if (target != null) {
                        printLog("SmartPowerService.shouldInterceptBroadcast package_name: " + target, true);
                        methodHookParam.setResult(false);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Class com.android.server.am.SmartPowerService");
        }

        try {
            Method method = XposedUtils.findMethod(
                    XposedHelpers.findClass("com.android.server.am.OplusAppStartupManager", classLoader),
                    "shouldPreventSendReceiverReal", 4);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Intent intent = nestedIntentArg(methodHookParam, 0);
                    if (allowedFcmPackageFromIntentPackage(intent) != null) {
                        methodHookParam.setResult(false);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal");
        }
    }

    protected void startHookRemovePowerPolicy() {
        try {
            Class<?> AutoStartManagerService = XposedHelpers.findClass("com.miui.server.smartpower.SmartPowerPolicyManager", classLoader);
            XposedUtils.findAndHookMethodAnyParam(AutoStartManagerService, "shouldInterceptService", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isBootComplete) return;
                    Intent intent = intentArg(param, 0);
                    String target = allowedMessagingEventPackage(intent);
                    if (target != null) {
                        printLog("Disable MIUI Intercept: " + target, true);
                        param.setResult(false);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService");
        }
    }
}
