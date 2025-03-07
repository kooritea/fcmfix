package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import com.kooritea.fcmfix.util.XposedUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Timer;
import java.util.TimerTask;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class ReconnectManagerFix extends XposedModule {

    private Class<?> GcmChimeraService;
    private String GcmChimeraServiceLogMethodName;
    private Boolean startHookFlag = false;


    public ReconnectManagerFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.addButton();
        this.startHookGcmServiceStart();
    }

    @Override
    protected void onCanReadConfig() throws Exception {
        if(startHookFlag){
            this.checkVersion();
            onUpdateConfig();
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
                    if (Build.VERSION.SDK_INT >= 34) {
                        context.registerReceiver(logBroadcastReceive, intentFilter, Context.RECEIVER_EXPORTED);
                    } else {
                        context.registerReceiver(logBroadcastReceive, intentFilter);
                    }
                    if(startHookFlag){
                        checkVersion();
                    }else {
                        startHookFlag = true;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(this.GcmChimeraService, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    context.unregisterReceiver(logBroadcastReceive);
                }
            });
        }catch (Exception e){
            XposedBridge.log("GcmChimeraService hook 失败");
        }
        try{
            Class<?> clazz = XposedHelpers.findClass("com.google.android.gms.gcm.DataMessageManager$BroadcastDoneReceiver", loadPackageParam.classLoader);
            final Method[] declareMethods = clazz.getDeclaredMethods();
            Method targetMethod = null;
            for(Method method : declareMethods){
                Parameter[] parameters = method.getParameters();
                if(parameters.length == 2 && parameters[0].getType() == Context.class && parameters[1].getType() == Intent.class){
                    targetMethod = method;
                    break;
                }
            }
            if(targetMethod != null){
                XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                        int resultCode = (int)XposedHelpers.callMethod(methodHookParam.thisObject, "getResultCode");
                        Intent intent = (Intent)methodHookParam.args[1];
                        String packageName = intent.getPackage();
                        if(resultCode != -1 && targetIsAllow(packageName)){
                            try{
                                Intent notifyIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                                if(notifyIntent!=null){
                                    notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    PendingIntent pendingIntent = PendingIntent.getActivity(
                                            context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                                    sendNotification("FCM Message " + packageName, "",pendingIntent);
                                }else{
                                    printLog("无法获取目标应用active: " + packageName,false);
                                }
                            }catch (Exception e){
                                printLog(e.getMessage(),false);
                            }
                        }
                    }
                });
            }else{
                printLog("No Such Method com.google.android.gms.gcm.DataMessageManager$BroadcastDoneReceiver.handler");
            }
        }catch (Exception e){
            XposedBridge.log("DataMessageManager$BroadcastDoneReceiver hook 失败");
        }
    }

    public static final String configVersion = "v3";
    private void checkVersion() throws Exception {
        final SharedPreferences sharedPreferences = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        long versionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        if(versionCode < 213916046){
            printLog("当前为旧版GMS，请使用0.4.1版本FCMFIX，禁用重连修复功能");
            return;
        }
        if (!sharedPreferences.getBoolean("isInit", false) || !sharedPreferences.getString("config_version", "").equals(configVersion)) {
            printLog("fcmfix_config init", true);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("isInit", true);
            editor.putBoolean("enable", false);
            editor.putLong("heartbeatInterval", 0L);
            editor.putLong("reconnInterval", 0L);
            editor.putString("gms_version", versionName);
            editor.putLong("gms_version_code", versionCode);
            editor.putString("config_version", configVersion);
            editor.putString("timer_class", "");
            editor.putString("timer_settimeout_method", "");
            editor.putString("timer_alarm_type_property", "");
            editor.apply();
            printLog("正在更新hook位置", true);
            findAndUpdateHookTarget(sharedPreferences);
            return;
        }
        if (!sharedPreferences.getString("gms_version", "").equals(versionName) ) {
            printLog("gms已更新: " + sharedPreferences.getString("gms_version", "") + "(" + sharedPreferences.getLong("gms_version_code", 0) + ")" + "->" + versionName + "(" +versionCode + ")", true);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString("gms_version", versionName);
            editor.putLong("gms_version_code", versionCode);
            editor.putBoolean("enable", false);
            editor.apply();
            printLog("正在更新hook位置", true);
            findAndUpdateHookTarget(sharedPreferences);
            return;
        }
        if (!sharedPreferences.getBoolean("enable", false)) {
            printLog("当前配置文件enable标识为false，FCMFIX退出", true);
            return;
        }
        startHook();
    }

    protected void startHook() {
        final SharedPreferences sharedPreferences = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        printLog("timer_class: "+ sharedPreferences.getString("timer_class", ""), true);
        printLog("timer_alarm_type_property: "+ sharedPreferences.getString("timer_alarm_type_property", ""), true);
        printLog("timer_settimeout_method: "+ sharedPreferences.getString("timer_settimeout_method", ""), true);
        final Class<?> timerClazz = XposedHelpers.findClass(sharedPreferences.getString("timer_class", ""), loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(timerClazz, "toString", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                String alarmType = (String) XposedUtils.getObjectFieldByPath(param.thisObject,  sharedPreferences.getString("timer_alarm_type_property", ""));
                if("GCM_HB_ALARM".equals(alarmType) || "GCM_CONN_ALARM".equals(alarmType)){
                    long hinterval = sharedPreferences.getLong("heartbeatInterval", 0L);
                    long cinterval = sharedPreferences.getLong("reconnInterval", 0L);
                    if((hinterval > 1000) || (cinterval > 1000)){
                        param.setResult(param.getResult() + "[fcmfix locked]");
                    }
                }
            }
        });
        XposedHelpers.findAndHookMethod(timerClazz, sharedPreferences.getString("timer_settimeout_method", ""), long.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                // 修改心跳间隔
                String alarmType = (String) XposedUtils.getObjectFieldByPath(param.thisObject,  sharedPreferences.getString("timer_alarm_type_property", ""));
                if ("GCM_HB_ALARM".equals(alarmType)) {
                    long interval = sharedPreferences.getLong("heartbeatInterval", 0L);
                    if(interval > 1000){
                        param.args[0] = interval;
                    }
                }
                if ("GCM_CONN_ALARM".equals(alarmType)) {
                    long interval = sharedPreferences.getLong("reconnInterval", 0L);
                    if(interval > 1000){
                        param.args[0] = interval;
                    }
                }
            }

            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                // 防止计时器出现负数计时,分别是心跳计时和重连计时
                String alarmType = (String) XposedUtils.getObjectFieldByPath(param.thisObject,  sharedPreferences.getString("timer_alarm_type_property", ""));
                if ("GCM_HB_ALARM".equals(alarmType) || "GCM_CONN_ALARM".equals(alarmType)) {
                    Field maxField = null;
                    long maxFieldValue = 0L;
                    for(Field field : timerClazz.getDeclaredFields()){
                        if(field.getType() == long.class){
                            long fieldValue = (long)XposedHelpers.getObjectField(param.thisObject,field.getName());
                            if(maxField == null || fieldValue > maxFieldValue){
                                maxField = field;
                                maxFieldValue = fieldValue;
                            }
                        }
                    }
                    final Timer timer = new Timer("ReconnectManagerFix");
                    final Field finalMaxField = maxField;
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            long nextConnectionTime = XposedHelpers.getLongField(param.thisObject, finalMaxField.getName());
                            if (nextConnectionTime != 0 && nextConnectionTime - SystemClock.elapsedRealtime() < 0) {
                                context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                                printLog("Send broadcast GCM_RECONNECT", true);
                            }
                            timer.cancel();
                        }
                    }, (long) param.args[0] + 5000);
                }
            }
        });
    }

    private final BroadcastReceiver logBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.kooritea.fcmfix.log".equals(action)) {
                try{
                    XposedHelpers.callStaticMethod(GcmChimeraService,GcmChimeraServiceLogMethodName , new Class<?>[]{String.class, Object[].class}, "[fcmfix] " + intent.getStringExtra("text"), null);
                }catch (Throwable e){
                    XposedBridge.log("输出日志到fcm失败： "+"[fcmfix] " + intent.getStringExtra("text"));
                }
            }
        }
    };

    private void findAndUpdateHookTarget(final SharedPreferences sharedPreferences){
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        try{
            Class<?> heartbeatChimeraAlarm =  XposedHelpers.findClass("com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm",loadPackageParam.classLoader);
            Class<?> timerClass = heartbeatChimeraAlarm.getConstructors()[0].getParameterTypes()[3];
            if (timerClass.getDeclaredMethods().length == 0) {
                timerClass = timerClass.getSuperclass();
            }
            editor.putString("timer_class", timerClass.getName());
            for(Method method : timerClass.getDeclaredMethods()){
                if(method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == long.class && Modifier.isFinal(method.getModifiers()) && Modifier.isPublic(method.getModifiers())){
                    editor.putString("timer_settimeout_method", method.getName());
                    break;
                }
            }
            for(final Field timerClassField : timerClass.getDeclaredFields()){
                if(Modifier.isFinal(timerClassField.getModifiers()) && Modifier.isPublic(timerClassField.getModifiers())){
                    final Class<?> alarmClass = timerClassField.getType();
                    final Boolean[] isFinish = {false};
                    Constructor alarmClassConstructor = null;
		            for (Constructor constructor: alarmClass.getConstructors()) {
			            Class[] pts = constructor.getParameterTypes();
			            if (alarmClassConstructor == null || pts.length > alarmClassConstructor.getParameterCount()) {
                            if (pts[0] == Context.class && pts[1] == int.class && pts[2] == String.class)
				                alarmClassConstructor = constructor;
			            }
		            }
                    if(alarmClassConstructor == null) throw new Throwable("未找到构造函数");
                    XposedBridge.hookMethod(alarmClassConstructor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            if(!isFinish[0]){
                                for(Field field : alarmClass.getDeclaredFields()){
                                    if(field.getType() == String.class && Modifier.isFinal(field.getModifiers()) && Modifier.isPrivate(field.getModifiers())){
                                        if(param.args[2] != null && XposedHelpers.getObjectField(param.thisObject, field.getName()) == param.args[2]){
                                            SharedPreferences.Editor editor = sharedPreferences.edit();
                                            editor.putString("timer_alarm_type_property", timerClassField.getName() + "." + field.getName());
                                            editor.putBoolean("enable", true);
                                            editor.apply();
                                            isFinish[0] = true;
                                            printLog("更新hook位置成功", true);
                                            sendNotification("自动更新配置文件成功");
                                            startHook();
                                            return;
                                        }
                                    }
                                }
                                printLog("自动寻找hook点失败: 未找到目标方法", true);
                            }
                        }
                    });
                    break;
                }
            }
        }catch (Throwable e){
            editor.putBoolean("enable", false);
            printLog("自动寻找hook点失败"+e.getMessage(), true);
            this.sendNotification("自动更新配置文件失败", "未能找到hook点，已禁用重连修复和固定心跳功能。");
            e.printStackTrace();
        }
        editor.apply();
    }

    private void addButton(){
        XposedHelpers.findAndHookMethod("com.google.android.gms.gcm.GcmChimeraDiagnostics", loadPackageParam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
            @SuppressLint("SetTextI18n")
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                ViewGroup viewGroup = ((Window)XposedHelpers.callMethod(param.thisObject, "getWindow")).getDecorView().findViewById(android.R.id.content);
                LinearLayout linearLayout = (LinearLayout)viewGroup.getChildAt(0);
                LinearLayout linearLayout2 = (LinearLayout)linearLayout.getChildAt(0);

                Button reConnectButton = new Button((ContextWrapper)param.thisObject);
                reConnectButton.setText("RECONNECT");
                reConnectButton.setOnClickListener(view -> {
                    context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                    printLog("Send broadcast GCM_RECONNECT", true);
                });
                linearLayout2.addView(reConnectButton);

                Button openFcmFixButton = new Button((ContextWrapper)param.thisObject);
                openFcmFixButton.setText("打开FCMFIX");
                openFcmFixButton.setOnClickListener(view -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage("com.kooritea.fcmfix");
                    intent.setComponent(new ComponentName("com.kooritea.fcmfix","com.kooritea.fcmfix.MainActivity"));
                    context.startActivity(intent);
                });
                linearLayout2.addView(openFcmFixButton);
            }
        });
    }
}
