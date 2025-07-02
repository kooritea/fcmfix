package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kooritea.fcmfix.util.ContentProviderHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    static public XC_LoadPackage.LoadPackageParam staticLoadPackageParam = null;

    protected XC_LoadPackage.LoadPackageParam loadPackageParam;
    public static Set<String> allowList = null;
    static final String TAG = "FcmFix";
    private static final HashMap<String,Object> config = new HashMap<>();

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static Boolean isInitReceiver = false;
    public static Boolean isBootComplete = false;
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
                }catch (Throwable e){
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
        if("android".equals(getSelfPackageName())){
            new Thread(() -> {
                try {
                    Thread.sleep(60000);
                    isBootComplete = true;
                    printLog("Boot Complete");
                } catch (Throwable e) {
                    printLog(e.getMessage());
                }
            }).start();
        }else{
            isBootComplete = true;
        }
        for(XposedModule instance : instances){
            try{
                instance.onCanReadConfig();
            }catch (Throwable e){
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Throwable{}

    protected static void printLog(String text){
        printLog(text, false);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        Log.d(TAG, text);
        if (isDiagnosticsLog) {
            Intent log = new Intent("com.kooritea.fcmfix.log");
            log.putExtra("text", "["+getSelfPackageName()+"]"+text);

            try {
                context.sendBroadcast(log);
            } catch (Throwable e) {
                XposedBridge.log("[fcmfix] ["+getSelfPackageName()+"]"+text);
            }
        } else {
            XposedBridge.log("[fcmfix] ["+getSelfPackageName()+"]"+text);
        }
    }

    /**
     * 尝试读取允许的应用列表但列表未初始化时调用
     */
    protected void checkUserDeviceUnlockAndUpdateConfig(){
        if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onUpdateConfig();
            } catch (Throwable e) {
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
                } catch (Throwable ignored) { }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName){
        if(config.get("init") == null){
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if("com.kooritea.fcmfix".equals(packageName)){
            return true;
        }
        if(allowList != null){
            return allowList.contains(packageName);
        }
        return false;
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue){
        if(config.get("init") == null){
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if(config.get("init") == null){
            return defaultValue;
        }
        Object value = config.get(key);
        return value == null ? defaultValue : (Boolean) value;
    }

    protected static void onUpdateConfig(){
        if(loadConfigThread == null){
            loadConfigThread = new Thread(){
                @Override
                public void run() {
                    super.run();
                    try{
                        XSharedPreferences pref = new XSharedPreferences("com.kooritea.fcmfix", "config");
                        if(pref.getFile().canRead() && pref.getBoolean("init", false)){
                            allowList = pref.getStringSet("allowList", null);
                            if(allowList != null && "android".equals(getSelfPackageName())){
                                printLog( "[XSharedPreferences Mode]onUpdateConfig allowList size: " + allowList.size());
                            }
                            config.put("disableAutoCleanNotification", pref.getBoolean("disableAutoCleanNotification", false));
                            config.put("includeIceBoxDisableApp", pref.getBoolean("includeIceBoxDisableApp", false));
                            config.put("noResponseNotification", pref.getBoolean("noResponseNotification", false));
                            config.put("init", true);
                            loadConfigThread = null;
                            return;
                        }
                    }catch (Throwable e){
                        printLog("直接读取应用配置失败，将唤醒fcmfix本体进行读取: " + e.getMessage());
                    }
                    try{
                        ContentProviderHelper contentProviderHelper = new ContentProviderHelper(context,"content://com.kooritea.fcmfix.provider/config");
                        allowList = contentProviderHelper.getStringSet("allowList");
                        if(allowList != null && "android".equals(getSelfPackageName())){
                            printLog( "[ContentProvider Mode]onUpdateConfig allowList size: " + allowList.size());
                        }
                        config.put("disableAutoCleanNotification", contentProviderHelper.getBoolean("disableAutoCleanNotification", false));
                        config.put("includeIceBoxDisableApp", contentProviderHelper.getBoolean("includeIceBoxDisableApp", false));
                        config.put("noResponseNotification", contentProviderHelper.getBoolean("noResponseNotification", false));
                        config.put("init", true);
                        contentProviderHelper.close();
                    }catch (Throwable e){
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
                        if("android".equals(getSelfPackageName())){
                            printLog("Fcmfix已卸载，重启后停止生效。");
                        }
                    }
                }
            }, unInstallIntentFilter);
        }

    }

    protected void sendNotification(String title) {
        sendNotification(title,null,null);
    }

    protected void sendNotification(String title, String content) {
        sendNotification(title,content,null);
    }

    @SuppressLint("MissingPermission")
    protected void sendNotification(String title, String content, PendingIntent pendingIntent ) {
        printLog(title, false);
        title = "[fcmfix]" + title;
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if(pendingIntent != null){
            notification.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        notificationManager.notify((int) System.currentTimeMillis(), notification.build());
    }

    protected void createFcmfixChannel(NotificationManagerCompat notificationManager) {
        if(notificationManager.getNotificationChannel("fcmfix") == null){
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected boolean isFCMAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE") ||
                "com.google.firebase.MESSAGING_EVENT".equals(action) ||
                "com.google.firebase.INSTANCE_ID_EVENT".equals(action));
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

     protected static String getSelfPackageName() {
        return staticLoadPackageParam == null ? "UNKNOWN":staticLoadPackageParam.packageName;
    }
}
