package sumicya.fcmself;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.FileObserver;
import android.os.PowerExemptionManager;
import android.os.WorkSource;

import androidx.annotation.NonNull;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class FcmFixModule extends XposedModule {

    // ================= 静态字段 =================
    private static final Object LOCK = new Object();
    public static volatile Set<String> TARGET_PACKAGES = Collections.emptySet();
    
    private static ConfigFileObserver observer1;
    private static ConfigFileObserver observer2;
    
    private static final Set<Class<?>> NO_INTENT_CLASSES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final ConcurrentHashMap<Class<?>, Field> INTENT_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> UID_CACHE = new ConcurrentHashMap<>();

    public static PowerExemptionManager powerExemptionManager;
    public static Context systemContext;
    public static Object startupManager;
    public static Method unfreezeMethod;
    public static int unfreezeParamCount;

    public FcmFixModule() {
        super();
    }

    // ================= 生命周期 =================
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader classLoader = param.getClassLoader();
        loadConfig();

        synchronized (LOCK) {
            if (observer1 == null) {
                File dir1 = new File("/sdcard/Android");
                if (dir1.isDirectory()) {
                    observer1 = new ConfigFileObserver("/sdcard/Android", FileObserver.CREATE | FileObserver.DELETE | FileObserver.MODIFY | FileObserver.MOVED_FROM | FileObserver.MOVED_TO);
                    observer1.startWatching();
                }
            }
            if (observer2 == null) {
                File dir2 = new File("/storage/emulated/0/Android");
                if (dir2.isDirectory()) {
                    observer2 = new ConfigFileObserver("/storage/emulated/0/Android", FileObserver.CREATE | FileObserver.DELETE | FileObserver.MODIFY | FileObserver.MOVED_FROM | FileObserver.MOVED_TO);
                    observer2.startWatching();
                }
            }
        }

        try {
            hookAms(classLoader);
        } catch (Exception e) {
            log(ERROR, "FcmFuck", "Failed to hook ActivityManagerService", e);
        }

        try {
            hookVendorServices(classLoader);
        } catch (Exception e) {
            log(ERROR, "FcmFuck", "Failed to hook app autostart", e);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // 空实现
    }

    // ================= 核心逻辑方法 =================
    public static void loadConfig() {
        synchronized (LOCK) {
            File file = new File("/sdcard/Android/fcmfuck.txt");
            if (!file.isFile()) {
                file = new File("/storage/emulated/0/Android/fcmfuck.txt");
            }
            if (!file.isFile()) {
                TARGET_PACKAGES = Collections.emptySet();
                return;
            }

            try {
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                Set<String> set = new HashSet<>();
                String[] lines = content.split("\\R");
                for (String line : lines) {
                    int commentIdx = line.indexOf('#');
                    if (commentIdx >= 0) {
                        line = line.substring(0, commentIdx);
                    }
                    String pkg = line.trim();
                    if (!pkg.isEmpty()) {
                        set.add(pkg);
                    }
                }
                TARGET_PACKAGES = Collections.unmodifiableSet(set);
            } catch (Exception e) {
                TARGET_PACKAGES = Collections.emptySet();
            }
        }
    }

    private void hookAms(ClassLoader classLoader) throws Exception {
        Class<?> amsClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        Field mContextField = amsClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);

        Class<?> iAppThreadClass = classLoader.loadClass("android.app.IApplicationThread");
        Class<?> processRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        Field infoField = processRecordClass.getDeclaredField("info");
        infoField.setAccessible(true);

        Method getRecordMethod;
        if (Build.VERSION.SDK_INT >= 31) {
            getRecordMethod = amsClass.getDeclaredMethod("getRecordForAppLOSP", iAppThreadClass);
        } else {
            getRecordMethod = amsClass.getDeclaredMethod("getRecordForAppLocked", iAppThreadClass);
        }

        Method broadcastMethod;
        if (Build.VERSION.SDK_INT >= 33) {
            broadcastMethod = amsClass.getDeclaredMethod("broadcastIntentWithFeature",
                    iAppThreadClass, String.class, Intent.class, String.class, int.class, String.class,
                    android.os.Bundle.class, String[].class, String[].class, String[].class,
                    int.class, android.os.Bundle.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT >= 31) {
            broadcastMethod = amsClass.getDeclaredMethod("broadcastIntentWithFeature",
                    iAppThreadClass, String.class, Intent.class, String.class, int.class, String.class,
                    android.os.Bundle.class, String[].class, String[].class,
                    int.class, android.os.Bundle.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT >= 30) {
            broadcastMethod = amsClass.getDeclaredMethod("broadcastIntentWithFeature",
                    iAppThreadClass, String.class, Intent.class, String.class, int.class, String.class,
                    android.os.Bundle.class, String[].class, String[].class,
                    int.class, android.os.Bundle.class, boolean.class);
        } else {
            broadcastMethod = amsClass.getDeclaredMethod("broadcastIntent",
                    iAppThreadClass, Intent.class, String.class, classLoader.loadClass("android.content.IIntentReceiver"),
                    int.class, String.class, android.os.Bundle.class, String[].class,
                    int.class, android.os.Bundle.class, boolean.class);
        }

        XposedInterface.Invoker invoker = getInvoker(getRecordMethod);
        hook(broadcastMethod).intercept(new BroadcastHooker(0, invoker, infoField, mContextField, 0));
        deoptimize(broadcastMethod);
    }

    private void hookVendorServices(ClassLoader classLoader) {
        hookMethodBySignature(classLoader, "com.android.server.am.BroadcastQueueInjector", "checkApplicationAutoStart", 0, Boolean.TRUE);
        hookMethodBySignature(classLoader, "com.android.server.am.BroadcastQueueImpl", "checkApplicationAutoStart", 0, Boolean.TRUE);
        hookMethodBySignature(classLoader, "com.android.server.am.BroadcastQueueModernStubImpl", "checkApplicationAutoStart", 0, Boolean.TRUE);
        hookMethodBySignature(classLoader, "com.android.server.am.BroadcastQueueModernStubImpl", "checkReceiverIfRestricted", 1, Boolean.FALSE);
        hookMethodBySignature(classLoader, "com.android.server.am.AutoStartManagerServiceStubImpl", "isAllowStartService", 0, Boolean.TRUE);
        hookMethodBySignature(classLoader, "com.android.server.am.SmartPowerService", "shouldInterceptBroadcast", 1, Boolean.FALSE);
        hookMethodBySignature(classLoader, "com.android.server.am.OplusAppStartupManager", "shouldPreventSendReceiverReal", 1, Boolean.FALSE);
        hookMethodBySignature(classLoader, "com.android.server.am.ActivityManagerService", "broadcastIntentLocked", 2, null);
        hookMethodBySignature(classLoader, "com.android.server.am.BroadcastController", "broadcastIntentLocked", 2, null);

        try {
            Class<?> resultClass = classLoader.loadClass("com.android.server.am.OplusProxyBroadcast$RESULT");
            Field notIncludeField = resultClass.getDeclaredField("NOT_INCLUDE");
            notIncludeField.setAccessible(true);
            Object notIncludeVal = notIncludeField.get(null);
            hookMethodBySignature(classLoader, "com.android.server.am.OplusProxyBroadcast", "shouldProxy", 3, notIncludeVal);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> wakeLockClass = classLoader.loadClass("com.android.server.power.OplusProxyWakeLock");
            for (java.lang.reflect.Constructor<?> constructor : wakeLockClass.getDeclaredConstructors()) {
                hook(constructor).intercept(new OplusProxyWakeLockHooker());
                deoptimize(constructor);
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookMethodBySignature(ClassLoader classLoader, String className, String methodName, int type, Object returnValue) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;

                int[] intentArgIndices = null;
                int[] intArgIndices = null;

                if (type != 3) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    int complexCount = 0;
                    for (Class<?> p : paramTypes) {
                        if (isComplexType(p)) complexCount++;
                    }
                    intentArgIndices = new int[complexCount];
                    int idx = 0;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (isComplexType(paramTypes[i])) {
                            intentArgIndices[idx++] = i;
                        }
                    }
                }

                if (type == 2) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    int intCount = 0;
                    for (Class<?> p : paramTypes) {
                        if (p.equals(Integer.TYPE) || p.equals(Integer.class)) intCount++;
                    }
                    intArgIndices = new int[intCount];
                    int idx = 0;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (paramTypes[i].equals(Integer.TYPE) || paramTypes[i].equals(Integer.class)) {
                            intArgIndices[idx++] = i;
                        }
                    }
                }

                boolean shouldHook = false;
                if (type == 3) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    if (paramTypes.length >= 7 && paramTypes[5].equals(String.class) && paramTypes[6].equals(String.class)) {
                        shouldHook = true;
                    }
                } else {
                    if (intentArgIndices != null && intentArgIndices.length > 0) {
                        shouldHook = true;
                    }
                }

                if (shouldHook) {
                    hook(method).intercept(new BroadcastHooker(1, returnValue, intentArgIndices, intArgIndices, type));
                    deoptimize(method);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 静态工具方法 =================
    public static Field findIntentField(Class<?> clazz) {
        if (clazz == null || NO_INTENT_CLASSES.contains(clazz)) return null;
        Field cached = INTENT_FIELD_CACHE.get(clazz);
        if (cached != null) return cached;

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField("intent");
                if (Intent.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    INTENT_FIELD_CACHE.put(clazz, field);
                    return field;
                } else {
                    NO_INTENT_CLASSES.add(clazz);
                    return null;
                }
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
        }
        NO_INTENT_CLASSES.add(clazz);
        return null;
    }

    public static Intent findIntentInArgs(XposedInterface.Chain chain, int[] indices) {
        for (int idx : indices) {
            Object arg = chain.getArg(idx);
            if (arg instanceof Intent) return (Intent) arg;
            if (arg == null) continue;

            Class<?> argClass = arg.getClass();
            if (NO_INTENT_CLASSES.contains(argClass)) continue;

            try {
                Field field = findIntentField(argClass);
                if (field == null) continue;
                Object intentObj = field.get(arg);
                if (intentObj instanceof Intent) return (Intent) intentObj;
            } catch (Throwable e) {
                NO_INTENT_CLASSES.add(argClass);
            }
        }
        return null;
    }

    public static String getPackageName(Intent intent) {
        if (intent == null) return null;
        if (intent.getComponent() != null) return intent.getComponent().getPackageName();
        return intent.getPackage();
    }

    public static boolean isFcmAction(String action) {
        if (action == null) return false;
        return action.endsWith(".android.c2dm.intent.RECEIVE") ||
                "com.google.firebase.MESSAGING_EVENT".equals(action) ||
                "com.google.firebase.INSTANCE_ID_EVENT".equals(action);
    }

    public static boolean isGmsApp(Object processRecord, Field infoField) {
        if (processRecord == null) return false;
        try {
            Object appInfo = infoField.get(processRecord);
            if (appInfo instanceof ApplicationInfo) {
                return "com.google.android.gms".equals(((ApplicationInfo) appInfo).packageName);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isComplexType(Class<?> clazz) {
        if (Intent.class.equals(clazz)) return true;
        if (findIntentField(clazz) != null) return true;
        if (clazz.isPrimitive() || clazz.isArray()) return false;
        return !clazz.equals(String.class) && !clazz.equals(Integer.class) &&
                !clazz.equals(Boolean.class) && !clazz.equals(android.os.Bundle.class);
    }

    public static boolean isTargetFcmIntent(Intent intent) {
        if (intent == null) return false;
        if (!isFcmAction(intent.getAction())) return false;
        if (TARGET_PACKAGES.isEmpty()) return false;
        String pkg = getPackageName(intent);
        return pkg != null && TARGET_PACKAGES.contains(pkg);
    }

    public static Object hookBroadcastIntent(int type, XposedInterface.Invoker invoker, Field infoField, Field contextField, XposedInterface.Chain chain) throws Throwable {
        Object arg0 = chain.getArg(0);
        if (arg0 instanceof Intent) {
            Intent intent = (Intent) arg0;
            if ("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())) {
                Object thisObj = chain.getThisObject();
                Object callerApp = chain.getArg(1);
                Object record = invoker.invoke(thisObj, new Object[]{callerApp});

                if (isGmsApp(record, infoField)) {
                    if (Build.VERSION.SDK_INT >= 31) {
                        String pkg = intent.getPackage();
                        if (pkg != null) {
                            Context ctx = (Context) contextField.get(thisObj);
                            systemContext = ctx;
                            if (powerExemptionManager == null) {
                                powerExemptionManager = new PowerExemptionManager(ctx);
                            }
                            powerExemptionManager.addToTemporaryAllowList(pkg, 102, "GOOGLE_C2DM", 2000L);
                        }
                    }
                    if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    }
                }
            }
        }
        return chain.proceed();
    }

    public static Object hookBroadcastQueue(int type, Object returnValue, int[] intentArgs, int[] intArgs, XposedInterface.Chain chain) throws Throwable {
        if (type == 3) {
            Object arg6 = chain.getArg(6);
            if (arg6 instanceof String && isFcmAction((String) arg6)) {
                Object arg5 = chain.getArg(5);
                if (arg5 instanceof String && !TARGET_PACKAGES.isEmpty() && TARGET_PACKAGES.contains((String) arg5)) {
                    return returnValue;
                }
            }
        } else if (type == 2) {
            Intent intent = findIntentInArgs(chain, intentArgs);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                String pkg = getPackageName(intent);

                if (pkg != null && systemContext != null && startupManager != null) {
                    try {
                        Integer uid = UID_CACHE.computeIfAbsent(pkg, new UidFunction());
                        if (uid >= 0) {
                            WorkSource ws = new WorkSource();
                            if (unfreezeMethod == null) {
                                for (Method m : startupManager.getClass().getDeclaredMethods()) {
                                    if ("unfreezeIfNeed".equals(m.getName())) {
                                        int paramCount = m.getParameterTypes().length;
                                        if (paramCount == 4 || paramCount == 3) {
                                            m.setAccessible(true);
                                            unfreezeMethod = m;
                                            unfreezeParamCount = paramCount;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (unfreezeMethod != null) {
                                if (unfreezeParamCount == 4) {
                                    unfreezeMethod.invoke(startupManager, pkg, ws, "FCMXX", "FcmFuck");
                                } else if (unfreezeParamCount == 3) {
                                    unfreezeMethod.invoke(startupManager, pkg, ws, "FCMXX");
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                Object[] args = chain.getArgs().toArray();
                for (int idx : intArgs) {
                    if (args[idx] instanceof Integer && (Integer) args[idx] == -1) {
                        args[idx] = 11;
                    }
                }
                return chain.proceed(args);
            }
        } else {
            Intent intent = findIntentInArgs(chain, intentArgs);
            if (intent != null && isTargetFcmIntent(intent)) {
                return returnValue;
            }
        }
        return chain.proceed();
    }

    public static Integer getPackageUid(String pkg) {
        try {
            PackageManager pm = systemContext.getPackageManager();
            return pm.getPackageUid(pkg, 0);
        } catch (Throwable e) {
            return -1;
        }
    }

    // ================= 静态内部类 (原 2,3,4,5 号文件) =================

    private static class BroadcastHooker implements XposedInterface.Hooker {
        private final int mode;
        private final Object returnValue;
        private final Object field1;
        private final Object field2;
        private final int type;

        public BroadcastHooker(int mode, Object returnValue, Object field1, Object field2, int type) {
            this.mode = mode;
            this.returnValue = returnValue;
            this.field1 = field1;
            this.field2 = field2;
            this.type = type;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (mode == 0) {
                return hookBroadcastIntent(type, (XposedInterface.Invoker) field1, (Field) field2, (Field) returnValue, chain);
            } else {
                return hookBroadcastQueue(type, returnValue, (int[]) field1, (int[]) field2, chain);
            }
        }
    }

    private static class OplusProxyWakeLockHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            startupManager = chain.getThisObject();
            return result;
        }
    }

    private static class ConfigFileObserver extends FileObserver {
        public ConfigFileObserver(String path, int mask) {
            super(path, mask);
        }

        @Override
        public void onEvent(int event, String path) {
            if (path != null && "fcmfuck.txt".equals(path)) {
                loadConfig();
            }
        }
    }

    private static class UidFunction implements Function<String, Integer> {
        @Override
        public Integer apply(String pkg) {
            return getPackageUid(pkg);
        }
    }
}
