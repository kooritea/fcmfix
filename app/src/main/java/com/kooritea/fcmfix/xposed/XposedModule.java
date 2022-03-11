package com.kooritea.fcmfix.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;

import com.kooritea.fcmfix.util.ContentProviderHelper;

import java.util.ArrayList;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
                    printLog(e.getMessage());
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
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Exception{};

    protected static void printLog(String text){
        Intent log = new Intent("com.kooritea.fcmfix.log");
        log.putExtra("text",text);
        try{
            context.sendBroadcast(log);
        }catch (Exception e){
            XposedBridge.log("[fcmfix] "+ text);
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
                printLog("更新配置文件失败: " + e.getMessage());
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
            printLog("Allow list is not ready");
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
                        printLog( "onUpdateConfig allowList size: " + allowList.size());
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
}
