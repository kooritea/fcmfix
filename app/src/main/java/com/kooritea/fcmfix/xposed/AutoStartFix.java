package com.kooritea.fcmfix.xposed;

import android.content.Intent;

import com.kooritea.fcmfix.util.XposedUtils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AutoStartFix extends XposedModule {

    public AutoStartFix(XC_LoadPackage.LoadPackageParam loadPackageParam){
        super(loadPackageParam);
        this.startHook();
    }

    protected void startHook(){
        try{
            Class<?> BroadcastQueueInjector;
            try {
                BroadcastQueueInjector = XposedHelpers.findClass("com.android.server.am.BroadcastQueueInjector",loadPackageParam.classLoader);
            } catch (XposedHelpers.ClassNotFoundError e) {
                BroadcastQueueInjector = XposedHelpers.findClass("com.android.server.am.BroadcastQueueImpl",loadPackageParam.classLoader);
            }
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueInjector,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Intent intent;
                    try {
                        intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[2], "intent");
                    } catch (NoSuchFieldError e) {
                        intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    }
                    if("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target;
                        if (intent.getComponent() != null) {
                            target = intent.getComponent().getPackageName();
                        } else {
                            target = intent.getPackage();
                        }
                        if(targetIsAllow(target)){
                            printLog("Allow Auto Start: " + target);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.[BroadcastQueueInjector/BroadcastQueueImpl].checkApplicationAutoStart");
        }
    }
}
