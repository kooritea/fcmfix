package sumicya.fcmself.xposed;

import android.content.ComponentName;
import android.content.Intent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;
import sumicya.fcmself.util.XposedUtils;

/**
 * OEM auto-start / broadcast restriction bypasses for FCM intents (MIUI, HyperOS, ColorOS, etc.).
 * Each probe is independent; missing classes on a given ROM are reported via health status.
 */
public class AutoStartFix extends XposedModule {

    private final List<String> installed = new ArrayList<>();
    private final List<String> missed = new ArrayList<>();

    public AutoStartFix(ClassLoader classLoader) {
        super(classLoader);
        probe("BroadcastQueueInjector.checkApplicationAutoStart", this::hookBroadcastQueueInjector);
        probe("BroadcastQueueImpl.checkApplicationAutoStart", this::hookBroadcastQueueImpl);
        probe("BroadcastQueueModernStubImpl", this::hookBroadcastQueueModernStub);
        probe("AutoStartManagerServiceStubImpl.isAllowStartService", this::hookAutoStartManagerService);
        probe("SmartPowerService.shouldInterceptBroadcast", this::hookSmartPowerService);
        probe("OplusAppStartupManager.shouldPreventSendReceiverReal", this::hookOplusAppStartup);
        probe("SmartPowerPolicyManager.shouldInterceptService", this::hookMiuiSmartPowerPolicy);

        String status = "命中 " + installed.size() + "/" + (installed.size() + missed.size());
        if (!installed.isEmpty()) {
            status += "：" + String.join(", ", installed);
        }
        if (!missed.isEmpty() && installed.isEmpty()) {
            status += "（均未命中，可能非目标 OEM）";
        }
        reportHookHealth("AutoStartFix", status);
    }

    private void probe(String name, Runnable installer) {
        try {
            installer.run();
            installed.add(shortName(name));
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            missed.add(shortName(name));
            printLog("AutoStartFix miss: " + name);
        } catch (Throwable e) {
            missed.add(shortName(name));
            printLog("AutoStartFix error " + name + ": " + e.getMessage());
        }
    }

    private static String shortName(String full) {
        int dot = full.lastIndexOf('.');
        return dot >= 0 ? full.substring(dot + 1) : full;
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

    private void hookBroadcastQueueInjector() {
        Class<?> clazz = XposedHelpers.findClass(
                "com.android.server.am.BroadcastQueueInjector", classLoader);
        XposedUtils.findAndHookMethodAnyParam(clazz, "checkApplicationAutoStart", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                Intent intent = nestedIntentArg(param, 2);
                String target = allowedFcmPackage(intent);
                if (target != null) {
                    XposedHelpers.callStaticMethod(clazz, "checkAbnormalBroadcastInQueueLocked",
                            param.args[1], param.args[0]);
                    printLog("Allow Auto Start: " + target, true);
                    param.setResult(true);
                }
            }
        });
    }

    private void hookBroadcastQueueImpl() {
        Class<?> clazz = XposedHelpers.findClass(
                "com.android.server.am.BroadcastQueueImpl", classLoader);
        XposedUtils.findAndHookMethodAnyParam(clazz, "checkApplicationAutoStart", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                Intent intent = nestedIntentArg(param, 1);
                String target = allowedFcmPackage(intent);
                if (target != null) {
                    XposedHelpers.callMethod(param.thisObject,
                            "checkAbnormalBroadcastInQueueLocked", param.args[0]);
                    printLog("Allow Auto Start: " + target, true);
                    param.setResult(true);
                }
            }
        });
    }

    private void hookBroadcastQueueModernStub() {
        Class<?> clazz = XposedHelpers.findClass(
                "com.android.server.am.BroadcastQueueModernStubImpl", classLoader);
        XposedUtils.findAndHookMethodAnyParam(clazz, "checkApplicationAutoStart", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                Intent intent = nestedIntentArg(param, 1);
                String target = allowedFcmPackage(intent);
                if (target != null) {
                    printLog("[" + intent.getAction() + "]checkApplicationAutoStart: " + target, true);
                    param.setResult(true);
                }
            }
        });
        XposedUtils.findAndHookMethodAnyParam(clazz, "checkReceiverIfRestricted", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                Intent intent = nestedIntentArg(param, 1);
                String target = allowedFcmPackage(intent);
                if (target != null) {
                    printLog("checkReceiverIfRestricted: " + target, true);
                    param.setResult(false);
                }
            }
        });
    }

    private void hookAutoStartManagerService() {
        Class<?> clazz = XposedHelpers.findClass(
                "com.android.server.am.AutoStartManagerServiceStubImpl", classLoader);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                Intent intent = intentArg(param, 1);
                String target = allowedFcmPackage(intent);
                if (target != null) {
                    printLog("isAllowStartService: " + target, true);
                    param.setResult(true);
                }
            }
        };
        XC_MethodHook.Unhook u1 = XposedUtils.tryFindAndHookMethod(clazz, "isAllowStartService", 3, hook);
        XC_MethodHook.Unhook u2 = XposedUtils.tryFindAndHookMethod(clazz, "isAllowStartService", 4, hook);
        if (u1 == null && u2 == null) {
            throw new NoSuchMethodError();
        }
    }

    private void hookSmartPowerService() {
        Class<?> clazz = XposedHelpers.findClass(
                "com.android.server.am.SmartPowerService", classLoader);
        XposedUtils.findAndHookMethodAnyParam(clazz, "shouldInterceptBroadcast", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                Intent intent = nestedIntentArg(param, 1);
                String target = allowedFcmPackage(intent);
                if (target != null) {
                    printLog("shouldInterceptBroadcast: " + target, true);
                    param.setResult(false);
                }
            }
        });
    }

    private void hookOplusAppStartup() {
        Method method = XposedUtils.findMethod(
                XposedHelpers.findClass("com.android.server.am.OplusAppStartupManager", classLoader),
                "shouldPreventSendReceiverReal", 4);
        if (method == null) {
            throw new NoSuchMethodError();
        }
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                Intent intent = nestedIntentArg(param, 0);
                if (allowedFcmPackageFromIntentPackage(intent) != null) {
                    param.setResult(false);
                }
            }
        });
    }

    private void hookMiuiSmartPowerPolicy() {
        Class<?> clazz = XposedHelpers.findClass(
                "com.miui.server.smartpower.SmartPowerPolicyManager", classLoader);
        XposedUtils.findAndHookMethodAnyParam(clazz, "shouldInterceptService", new XC_MethodHook() {
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
    }
}
