package sumicya.fcmself.xposed;

import android.content.Context;

import java.util.List;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedHelpers;
import sumicya.fcmself.util.XposedUtils;

public class PowerkeeperFix extends XposedModule {
    private static final String GMS_PACKAGE = "com.google.android.gms";

    public PowerkeeperFix(ClassLoader classLoader) {
        super(classLoader);
        installSafely("MilletConfig", this::configureMilletGlobalMode);
        installSafely("SimpleSettings.Misc#getBoolean", this::hookGmsControl);
        installSafely("MilletPolicy", this::hookMilletPolicy);
    }

    private void installSafely(String name, Runnable installer) {
        try {
            installer.run();
        } catch (Throwable e) {
            // PowerKeeper internals vary significantly between MIUI releases. One missing class or
            // field must not prevent the other independent compatibility hooks from loading.
            printLog("PowerkeeperFix " + name + " unavailable: " + e);
        }
    }

    private void configureMilletGlobalMode() {
        Class<?> milletConfig = XposedHelpers.findClass(
                "com.miui.powerkeeper.millet.MilletConfig", classLoader);
        XposedHelpers.setStaticBooleanField(milletConfig, "isGlobal", true);
        printLog("Set com.miui.powerkeeper.millet.MilletConfig.isGlobal to true");
    }

    private void hookGmsControl() {
        Class<?> misc = XposedHelpers.findClass(
                "com.miui.powerkeeper.provider.SimpleSettings.Misc", classLoader);
        XposedUtils.findAndHookMethod(misc, "getBoolean", 3, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 1 && "gms_control".equals(param.args[1])) {
                    printLog("Disabled PowerKeeper GMS limitation", true);
                    param.setResult(false);
                }
            }
        });
    }

    private void hookMilletPolicy() {
        Class<?> milletPolicy = XposedHelpers.findClass(
                "com.miui.powerkeeper.millet.MilletPolicy", classLoader);
        XposedHelpers.findAndHookConstructor(milletPolicy, Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                removeFromList(param.thisObject, "mSystemBlackList", GMS_PACKAGE);
                removeFromList(param.thisObject, "whiteApps", GMS_PACKAGE);
                removeFromList(param.thisObject, "whiteApps", "com.google.android.ext.services");
                addToList(param.thisObject, "mDataWhiteList", GMS_PACKAGE);
            }
        });
    }

    private static void removeFromList(Object owner, String fieldName, String value) {
        try {
            Object field = XposedHelpers.getObjectField(owner, fieldName);
            if (field instanceof List<?>) {
                ((List<?>) field).remove(value);
            }
        } catch (Throwable e) {
            printLog("PowerkeeperFix field unavailable: " + fieldName + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void addToList(Object owner, String fieldName, String value) {
        try {
            Object field = XposedHelpers.getObjectField(owner, fieldName);
            if (field instanceof List<?>) {
                List<Object> list = (List<Object>) field;
                if (!list.contains(value)) {
                    list.add(value);
                }
            }
        } catch (Throwable e) {
            printLog("PowerkeeperFix field unavailable: " + fieldName + ": " + e.getMessage());
        }
    }
}
