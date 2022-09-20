package com.kooritea.fcmfix.xposed;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;

import androidx.core.app.NotificationCompat;

import com.kooritea.fcmfix.R;
import com.kooritea.fcmfix.util.ContentProviderHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {

    protected XC_LoadPackage.LoadPackageParam loadPackageParam;
    private static Set<String> allowList = null;

    protected static Context context = null;
    private static ArrayList<XposedModule> instances = new ArrayList();
    private static Boolean isInitUpdateConfigReceiver = false;
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
                    printLog(e.getMessage(), false);
                }
            }
        }

    }

    private static void initContext(final XC_LoadPackage.LoadPackageParam loadPackageParam){
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", loadPackageParam.classLoader,"attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
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

    private static void callAllOnCanReadConfig(){
        for(XposedModule instance : instances){
            try{
                instance.onCanReadConfig();
            }catch (Exception e){
                printLog(e.getMessage(), false);
            }
        }
    }

    protected void onCanReadConfig() throws Exception{};

    protected static void printLog(String text){
        printLog(text, true);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        if (!isDiagnosticsLog) {
            XposedBridge.log("[fcmfix] " + text);
        } else {
            Intent log = new Intent("com.kooritea.fcmfix.log");
            log.putExtra("text", text);

            try {
                context.sendBroadcast(log);
            } catch (Exception e) {
                XposedBridge.log("[fcmfix] " + text);
            }
        }
    }

    /**
     * 尝试读取允许的应用列表但列表未初始化时调用
     */
    protected void checkUserDeviceUnlockAndUpdateConfig(){
        if (context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                this.onUpdateConfig();
            } catch (Exception e) {
                printLog("更新配置文件失败: " + e.getMessage(), false);
            }
        }
    }

    private static BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Exception e) { }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName){
        if(allowList == null){
            this.checkUserDeviceUnlockAndUpdateConfig();
            initUpdateConfigReceiver();
        }
        if(allowList != null){
            for (String item : allowList) {
                if (item.equals(packageName)) {
                    return true;
                }
            }
        }else{
            printLog("Allow list is not ready", false);
        }
        return false;
    }

    private static void onUpdateConfig(){
        if(loadConfigThread == null){
            loadConfigThread = new Thread(){
                @Override
                public void run() {
                    super.run();
                    ContentProviderHelper contentProviderHelper = new ContentProviderHelper(context,"content://com.kooritea.fcmfix.provider/config");
                    allowList = contentProviderHelper.getStringSet("allowList");
                    if(allowList != null){
                        printLog( "onUpdateConfig allowList size: " + allowList.size(), false);
                    }
                    loadConfigThread = null;
                }
            };
            loadConfigThread.start();
        }
    }

    private static synchronized void initUpdateConfigReceiver(){
        if(!isInitUpdateConfigReceiver && context != null){
            isInitUpdateConfigReceiver = true;
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("com.kooritea.fcmfix.update.config");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if ("com.kooritea.fcmfix.update.config".equals(action)) {
                        onUpdateConfig();
                    }
                }
            }, intentFilter);
        }

    }

    protected void sendUpdateNotification(String title) {
        sendUpdateNotification(title,null);
    }

    protected void sendUpdateNotification(String title, String content) {
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
        List<NotificationChannel> channelList = notificationManager.getNotificationChannels();
        for (NotificationChannel item : channelList) {
            if (item.getId() == "fcmfix") {
                item.setName("fcmfix");
                item.setImportance(NotificationManager.IMPORTANCE_HIGH);
                item.setDescription("fcmfix");
                return;
            }
        }
        NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("[xposed] fcmfix更新通知");
        notificationManager.createNotificationChannel(channel);
    }
}
