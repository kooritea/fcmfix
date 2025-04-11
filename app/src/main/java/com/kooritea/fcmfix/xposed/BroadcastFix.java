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
import java.lang.reflect.Parameter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.XposedUtils;

public class BroadcastFix extends XposedModule {

    public BroadcastFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        try{
            this.startHookBroadcastIntentLocked();
        }catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
        try{
            this.startHookScheduleResultTo();
        }catch (Throwable e) {
            printLog("hook error com.android.server.am.BroadcastQueueModernImpl.scheduleResultTo:" + e.getMessage());
        }
    }

    protected void startHookBroadcastIntentLocked(){
        Method targetMethod = null;
        int intent_args_index = 0;
        int appOp_args_index = 0;
        if(Build.VERSION.SDK_INT >= 35){
            targetMethod = XposedUtils.tryFindMethodMostParam(loadPackageParam,"com.android.server.am.BroadcastController","broadcastIntentLocked");
            if(targetMethod != null){
                if(Build.VERSION.SDK_INT >= 35){
                    intent_args_index = 3;
                    appOp_args_index = 13;
                }
            }
        }
        if(targetMethod == null){
            targetMethod = XposedUtils.tryFindMethodMostParam(loadPackageParam,"com.android.server.am.ActivityManagerService","broadcastIntentLocked");
            if(targetMethod != null){
                Parameter[] parameters = targetMethod.getParameters();
                if(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q){
                    intent_args_index = 2;
                    appOp_args_index = 9;
                }else if(Build.VERSION.SDK_INT == Build.VERSION_CODES.R){
                    intent_args_index = 3;
                    appOp_args_index = 10;
                }else if(Build.VERSION.SDK_INT == 31){
                    intent_args_index = 3;
                    if(parameters[11].getType() == int.class){
                        appOp_args_index = 11;
                    }
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                }else if(Build.VERSION.SDK_INT == 32){
                    intent_args_index = 3;
                    if(parameters[11].getType() == int.class){
                        appOp_args_index = 11;
                    }
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                }else if(Build.VERSION.SDK_INT == 33){
                    intent_args_index = 3;
                    appOp_args_index = 12;
                } else if(Build.VERSION.SDK_INT == 34){
                    intent_args_index = 3;
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                    if(parameters[13].getType() == int.class){
                        appOp_args_index = 13;
                    }
                } else if(Build.VERSION.SDK_INT >= 35){
                    intent_args_index = 3;
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                    if(parameters[13].getType() == int.class){
                        appOp_args_index = 13;
                    }
                }
                if(intent_args_index == 0 || appOp_args_index == 0){
                    intent_args_index = 0;
                    appOp_args_index = 0;
                    // 根据参数名称查找，部分经过混淆的系统无效
                    for(int i = 0; i < parameters.length; i++){
                        if("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class){
                            appOp_args_index = i;
                        }
                        if("intent".equals(parameters[i].getName()) && parameters[i].getType() == Intent.class){
                            intent_args_index = i;
                        }
                    }
                }
            }
        }
        if(targetMethod != null && intent_args_index != 0 & appOp_args_index != 0 && targetMethod.getParameters()[intent_args_index].getType() == Intent.class && targetMethod.getParameters()[appOp_args_index].getType() == int.class){
            createBroadcastIntentLockedHooker(intent_args_index,appOp_args_index,targetMethod);
        } else {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmfix将不会工作。");
        }
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
                if(intent.getFlags() != Intent.FLAG_INCLUDE_STOPPED_PACKAGES && isFCMIntent(intent)){
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
                    }
                }
            }
        });
    }

    protected void startHookScheduleResultTo(){
        Method method = XposedUtils.findMethod(XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernImpl",loadPackageParam.classLoader),"scheduleResultTo",1);
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
