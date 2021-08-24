package com.kooritea.fcmfix.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;

import com.kooritea.fcmfix.util.ContentProviderHelper;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public abstract class XposedModule {

    protected XC_LoadPackage.LoadPackageParam loadPackageParam;
    private Set<String> allowList = null;

    protected Context context = null;

    protected XposedModule(final XC_LoadPackage.LoadPackageParam loadPackageParam){
        this.loadPackageParam = loadPackageParam;
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", loadPackageParam.classLoader,"attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                if(context == null){
                    context = (Context)methodHookParam.thisObject;
                    initUpdateConfigReceiver();
                    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                        onCanReadConfig();
                    }else{
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                    }
                }
            }
        });
    }

    protected void onCanReadConfig() throws Exception{};

    protected void printLog(String text){
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

    private BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Exception e) {
                    printLog("注销解锁广播出错: " + e.getMessage());
                }
                try {
                    onCanReadConfig();
                } catch (Exception e) {
                    printLog("解锁广播回调出错: " + e.getMessage());
                }
            }
        }
    };

    protected boolean targetIsAllow(String packageName){
        if(this.allowList == null){
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if(this.allowList != null){
            for (String item : this.allowList) {
                if (item.equals(packageName)) {
                    return true;
                }
            }
        }else{
            printLog("Allow list is not ready");
        }
        return false;
    }

    private void onUpdateConfig(){
        ContentProviderHelper contentProviderHelper = new ContentProviderHelper(context,"content://com.kooritea.fcmfix.provider/config");
        this.allowList = contentProviderHelper.getStringSet("allowList");
        if(this.allowList != null){
            printLog("onUpdateConfig allowList size: " + this.allowList.size());
        }
    }

    private  void initUpdateConfigReceiver(){
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
