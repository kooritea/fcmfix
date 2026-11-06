package com.kooritea.fcmfix.xposed;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Method;
import com.kooritea.fcmfix.util.BroadcastSignature;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.XposedUtils;

public class BroadcastFix extends XposedModule {

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
        for (String className : new String[]{
                "com.android.server.am.BroadcastController", "com.android.server.am.ActivityManagerService"}) {
            Class<?> owner = XposedHelpers.findClassIfExists(className, classLoader);
            if (owner == null) continue;
            // New frameworks use a small tracing wrapper around the implementation.
            // Hook the implementation when available so compiled wrapper callers
            // cannot bypass the stopped-package flag update.
            for (String methodName : new String[]{"broadcastIntentLockedTraced", "broadcastIntentLocked"}) {
                Method best = null;
                BroadcastSignature bestSignature = null;
                for (Method candidate : owner.getDeclaredMethods()) {
                    if (!methodName.equals(candidate.getName())) continue;
                    BroadcastSignature signature = BroadcastSignature.resolve(candidate);
                    if (signature != null && (best == null || candidate.getParameterCount() > best.getParameterCount())) {
                        best = candidate;
                        bestSignature = signature;
                    }
                }
                if (best != null) {
                    createBroadcastIntentLockedHooker(bestSignature.intentIndex, bestSignature.appOpIndex, best);
                    return;
                }
            }
        }
        printLog("Unsupported broadcastIntentLocked signature; broadcast hook skipped.");
    }

    protected void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method){
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target: " + method.toGenericString());
        final int finalIntent_args_index = intent_args_index;
        final int finalAppOp_args_index = appOp_args_index;

        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(methodHookParam.args[finalIntent_args_index] == null){
                    return;
                }
                Intent intent = (Intent) methodHookParam.args[finalIntent_args_index];
                if (!isFCMIntent(intent)) return;
                if (!isBootComplete) {
                    printLog("FCM before boot grace period finished: " + intent.getPackage());
                    return;
                }
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
                        intent.removeFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
                        if (getBooleanConfig("includeIceBoxDisableApp",false) && !IceboxUtils.isAppEnabled(context, target)) {
                            printLog("Waiting for IceBox to activate the app: " + target, true);
                            // broadcastIntentLocked returns an int (BROADCAST_SUCCESS).
                            methodHookParam.setResult(0);
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
                    } else {
                        printLog("FCM skipped: target not selected: " + target);
                    }
                }
            }
        });
        printLog("Broadcast hook installed: " + method.getName());
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
