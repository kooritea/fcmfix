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
        this.startHookRemovePowerPolicy();
    }

    protected void startHook(){
        try{
            // miui12
            Class<?> BroadcastQueueInjector = XposedHelpers.findClass("com.android.server.am.BroadcastQueueInjector",loadPackageParam.classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueInjector,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[2], "intent");
                    if("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            XposedHelpers.callStaticMethod(BroadcastQueueInjector,"checkAbnormalBroadcastInQueueLocked", methodHookParam.args[1], methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart", false);
        }
        try{
            // miui13
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueImpl",loadPackageParam.classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    if("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            XposedHelpers.callMethod(methodHookParam.thisObject, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart", false);
        }

        try{
            // hyperos
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernStubImpl",loadPackageParam.classLoader);
            printLog("[fcmfix] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkApplicationAutoStart");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if (targetIsAllow(target)) {
                        printLog("Allow Auto Start: " + target, false);
                        methodHookParam.setResult(true);
                    }
                }
            });

            printLog("[fcmfix] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkReceiverIfRestricted");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkReceiverIfRestricted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if(targetIsAllow(target)){
                        //XposedHelpers.callMethod(methodHookParam.thisObject, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[0]);
                        printLog("BroadcastQueueModernStubImpl.checkReceiverIfRestricted: " + target, false);
                        methodHookParam.setResult(false);
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl", false);
        }

        try {
            Class<?> ProcessCleanerBase = XposedHelpers.findClassIfExists("com.android.server.am.ProcessCleanerBase", loadPackageParam.classLoader);
            printLog("[fcmfix] start hook com.android.server.am.ProcessCleanerBase.killOnce");
            XposedUtils.findAndHookMethod(ProcessCleanerBase, "killOnce", 5, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    String target = (String) methodHookParam.args[0].getClass().getField("processName").get(methodHookParam.args[0]);
                    String reason = (String) methodHookParam.args[1];
                    int killLevel = ((Integer) methodHookParam.args[2]).intValue();

                    if (targetIsAllow(target) && (killLevel == 103 || killLevel == 104)) {
                        methodHookParam.args[2] = 102;
                        printLog("Success: Process Guard. packageName is:" + target + " reason is:" + reason + "  killLevel is:" + killLevel + " -> 102");
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.ProcessCleanerBase.killOnce", false);
        }

        try {
            Class<?> ActivityManagerService = XposedHelpers.findClassIfExists("com.android.server.am.ActivityManagerService", loadPackageParam.classLoader);
            printLog("[fcmfix] start hook com.android.server.am.ActivityManagerService.getAppStartModeLOSP");
            XposedUtils.findAndHookMethod(ActivityManagerService, "getAppStartModeLOSP", 8, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    String target = (String) methodHookParam.args[7];
                    if(targetIsAllow(target)) {
                        printLog("ActivityManagerService.getAppStartModeLOSP  package_name: " + target + " result: " + methodHookParam.getResult() + " -> 0", false);
                        methodHookParam.setResult(0);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.ActivityManagerService.getAppStartModeLOSP", false);
        }

        try {
            Class<?> ProcessMemoryCleaner = XposedHelpers.findClassIfExists("com.android.server.am.ProcessMemoryCleaner", loadPackageParam.classLoader);

            printLog("[fcmfix] start hook com.android.server.am.ProcessMemoryCleaner.checkBackgroundAppException");
            XposedUtils.findAndHookMethodAnyParam(ProcessMemoryCleaner,"checkBackgroundAppException", new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    String target = (String) methodHookParam.args[0];
                    if(targetIsAllow(target)) {
                        printLog("ProcessMemoryCleaner.checkBackgroundAppException package_name: " + target, false);
                        methodHookParam.setResult(0);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.ProcessMemoryCleaner.checkBackgroundAppException", false);
        }

        try {
            Class<?> ProcessPolicy = XposedHelpers.findClassIfExists("com.android.server.am.ProcessPolicy", loadPackageParam.classLoader);
            String[] methods = new String[] {
                    "isFastBootEnable", "isInAppProtectList", "isInFastBootList",
                    "isInProcessStaticWhiteList", "isInSecretlyProtectList",
                    "isInSystemCleanWhiteList", "isLockedApplication"
            };
            for (String method: methods) {
                printLog("[fcmfix] start hook com.android.server.am.ProcessPolicy." + method);
                XposedUtils.findAndHookMethodAnyParam(ProcessPolicy, method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                        String target = (String) methodHookParam.args[0];
                        if(targetIsAllow(target)) {
                            printLog("com.android.server.am.ProcessPolicy." + method + "  package_name:" + target, false);
                            methodHookParam.setResult(true);
                        }
                    }
                });
            }
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Class com.android.server.am.ProcessPolicy", false);
        }

        try {
            Class<?> AutoStartManagerServiceStubImpl = XposedHelpers.findClassIfExists("com.android.server.am.AutoStartManagerServiceStubImpl", loadPackageParam.classLoader);
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Intent service = (Intent) methodHookParam.args[1];
                    String target = service.getComponent().getPackageName();
                    if(targetIsAllow(target)) {
                        printLog("AutoStartManagerServiceStubImpl.isAllowStartService  package_name:" + target, false);
                        methodHookParam.setResult(true);
                    }
                }
            };

            printLog("[fcmfix] start hook com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
            XposedUtils.findAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 3, methodHook);
            XposedUtils.findAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 4, methodHook);
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService", false);
        }

        try {
            Class<?> SmartPowerService = XposedHelpers.findClassIfExists("com.android.server.am.SmartPowerService", loadPackageParam.classLoader);

            printLog("[fcmfix] start hook com.android.server.am.SmartPowerService.isProcessWhiteList");
            XposedUtils.findAndHookMethodAnyParam(SmartPowerService, "isProcessWhiteList", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    String target = (String)methodHookParam.args[1];
                    if(targetIsAllow(target)) {
                        printLog("SmartPowerService.isProcessWhiteList  package_name: " + target, false);
                        methodHookParam.setResult(true);
                    }
                }
            });

            printLog("[fcmfix] start hook com.android.server.am.SmartPowerService.shouldInterceptBroadcast");
            XposedUtils.findAndHookMethodAnyParam(SmartPowerService, "shouldInterceptBroadcast", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if(targetIsAllow(target)) {
                        printLog("SmartPowerService.shouldInterceptBroadcast  package_name: " + target, false);
                        methodHookParam.setResult(false);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Class com.android.server.am.SmartPowerService", false);
        }
    }

    protected void startHookRemovePowerPolicy(){
        try {
            // MIUI13
            Class<?> AutoStartManagerService = XposedHelpers.findClass("com.miui.server.smartpower.SmartPowerPolicyManager",loadPackageParam.classLoader);
            XposedUtils.findAndHookMethodAnyParam(AutoStartManagerService,"shouldInterceptService",new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if("com.google.firebase.MESSAGING_EVENT".equals(intent.getAction())){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            printLog("Disable MIUI Intercept: " + target);
                            param.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService", false);
        }
    }
}
