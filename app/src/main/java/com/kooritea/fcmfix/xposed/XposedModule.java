package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.util.Log;

import com.kooritea.fcmfix.util.ContentProviderHelper;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public abstract class XposedModule {

    protected XC_LoadPackage.LoadPackageParam loadPackageParam;

    protected XposedModule(final XC_LoadPackage.LoadPackageParam loadPackageParam){
        this.loadPackageParam = loadPackageParam;
    }

    protected abstract void onCanReadConfig() throws Exception;
    private boolean isRegisterUnlockBroadcastReceive = false;

    protected void printLog(String text){
        XposedBridge.log("[fcmfix] "+ text);
        Intent log = new Intent("com.kooritea.fcmfix.log");
        log.putExtra("text",text);
        try{
            AndroidAppHelper.currentApplication().getApplicationContext().sendBroadcast(log);
        }catch (Exception e){
            // 没有作用域
//            e.printStackTrace();;
        }
    }

    /**
     * 多次调用也仅调用一次onCanReadConfig
     * @param context
     */
    protected void checkUserDeviceUnlock(Context context){
        if(!isRegisterUnlockBroadcastReceive){
            if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                try {
                    this.onCanReadConfig();
                    printLog("startHook");
                } catch (Exception e) {
                    printLog("读取配置文件初始化失败: " + e.getMessage());
                }
            } else {
                isRegisterUnlockBroadcastReceive = true;
                IntentFilter userUnlockIntentFilter = new IntentFilter();
                userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                AndroidAppHelper.currentApplication().getApplicationContext().registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
            }
        }

    }

    private BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                printLog("User Device Unlock Broadcast");
                try {
                    onCanReadConfig();
                    AndroidAppHelper.currentApplication().getApplicationContext().unregisterReceiver(unlockBroadcastReceive);
                    isRegisterUnlockBroadcastReceive = false;
                } catch (Exception e) {
                    printLog("读取配置文件初始化失败: " + e.getMessage());
                }
            }
        }
    };
}
