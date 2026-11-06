package com.kooritea.fcmfix.xposed;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.XposedUtils;

public class BroadcastFix extends XposedModule {

    private static final ArrayList<String> hookedMethodNames = new ArrayList<>();

    public static String getHookStatus() {
        synchronized (hookedMethodNames) {
            return "hooks=" + hookedMethodNames.size() + " [" + String.join(",", hookedMethodNames) + "]";
        }
    }

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        try{
            this.startHookBroadcastIntentLocked();
        }catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
//        try{
//            this.startHookScheduleResultTo();
//        }catch (Throwable e) {
//            printLog("hook error com.android.server.am.BroadcastQueueModernImpl.scheduleResultTo:" + e.getMessage());
//        }
    }

    protected void startHookBroadcastIntentLocked(){
        Class<?> broadcastController = XposedHelpers.findClassIfExists("com.android.server.am.BroadcastController", classLoader);
        Class<?> ams = XposedHelpers.findClassIfExists("com.android.server.am.ActivityManagerService", classLoader);
        if (broadcastController != null) {
            // ColorOS 16 / AOSP 16：實際邏輯在 broadcastIntentLockedTraced（葉子方法，不會被內聯）
            Method traced = XposedUtils.tryFindMethodMostParam(broadcastController, "broadcastIntentLockedTraced");
            if (traced != null) {
                tryInstallHook(traced);
            }
            Method locked = XposedUtils.tryFindMethodMostParam(broadcastController, "broadcastIntentLocked");
            if (locked != null) {
                tryInstallHook(locked);
            }
        }
        if (ams != null) {
            // 兼容舊版 Android / 兜底：ActivityManagerService.broadcastIntentLocked
            Method locked = XposedUtils.tryFindMethodMostParam(ams, "broadcastIntentLocked");
            if (locked != null) {
                tryInstallHook(locked);
            }
            // AOSP 15 上 BroadcastController 不存在，AMS 的實際邏輯在 broadcastIntentLockedTraced（葉子方法，不會被內聯）
            Method traced = XposedUtils.tryFindMethodMostParam(ams, "broadcastIntentLockedTraced");
            if (traced != null) {
                tryInstallHook(traced);
            }
            // 反內聯：這些入口方法可能把 broadcastIntentLocked 內聯進自己的方法體
            deoptMethodsByName(ams, "broadcastIntentWithFeature");
            deoptMethodsByName(ams, "broadcastIntentWithFeatureWithCallback");
            deoptMethodsByName(ams, "broadcastIntent");
            deoptMethodsByName(ams, "broadcastIntentInPackage");
            deoptMethodsByName(ams, "broadcastIntentLocked");
        }
        if (hookedMethodNames.isEmpty()) {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmfix将不会工作。");
        } else {
            printLog("broadcastIntentLocked 挂接完成: " + getHookStatus());
        }
    }

    private void tryInstallHook(Method method) {
        int intent_args_index = findIntentParameterIndex(method);
        int appOp_args_index = findAppOpParameterIndex(method);
        if(intent_args_index < 0 || appOp_args_index < 0
                || method.getParameterTypes()[intent_args_index] != Intent.class
                || method.getParameterTypes()[appOp_args_index] != int.class){
            printLog("无法挂接 " + method.getDeclaringClass().getName() + "#" + method.getName() + " (参数定位失败 intentIdx=" + intent_args_index + " appOpIdx=" + appOp_args_index + ")");
            return;
        }
        try {
            // 嘗試反內聯：即使本方法被內聯，也確保其調用方回退為虛擬調用
            XposedBridge.deoptimize(method);
        } catch (Throwable ignored) {
        }
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target: " + method.getDeclaringClass().getName() + "#" + method.getName());
        createBroadcastIntentLockedHooker(intent_args_index, appOp_args_index, method);
        synchronized (hookedMethodNames) {
            hookedMethodNames.add(method.getDeclaringClass().getSimpleName() + "#" + method.getName());
        }
    }

    private static void deoptMethodsByName(Class<?> clazz, String name) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (name.equals(m.getName())) {
                    try {
                        XposedBridge.deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 定位 broadcastIntentLocked 中的 intent 参数：签名中唯一的 Intent 参数。
     */
    private static int findIntentParameterIndex(Method method){
        Class<?>[] types = method.getParameterTypes();
        int index = -1;
        for(int i = 0; i < types.length; i++){
            if(types[i] == Intent.class){
                if(index >= 0){
                    return -1; // 存在多个 Intent 参数，无法确定
                }
                index = i;
            }
        }
        return index;
    }

    /**
     * 定位 broadcastIntentLocked 中的 appOp 参数：
     * 1) 按参数名查找（框架类保留了参数名）；
     * 2) 查找 bOptions(Bundle) 前一个 int 参数（AOSP 各版本签名均为 ... int appOp, Bundle bOptions, ...）；
     * 3) 按各 Android 版本的历史位置兜底。
     */
    private static int findAppOpParameterIndex(Method method){
        Parameter[] parameters = method.getParameters();
        Class<?>[] types = method.getParameterTypes();
        for(int i = 0; i < parameters.length; i++){
            if("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class){
                return i;
            }
        }
        for(int i = 1; i < types.length; i++){
            if(types[i] == Bundle.class && types[i - 1] == int.class){
                return i - 1;
            }
        }
        int candidate;
        if(Build.VERSION.SDK_INT >= 34){
            candidate = 13;
        }else if(Build.VERSION.SDK_INT >= 31){
            candidate = 12;
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            candidate = 10;
        }else{
            candidate = 9;
        }
        if(candidate < types.length && types[candidate] == int.class){
            return candidate;
        }
        return -1;
    }

    protected void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method){
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target: " + method.getDeclaringClass().getName());
        final int finalIntent_args_index = intent_args_index;
        final int finalAppOp_args_index = appOp_args_index;

        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(!isBootComplete){
                    return;
                }
                if(methodHookParam.args[finalIntent_args_index] == null){
                    return;
                }
                Intent intent = (Intent) methodHookParam.args[finalIntent_args_index];
                // 介入条件：Intent未包含唤醒停止的pkg 且 Intent是FCM
                if((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 && isFCMIntent(intent)){
                    String target;
                    if (intent.getComponent() != null) {
                        target = intent.getComponent().getPackageName();
                    } else {
                        target = intent.getPackage();
                    }
                    if(targetIsAllow(target)){
                        int i = (Integer) methodHookParam.args[finalAppOp_args_index];
                        if (i == -1) {
                            methodHookParam.args[finalAppOp_args_index] = 11;
                        }
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        if (getBooleanConfig("includeIceBoxDisableApp",false) && !IceboxUtils.isAppEnabled(context, target)) {
                            printLog("Waiting for IceBox to activate the app: " + target, true);
                            methodHookParam.setResult(false);
                            new Thread(() -> {
                                IceboxUtils.activeApp(context, target);
                                for (int i1 = 0; i1 < 300; i1++) {
                                    if (!IceboxUtils.isAppEnabled(context, target)) {
                                        try {
                                            Thread.sleep(100);
                                        } catch (Throwable e) {
                                            printLog("Send Forced Start Broadcast Error: " + target + " " + e.getMessage(), true);
                                        }
                                    } else {
                                        break;
                                    }
                                }
                                try {
                                    if(IceboxUtils.isAppEnabled(context, target)){
                                        printLog("Send Forced Start Broadcast: " + target, true);
                                    }else{
                                        printLog("Waiting for IceBox to activate the app timed out: " + target, true);
                                    }
                                    XposedBridge.invokeOriginalMethod(methodHookParam.method, methodHookParam.thisObject, methodHookParam.args);
                                } catch (Throwable e) {
                                    printLog("Send Forced Start Broadcast Error: " + target + " " + e.getMessage(), true);
                                }
                            }).start();
                        }else{
                            printLog("Send Forced Start Broadcast: " + target, true);
                        }
                        // cos15 unfreeze
                        OplusProxyFix.unfreeze(target);
                    }
                }
            }
        });
    }

    protected void startHookScheduleResultTo(){
        Method method = XposedUtils.findMethod(XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernImpl",classLoader),"scheduleResultTo",1);
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(!isBootComplete){
                    return;
                }
                if(methodHookParam.args[0] == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultTo") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"intent") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode") == null){
                    return;
                }
                Intent intent = (Intent)XposedHelpers.getObjectField(methodHookParam.args[0],"intent");
                int resultCode = (int) XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode");
                String packageName = intent.getPackage();
                if(resultCode != -1 && getBooleanConfig("noResponseNotification",false) && targetIsAllow(packageName)){
                    try{
                        Intent notifyIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                        if(notifyIntent!=null){
                            notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            PendingIntent pendingIntent = PendingIntent.getActivity(
                                    context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                            createFcmfixChannel(notificationManager);
                            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle("FCM Message")
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                            Bitmap icon = getAppIcon(packageName);
                            if(icon != null){
                                notification.setLargeIcon(icon);
                            }
                            notification.setContentIntent(pendingIntent).setAutoCancel(true);
                            notificationManager.notify((int) System.currentTimeMillis(), notification.build());
                        }else{
                            printLog("无法获取目标应用active: " + packageName,false);
                        }
                    }catch (Throwable e){
                        printLog(e.getMessage(),false);
                    }
                }
            }
        });
    }

    private static Bitmap getAppIcon(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable drawable = pm.getApplicationIcon(appInfo);
            if (drawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) drawable).getBitmap();
            } else {
                Bitmap bitmap = Bitmap.createBitmap(
                        drawable.getIntrinsicWidth(),
                        drawable.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(new android.graphics.Canvas(bitmap));
                return bitmap;
            }
        } catch (Throwable e) {
            return null;
        }
    }
}
