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
            // miui12
            Class<?> BroadcastQueueInjector = XposedHelpers.findClass("com.android.server.am.BroadcastQueueInjector",loadPackageParam.classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueInjector,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[2], "intent");
                    if("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target;
                        if (intent.getComponent() != null) {
                            target = intent.getComponent().getPackageName();
                        } else {
                            target = intent.getPackage();
                        }
                        if(targetIsAllow(target)){
                            XposedHelpers.callStaticMethod(BroadcastQueueInjector,"checkAbnormalBroadcastInQueueLocked", methodHookParam.args[1], methodHookParam.args[0]);
//                            XposedHelpers.callStaticMethod(
//                                    XposedHelpers.findClass("android.miui.AppOpsUtils",loadPackageParam.classLoader),
//                                    "noteApplicationAutoStart",
//                                    XposedHelpers.getObjectField(methodHookParam.args[1], "mContext"),
//                                    XposedUtils.getObjectFieldByPath(methodHookParam.args[3], "activityInfo.applicationInfo.packageName"),
//                                    XposedUtils.getObjectFieldByPath(methodHookParam.args[3], "activityInfo.applicationInfo.uid"),
//                                    "BroadcastQueueInjector#checkApplicationAutoStart"
//                            );
                            methodHookParam.setResult(true);
                            printLog("Allow Auto Start: " + target);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart", false);
        }
        try{
            // miui13
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueImpl",loadPackageParam.classLoader);;
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");;
                    if("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target;
                        if (intent.getComponent() != null) {
                            target = intent.getComponent().getPackageName();
                        } else {
                            target = intent.getPackage();
                        }
                        if(targetIsAllow(target)){
                            XposedHelpers.callMethod(methodHookParam.thisObject, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[0]);
                            methodHookParam.setResult(true);
                            printLog("Allow Auto Start: " + target);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart", false);
        }
    }
}
