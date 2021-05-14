package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.kooritea.fcmfix.util.ContentProviderHelper;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class BroadcastFix extends XposedModule {

    private Set<String> allowList = null;

    public BroadcastFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHook();
    }

    @Override
    protected void onCanReadConfig() throws Exception {
        this.onUpdateConfig();
        this.initUpdateConfigReceiver();
    }

    protected void startHook(){
        Class<?> clazz = XposedHelpers.findClass("com.android.server.am.ActivityManagerService",loadPackageParam.classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        for(Method method : declareMethods){
            if(method.getName().equals("broadcastIntentLocked")){
                if(targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length){
                    targetMethod = method;
                }
            }
        }
        if(targetMethod != null){
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    int intent_args_index = 0;
                    int appOp_args_index = 0;
                    if(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q){
                        intent_args_index = 2;
                        appOp_args_index = 9;
                    }else if(Build.VERSION.SDK_INT > Build.VERSION_CODES.Q){
                        intent_args_index = 3;
                        appOp_args_index = 10;
                    }
                    Intent intent = (Intent) methodHookParam.args[intent_args_index];
                    if(intent != null && intent.getPackage() != null && intent.getFlags() != Intent.FLAG_INCLUDE_STOPPED_PACKAGES && "com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target;
                        if (intent.getComponent() != null) {
                            target = intent.getComponent().getPackageName();
                        } else {
                            target = intent.getPackage();
                        }
                        if(targetIsAllow(target)){
                            int i = ((Integer)methodHookParam.args[appOp_args_index]).intValue();
                            if (i == -1) {
                                methodHookParam.args[appOp_args_index] = Integer.valueOf(11);
                            }
                            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                            printLog("Send Forced Start Broadcast: " + target,false);
                        }
                    }
                }
            });
        }
    }

    private boolean targetIsAllow(String packageName){
        if(this.allowList == null){
            this.checkUserDeviceUnlock(AndroidAppHelper.currentApplication().getApplicationContext());
        }
        if(this.allowList != null){
            for (String item : this.allowList) {
                if (item.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void onUpdateConfig(){
        ContentProviderHelper contentProviderHelper = new ContentProviderHelper(AndroidAppHelper.currentApplication().getApplicationContext(),"content://com.kooritea.fcmfix.provider/config");
        this.allowList = contentProviderHelper.getStringSet("allowList");
    }

    private void initUpdateConfigReceiver(){
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.kooritea.fcmfix.update.config");
        AndroidAppHelper.currentApplication().getApplicationContext().registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.kooritea.fcmfix.update.config".equals(action)) {
                    onUpdateConfig();
                }
            }
        }, intentFilter);
    }
}
