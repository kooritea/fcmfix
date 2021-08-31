package com.kooritea.fcmfix.xposed;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import com.kooritea.fcmfix.R;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static android.content.Context.NOTIFICATION_SERVICE;

public class ReconnectManagerFix extends XposedModule {

    private Class<?> GcmChimeraService;
    private String GcmChimeraServiceLogMethodName;
    private Boolean startHookFlag = false;


    public ReconnectManagerFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHookGcmServiceStart();
    }

    @Override
    protected void onCanReadConfig() throws Exception {
        if(startHookFlag){
            this.startHook();
        }else {
            startHookFlag = true;
        }

    }

    private void startHookGcmServiceStart() {
        this.GcmChimeraService = XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService", loadPackageParam.classLoader);
        try{
            for(Method method : this.GcmChimeraService.getMethods()){
                if(method.getParameterTypes().length == 2){
                    if(method.getParameterTypes()[0] == String.class && method.getParameterTypes()[1] == Object[].class){
                        this.GcmChimeraServiceLogMethodName = method.getName();
                        break;
                    }
                }
            }
            XposedHelpers.findAndHookMethod(this.GcmChimeraService, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction("com.kooritea.fcmfix.log");
                    context.registerReceiver(logBroadcastReceive, intentFilter);
                    if(startHookFlag){
                        startHook();
                    }else {
                        startHookFlag = true;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(this.GcmChimeraService, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    context.unregisterReceiver(logBroadcastReceive);
                }
            });
        }catch (Exception e){
            XposedBridge.log("GcmChimeraService hook 失败");
        }
    }

    protected void startHook() throws Exception {
        final SharedPreferences sharedPreferences = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        if (!sharedPreferences.getBoolean("isInit", false)) {
            this.printLog("fcmfix_config init");
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("isInit", true);
            editor.putLong("heartbeatInterval", 117000L);
            editor.putString("gms_version", versionName);
            editor.commit();
            findAndUpdateHookTarget(sharedPreferences);
        }
        if (!sharedPreferences.getString("gms_version", "").equals(versionName)) {
            this.printLog("gms已更新: " + sharedPreferences.getString("gms_version", "") + "->" + versionName);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString("gms_version", versionName);;
            editor.commit();
            findAndUpdateHookTarget(sharedPreferences);
        }
        if (!sharedPreferences.getBoolean("enable", false)) {
            this.printLog("当前配置文件enable标识为false，FCMFIX退出");
            return;
        }
        this.printLog("timer_class: "+ sharedPreferences.getString("timer_class", ""));
        this.printLog("timer_intent_property: "+ sharedPreferences.getString("timer_intent_property", ""));
        this.printLog("timer_next_time_property: "+ sharedPreferences.getString("timer_next_time_property", ""));
        this.printLog("timer_settimeout_method: "+ sharedPreferences.getString("timer_settimeout_method", ""));
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(sharedPreferences.getString("timer_class", ""), loadPackageParam.classLoader), sharedPreferences.getString("timer_settimeout_method", ""), long.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                // 修改心跳间隔
                Intent intent = (Intent) XposedHelpers.getObjectField(param.thisObject, sharedPreferences.getString("timer_intent_property", ""));
                if ("com.google.android.gms.gcm.HEARTBEAT_ALARM".equals(intent.getAction())) {
                    long interval = sharedPreferences.getLong("heartbeatInterval", 0L);
                    if(interval != 0L){
                        param.args[0] = interval;
                    }
                }
            }

            @Override
            protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                // 防止计时器出现负数计时,分别是心跳计时和重连计时
                Intent intent = (Intent) XposedHelpers.getObjectField(param.thisObject, sharedPreferences.getString("timer_intent_property", ""));
                if ("com.google.android.intent.action.GCM_RECONNECT".equals(intent.getAction()) || "com.google.android.gms.gcm.HEARTBEAT_ALARM".equals(intent.getAction())) {
                    final Timer timer = new Timer("ReconnectManagerFix");
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            long nextConnectionTime = XposedHelpers.getLongField(param.thisObject, sharedPreferences.getString("timer_next_time_property", ""));
                            if (nextConnectionTime != 0 && nextConnectionTime - SystemClock.elapsedRealtime() < 0) {
                                context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                                printLog("Send broadcast GCM_RECONNECT");
                            }
                            timer.cancel();
                        }
                    }, (long) param.args[0] + 5000);
                }
            }
        });
    }

    private void sendUpdateNotification(String text) {
        printLog(text);
        text = "[fcmfix]" + text;
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "fcmfix");
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle(text);
        builder.setAutoCancel(true);
        Intent intent = new Intent(Intent.ACTION_EDIT);
        intent.addCategory("android.intent.category.DEFAULT");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.setDataAndType(Uri.fromFile(new File(context.getFilesDir().getParent() + "/shared_prefs/fcmfix_config.xml")),"text/*");
        builder.setContentIntent(PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT));
        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void createFcmfixChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            List<NotificationChannel> channelList = notificationManager.getNotificationChannels();
            for (NotificationChannel item : channelList) {
                if (item.getId() == "fcmfix") {
                    item.setName("fcmfix");
                    item.setImportance(NotificationManager.IMPORTANCE_HIGH);
                    item.setDescription("fcmfix");
                    return;
                }
            }
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix更新通知");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private BroadcastReceiver logBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.kooritea.fcmfix.log".equals(action)) {
                try{
                    XposedHelpers.callStaticMethod(GcmChimeraService,GcmChimeraServiceLogMethodName , new Class<?>[]{String.class, Object[].class}, "[fcmfix] " + intent.getStringExtra("text"), null);
                }catch (Exception e){
                    XposedBridge.log("输出日志到fcm失败： "+"[fcmfix] " + intent.getStringExtra("text"));
                }
            }
        }
    };

    private void findAndUpdateHookTarget(SharedPreferences sharedPreferences){
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("enable", false);
        printLog("开始自动寻找hook点");
        try{
            Class<?> heartbeatChimeraAlarm =  XposedHelpers.findClass("com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm",loadPackageParam.classLoader);
            for(Constructor<?> heartbeatChimeraAlarmConstructor : heartbeatChimeraAlarm.getConstructors()){
                String timerClass = "";
                String timerNextTimeProperty = "";
                String timerIntentProperty = "";
                String timerSettimeoutMethod = "";
                for(Class<?> paramClazz : heartbeatChimeraAlarmConstructor.getParameterTypes()){
                    timerClass = "";
                    timerNextTimeProperty = "";
                    timerIntentProperty = "";
                    timerSettimeoutMethod = "";
                    for(Field field : paramClazz.getDeclaredFields()){
                        if(field.getType() == Intent.class&& Modifier.isPrivate(field.getModifiers())){
                            timerIntentProperty = field.getName();
                        }
                        if(field.getType() == long.class && Modifier.isPrivate(field.getModifiers())){
                            timerNextTimeProperty = field.getName();
                        }
                        if(!"".equals(timerNextTimeProperty) && !"".equals(timerIntentProperty)){
                            break;
                        }
                    }
                    for(Method method : paramClazz.getMethods()){
                        if(method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == long.class && Modifier.isFinal(method.getModifiers()) && Modifier.isPublic(method.getModifiers())){
                            timerSettimeoutMethod = method.getName();
                            break;
                        }
                    }
                    if(!"".equals(timerNextTimeProperty) && !"".equals(timerIntentProperty) && !"".equals(timerSettimeoutMethod)){
                        timerClass = paramClazz.getName();
                        break;
                    }
                }
                if(!"".equals(timerNextTimeProperty) && !"".equals(timerIntentProperty) && !"".equals(timerSettimeoutMethod) && !"".equals(timerClass)){
                    editor.putBoolean("enable", true);
                    editor.putString("timer_class", timerClass);
                    editor.putString("timer_settimeout_method", timerSettimeoutMethod);
                    editor.putString("timer_next_time_property", timerNextTimeProperty);
                    editor.putString("timer_intent_property", timerIntentProperty);
                    this.sendUpdateNotification("自动更新配置文件成功");
                    break;
                }
            }
        }catch (Exception e){
            editor.putBoolean("enable", false);
            printLog("自动寻找hook点失败"+e.getMessage());
            this.sendUpdateNotification("自动更新配置文件失败，请手动更新。");
            e.printStackTrace();
        }
        editor.commit();
    }

}
