package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import com.kooritea.fcmfix.R;
import com.kooritea.fcmfix.util.ContentProviderHelper;
import java.util.ArrayList;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {

    protected XC_LoadPackage.LoadPackageParam loadPackageParam;
    public static Set<String> allowList = null;

    static final String TAG = "FcmFix";
    private static Boolean disableAutoCleanNotification = null;

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList();
    private static Boolean isInitReceiver = false;
    private static Thread loadConfigThread = null;

    protected XposedModule(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        this.loadPackageParam = loadPackageParam;
        instances.add(this);
        if(instances.size() == 1){
            initContext(loadPackageParam);
        }else{
            if(context != null && context.getSystemService(UserManager.class).isUserUnlocked()){
                try{
                    onCanReadConfig();
                }catch (Exception e){
                    printLog(e.getMessage());
                }
            }
        }

    }

    private static void initContext(final XC_LoadPackage.LoadPackageParam loadPackageParam){
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", loadPackageParam.classLoader,"attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) {
                if(context == null){
                    context = (Context)methodHookParam.thisObject;
                    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                        callAllOnCanReadConfig();
                    }else{
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                    }
                }
            }
        });
    }

    /**
     * 每个被hook的APP第一次获取到context时调用
     */
    private static void callAllOnCanReadConfig(){
        initReceiver();
        for(XposedModule instance : instances){
            try{
                instance.onCanReadConfig();
            }catch (Exception e){
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Exception{}

    protected static void printLog(String text){
        printLog(text, false);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        Log.d(TAG, text);
        if (isDiagnosticsLog) {
            Intent log = new Intent("com.kooritea.fcmfix.log");
            log.putExtra("text", text);

            try {
                context.sendBroadcast(log);
            } catch (Exception e) {
                XposedBridge.log("[fcmfix] " + text);
            }
        } else {
            XposedBridge.log("[fcmfix] " + text);
        }
    }

    /**
     * 尝试读取允许的应用列表但列表未初始化时调用
     */
    protected void checkUserDeviceUnlockAndUpdateConfig(){
        if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onUpdateConfig();
            } catch (Exception e) {
                printLog("更新配置文件失败: " + e.getMessage());
            }
        }
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Exception ignored) { }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName){
        if(disableAutoCleanNotification == null){
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if("com.kooritea.fcmfix".equals(packageName)){
            return true;
        }
        if(allowList != null){
            for (String item : allowList) {
                if (item.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected boolean isDisableAutoCleanNotification(){
        if(disableAutoCleanNotification == null){
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        return disableAutoCleanNotification != null && disableAutoCleanNotification;
    }

    private static void onUpdateConfig(){
        if(loadConfigThread == null){
            loadConfigThread = new Thread(){
                @Override
                public void run() {
                    super.run();
                    try{
                        XSharedPreferences pref = new XSharedPreferences("com.kooritea.fcmfix", "config");
                        if(pref.getFile().canRead() && pref.getBoolean("init", false)){
                            allowList = pref.getStringSet("allowList", null);
                            if(allowList != null && "android".equals(context.getPackageName())){
                                printLog( "[XSharedPreferences Mode]onUpdateConfig allowList size: " + allowList.size());
                            }
                            disableAutoCleanNotification = pref.getBoolean("disableAutoCleanNotification", false);
                            loadConfigThread = null;
                            return;
                        }
                    }catch (Exception e){
                        printLog("直接读取应用配置失败，将唤醒fcmfix本体进行读取: " + e.getMessage());
                    }
                    try{
                        ContentProviderHelper contentProviderHelper = new ContentProviderHelper(context,"content://com.kooritea.fcmfix.provider/config");
                        allowList = contentProviderHelper.getStringSet("allowList");
                        if(allowList != null && "android".equals(context.getPackageName())){
                            printLog( "[ContentProvider Mode]onUpdateConfig allowList size: " + allowList.size());
                        }
                        disableAutoCleanNotification = contentProviderHelper.getBoolean("disableAutoCleanNotification", false);
                        contentProviderHelper.close();
                    }catch (Exception e){
                        printLog("唤醒fcmfix应用读取配置失败: " + e.getMessage());
                    }
                    loadConfigThread = null;
                }
            };
            loadConfigThread.start();
        }
    }

    private static void onUninstallFcmfix(){
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmfix");
        if(channel != null){
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    private static synchronized void initReceiver(){
        if(!isInitReceiver && context != null){
            isInitReceiver = true;

            IntentFilter updateConfigIntentFilter = new IntentFilter();
            updateConfigIntentFilter.addAction("com.kooritea.fcmfix.update.config");
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                        }
                    }
                }, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
                printLog("Registered Receiver successfully in Android 14 ");
            } else {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                        }
                    }
                }, updateConfigIntentFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter();
            unInstallIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if(Intent.ACTION_PACKAGE_REMOVED.equals(action) && "com.kooritea.fcmfix".equals(intent.getData().getSchemeSpecificPart())){
                        Bundle extras = intent.getExtras();
                        if(extras.containsKey(Intent.EXTRA_REPLACING) && extras.getBoolean(Intent.EXTRA_REPLACING)){
                            return;
                        }
                        onUninstallFcmfix();
                        if("android".equals(context.getPackageName())){
                            printLog("Fcmfix已卸载，重启后停止生效。");
                        }
                    }
                }
            }, unInstallIntentFilter);
        }

    }

    protected void sendNotification(String title) {
        sendNotification(title,null);
    }

    protected void sendNotification(String title, String content) {
        printLog(title, false);
        title = "[fcmfix]" + title;
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "fcmfix");
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setAutoCancel(true);
        builder.setContentTitle(title);
        if(content != null){
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(content));
        }
        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    protected void createFcmfixChannel(NotificationManager notificationManager) {
        if(notificationManager.getNotificationChannel("fcmfix") == null){
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        if (action != null && action.endsWith(".android.c2dm.intent.RECEIVE")) {
            return true;
        } else {
            return false;
        }
    }
}
