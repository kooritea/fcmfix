package sumicya.fcmself;

import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.xposed.AutoStartFix;
import sumicya.fcmself.xposed.BroadcastFix;
import sumicya.fcmself.xposed.KeepNotification;
import sumicya.fcmself.xposed.MiuiLocalNotificationFix;
import sumicya.fcmself.xposed.OplusProxyFix;
import sumicya.fcmself.xposed.PowerkeeperFix;
import sumicya.fcmself.xposed.ReconnectManagerFix;
import sumicya.fcmself.xposed.XposedModule;

import io.github.libxposed.api.XposedModuleInterface;

public class XposedMain extends io.github.libxposed.api.XposedModule {

    private void safeHook(String name, Runnable hook) {
        try {
            hook.run();
            XposedBridge.log("[fcmself] loaded " + name);
        } catch (Throwable t) {
            XposedBridge.log("[fcmself] failed " + name + ": " + t);
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        XposedModule.setSelfPackageName("android");

        ClassLoader classLoader = param.getClassLoader();

        safeHook("BroadcastFix", () -> new BroadcastFix(classLoader));
        safeHook("MiuiLocalNotificationFix", () -> new MiuiLocalNotificationFix(classLoader));
        safeHook("AutoStartFix", () -> new AutoStartFix(classLoader));
        safeHook("KeepNotification", () -> new KeepNotification(classLoader));
        safeHook("OplusProxyFix", () -> new OplusProxyFix(classLoader));
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);

        if ("com.google.android.gms".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.google.android.gms");
            safeHook("ReconnectManagerFix", () -> new ReconnectManagerFix(param.getClassLoader()));
        }

        if ("com.miui.powerkeeper".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.miui.powerkeeper");
            safeHook("PowerkeeperFix", () -> new PowerkeeperFix(param.getClassLoader()));
        }
    }
}
