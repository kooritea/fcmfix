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

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        XposedModule.setSelfPackageName("android");

        ClassLoader classLoader = param.getClassLoader();
        XposedBridge.log("[fcmfix] start hook com.android.server.am.ActivityManagerService/com.android.server.am.BroadcastController");
        new BroadcastFix(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.notification.NotificationManagerServiceInjector");
        new MiuiLocalNotificationFix(classLoader);

        XposedBridge.log("[fcmfix] com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        new AutoStartFix(classLoader);

        XposedBridge.log("[fcmfix] com.android.server.notification.NotificationManagerService");
        new KeepNotification(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.power.OplusProxyWakeLock");
        new OplusProxyFix(classLoader);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);

        if ("com.google.android.gms".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.google.android.gms");
            XposedBridge.log("[fcmfix] start hook com.google.android.gms");
            new ReconnectManagerFix(param.getClassLoader());
        }

        if ("com.miui.powerkeeper".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.miui.powerkeeper");
            XposedBridge.log("[fcmfix] start hook com.miui.powerkeeper");
            new PowerkeeperFix(param.getClassLoader());
        }
    }
}
