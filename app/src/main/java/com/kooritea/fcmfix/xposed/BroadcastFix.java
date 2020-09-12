package com.kooritea.fcmfix.xposed;

import android.content.ContextWrapper;
import android.content.Intent;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class BroadcastFix extends XposedModule {

    public BroadcastFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
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
                    Intent intent = (Intent) methodHookParam.args[2];
                    if(intent != null && intent.getPackage() != null && intent.getFlags() != Intent.FLAG_INCLUDE_STOPPED_PACKAGES && "com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target;
                        if (intent.getComponent() != null) {
                            target = intent.getComponent().getPackageName();
                        } else {
                            target = intent.getPackage();
                        }
                        if(targetIsAllow(target)){
                            int i = ((Integer)methodHookParam.args[9]).intValue();
                            if (i == -1) {
                                methodHookParam.args[9] = Integer.valueOf(11);
                            }
                            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                            printLog("Send Forced Start Broadcast: " + target);
                        }
                    }
                }
            });
        }
    }

    private boolean targetIsAllow(String packageName){
        if (!packageName.equals("com.tencent.mm")) {
            Set<String> allowList = this.getXSharedPreferences().getStringSet("allowList", new HashSet<String>());
            for (String item : allowList) {
                if (item.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
