package sumicya.fcmself;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.WorkSource;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class FcmFixModule extends XposedModule {

    private static final String TAG = "FcmFix";

    private static final int MODE_RETURN_VALUE = 0;
    private static final int MODE_BROADCAST_INTENT_LOCKED = 2;
    private static final int MODE_SHOULD_PROXY = 3;

    private static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x00000020;

    private static final String C2DM_RECEIVE_SUFFIX = ".android.c2dm.intent.RECEIVE";
    private static final String FIREBASE_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    private static final String FIREBASE_INSTANCE_ID_EVENT = "com.google.firebase.INSTANCE_ID_EVENT";

    private static final String GMS_PACKAGE = "com.google.android.gms";

    private static volatile FcmFixModule instance;

    private static final AtomicReference<Context> contextRef = new AtomicReference<>();
    private static final AtomicReference<Object> oplusWakeLockRef = new AtomicReference<>();
    private static final AtomicReference<Object> powerManagerRef = new AtomicReference<>();

    private static volatile Method powerAddMethod;
    private static volatile Method unfreezeMethod;
    private static volatile int unfreezeParamCount;

    private static final Map<Class<?>, Field> intentFieldCache = new ConcurrentHashMap<>();
    private static final Set<Class<?>> noIntentFieldCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final Map<Class<?>, Field> contextFieldCache = new ConcurrentHashMap<>();
    private static final Set<Class<?>> noContextFieldCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final Map<String, Integer> uidCache = new ConcurrentHashMap<>();

    public FcmFixModule() {
        super();
        instance = this;
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader classLoader = param.getClassLoader();

        try {
            hookActivityManagerService(classLoader);
        } catch (Throwable t) {
            logError("Failed to hook ActivityManagerService", t);
        }

        try {
            hookAppAutostart(classLoader);
        } catch (Throwable t) {
            logError("Failed to hook app autostart", t);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws Throwable {
        Class<?> amsClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        Class<?> appThreadClass = classLoader.loadClass("android.app.IApplicationThread");

        Field contextField = findFirstFieldOfType(amsClass, Context.class);
        Class<?> processRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        Field infoField = findFirstFieldOfType(processRecordClass, ApplicationInfo.class);

        Method getRecordMethod = findGetRecordMethod(amsClass, appThreadClass);
        Method broadcastMethod = findBroadcastMethod(amsClass, appThreadClass, classLoader);

        int intentIndex = findFirstParameterIndex(broadcastMethod, Intent.class);
        if (intentIndex < 0) throw new IllegalStateException("No Intent parameter found in " + broadcastMethod);

        XposedInterface.Invoker invoker = getInvoker(getRecordMethod);
        hookAndDeoptimize(broadcastMethod, new AmsBroadcastHooker(intentIndex, invoker, infoField, contextField));
    }

    private Method findGetRecordMethod(Class<?> amsClass, Class<?> appThreadClass) {
        List<String> names = Arrays.asList("getRecordForAppLOSP", "getRecordForAppLocked");
        for (Method method : amsClass.getDeclaredMethods()) {
            if (!names.contains(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(appThreadClass)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new RuntimeException("Cannot find getRecordForApp method");
    }

    private Method findBroadcastMethod(Class<?> amsClass, Class<?> appThreadClass, ClassLoader classLoader) {
        Class<?> intentReceiverClass = null;
        try { intentReceiverClass = classLoader.loadClass("android.content.IIntentReceiver"); } catch (Throwable ignored) {}

        List<String> names = Arrays.asList("broadcastIntentWithFeature", "broadcastIntent");
        Method best = null;
        int bestScore = -1;

        for (Method method : amsClass.getDeclaredMethods()) {
            if (!names.contains(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 5 || !params[0].isAssignableFrom(appThreadClass) || !hasAssignableParameterType(method, Intent.class)) continue;

            int score = 0;
            if (method.getName().equals("broadcastIntentWithFeature")) score += 200;
            if (hasAssignableParameterType(method, Intent.class)) score += 100;
            if (intentReceiverClass != null && hasAssignableParameterType(method, intentReceiverClass)) score += 50;
            if (hasAssignableParameterType(method, Bundle.class)) score += 10;
            if (hasAssignableParameterType(method, String[].class)) score += 10;
            score += params.length;

            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best != null) {
            best.setAccessible(true);
            return best;
        }
        throw new RuntimeException("Cannot find AMS broadcastIntent method");
    }

    private final class AmsBroadcastHooker implements XposedInterface.Hooker {
        private final int intentIndex;
        private final XposedInterface.Invoker getRecordInvoker;
        private final Field processRecordInfoField;
        private final Field amsContextField;

        AmsBroadcastHooker(int intentIndex, XposedInterface.Invoker getRecordInvoker, Field processRecordInfoField, Field amsContextField) {
            this.intentIndex = intentIndex;
            this.getRecordInvoker = getRecordInvoker;
            this.processRecordInfoField = processRecordInfoField;
            this.amsContextField = amsContextField;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) {
            try {
                Object arg = chain.getArg(intentIndex);
                if (!(arg instanceof Intent)) return chain.proceed();
                Intent intent = (Intent) arg;
                if (!isFcmAction(intent.getAction())) return chain.proceed();

                Object thisObject = chain.getThisObject();
                Object appThread = chain.getArg(0);
                Object processRecord = getRecordInvoker.invoke(thisObject, new Object[]{appThread});

                if (!isGmsProcess(processRecord, processRecordInfoField)) return chain.proceed();

                String packageName = getPackageName(intent);
                Context context = getContextFromField(thisObject, amsContextField);
                if (context != null) contextRef.compareAndSet(null, context);

                if (packageName != null) addTemporaryAllowList(contextRef.get(), packageName);
                addIncludeStoppedFlag(intent);
            } catch (Throwable t) {
                logError("AMS broadcast hook failed", t);
            }
            return chain.proceed();
        }
    }

    private static boolean isGmsProcess(Object processRecord, Field infoField) {
        if (processRecord == null || infoField == null) return false;
        try {
            Object appInfo = infoField.get(processRecord);
            if (appInfo instanceof ApplicationInfo) return GMS_PACKAGE.equals(((ApplicationInfo) appInfo).packageName);
        } catch (Throwable ignored) {}
        return false;
    }

    private static void addTemporaryAllowList(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return;
        try {
            Class<?> powerClass = Class.forName("android.os.PowerExemptionManager");
            Object manager = powerManagerRef.get();
            if (manager == null) {
                Constructor<?> constructor = powerClass.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                manager = constructor.newInstance(context);
                powerManagerRef.compareAndSet(null, manager);
                manager = powerManagerRef.get();
            }
            Method method = powerAddMethod;
            if (method == null) {
                method = powerClass.getMethod("addToTemporaryAllowList", String.class, int.class, String.class, long.class);
                powerAddMethod = method;
            }
            try { method.invoke(manager, packageName, 102, TAG, 10000L); } 
            catch (Throwable t) { method.invoke(manager, packageName, 0, TAG, 10000L); }
        } catch (Throwable t) {
            logError("addToTemporaryAllowList failed for " + packageName, t);
        }
    }

    private void hookAppAutostart(ClassLoader classLoader) {
        hookByName(classLoader, "com.android.server.am.BroadcastQueueInjector", "checkApplicationAutoStart", MODE_RETURN_VALUE, Boolean.TRUE);
        hookByName(classLoader, "com.android.server.am.BroadcastQueueImpl", "checkApplicationAutoStart", MODE_RETURN_VALUE, Boolean.TRUE);
        hookByName(classLoader, "com.android.server.am.BroadcastQueueModernStubImpl", "checkApplicationAutoStart", MODE_RETURN_VALUE, Boolean.TRUE);
        hookByName(classLoader, "com.android.server.am.BroadcastQueueModernStubImpl", "checkReceiverIfRestricted", MODE_RETURN_VALUE, Boolean.FALSE);
        hookByName(classLoader, "com.android.server.am.AutoStartManagerServiceStubImpl", "isAllowStartService", MODE_RETURN_VALUE, Boolean.TRUE);
        hookByName(classLoader, "com.android.server.am.SmartPowerService", "shouldInterceptBroadcast", MODE_RETURN_VALUE, Boolean.FALSE);
        hookByName(classLoader, "com.android.server.am.OplusAppStartupManager", "shouldPreventSendReceiverReal", MODE_RETURN_VALUE, Boolean.FALSE);
        hookByName(classLoader, "com.android.server.am.ActivityManagerService", "broadcastIntentLocked", MODE_BROADCAST_INTENT_LOCKED, null);
        hookByName(classLoader, "com.android.server.am.BroadcastController", "broadcastIntentLocked", MODE_BROADCAST_INTENT_LOCKED, null);
        hookOplusProxyBroadcast(classLoader);
        hookOplusProxyWakeLock(classLoader);
    }

    private void hookByName(ClassLoader classLoader, String className, String methodName, int mode, Object returnValue) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                if (mode == MODE_SHOULD_PROXY) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length >= 7 && String.class.equals(params[5]) && String.class.equals(params[6])) {
                        hookAndDeoptimize(method, new GenericHooker(mode, returnValue, null, null));
                    }
                    continue;
                }
                int[] objectIndices = findPotentialIntentArgIndices(method);
                if (objectIndices.length == 0) continue;
                int[] intIndices = (mode == MODE_BROADCAST_INTENT_LOCKED) ? findIntArgIndices(method) : null;
                hookAndDeoptimize(method, new GenericHooker(mode, returnValue, objectIndices, intIndices));
            }
        } catch (Throwable t) {
            logError("hookByName failed: " + className + "#" + methodName, t);
        }
    }

    private void hookOplusProxyBroadcast(ClassLoader classLoader) {
        try {
            Class<?> resultClass = classLoader.loadClass("com.android.server.am.OplusProxyBroadcast$RESULT");
            Field field = resultClass.getDeclaredField("NOT_INCLUDE");
            field.setAccessible(true);
            Object notInclude = field.get(null);
            hookByName(classLoader, "com.android.server.am.OplusProxyBroadcast", "shouldProxy", MODE_SHOULD_PROXY, notInclude);
        } catch (Throwable t) {
            logError("hookOplusProxyBroadcast skipped", t);
        }
    }

    private void hookOplusProxyWakeLock(ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass("com.android.server.power.OplusProxyWakeLock");
            Method method = findUnfreezeMethod(clazz);
            if (method != null) {
                unfreezeMethod = method;
                unfreezeParamCount = method.getParameterTypes().length;
            }
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                hookAndDeoptimize(constructor, new OplusWakeLockHooker());
            }
        } catch (Throwable t) {
            logError("hookOplusProxyWakeLock skipped", t);
        }
    }

    private final class OplusWakeLockHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) {
            Object result = chain.proceed();
            try {
                Object thisObject = chain.getThisObject();
                if (thisObject != null) {
                    oplusWakeLockRef.set(thisObject);
                    if (unfreezeMethod == null) {
                        Method method = findUnfreezeMethod(thisObject.getClass());
                        if (method != null) {
                            unfreezeMethod = method;
                            unfreezeParamCount = method.getParameterTypes().length;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return result;
        }
    }

    private final class GenericHooker implements XposedInterface.Hooker {
        private final int mode;
        private final Object returnValue;
        private final int[] objectIndices;
        private final int[] intIndices;

        GenericHooker(int mode, Object returnValue, int[] objectIndices, int[] intIndices) {
            this.mode = mode;
            this.returnValue = returnValue;
            this.objectIndices = objectIndices;
            this.intIndices = intIndices;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) {
            try {
                switch (mode) {
                    case MODE_SHOULD_PROXY: return handleShouldProxy(chain);
                    case MODE_BROADCAST_INTENT_LOCKED: return handleBroadcastIntentLocked(chain);
                    case MODE_RETURN_VALUE:
                    default:
                        Intent intent = findTargetIntent(chain, objectIndices);
                        if (intent != null) return returnValue;
                        return chain.proceed();
                }
            } catch (Throwable t) {
                logError("Generic hook failed", t);
                try { return chain.proceed(); } catch (Throwable ignored) { return null; }
            }
        }

        private Object handleShouldProxy(XposedInterface.Chain chain) throws Throwable {
            Object actionObject = chain.getArg(6);
            if (!(actionObject instanceof String)) return chain.proceed();
            String action = (String) actionObject;
            if (isFcmAction(action)) return returnValue;
            return chain.proceed();
        }

        private Object handleBroadcastIntentLocked(XposedInterface.Chain chain) throws Throwable {
            Intent intent = findTargetIntent(chain, objectIndices);
            if (intent == null) return chain.proceed();
            addIncludeStoppedFlag(intent);

            String packageName = getPackageName(intent);
            if (packageName != null) {
                Context context = ensureContext(chain.getThisObject());
                if (context != null) {
                    int uid = getPackageUid(context, packageName);
                    if (uid >= 0) invokeOplusUnfreeze(packageName, uid);
                }
            }

            if (intIndices == null || intIndices.length == 0) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            for (int index : intIndices) {
                if (index < 0 || index >= args.length) continue;
                Object value = args[index];
                if (value instanceof Integer && ((Integer) value) == -1) args[index] = 11;
            }
            return chain.proceed(args);
        }
    }

    private static boolean isFcmAction(String action) {
        if (action == null) return false;
        return action.endsWith(C2DM_RECEIVE_SUFFIX) || action.equals(FIREBASE_MESSAGING_EVENT) || action.equals(FIREBASE_INSTANCE_ID_EVENT);
    }

    private static boolean isTargetIntent(Intent intent) {
        return intent != null && isFcmAction(intent.getAction());
    }

    private static String getPackageName(Intent intent) {
        if (intent == null) return null;
        try {
            ComponentName component = intent.getComponent();
            if (component != null) return component.getPackageName();
        } catch (Throwable ignored) {}
        try { return intent.getPackage(); } catch (Throwable ignored) {}
        return null;
    }

    private static Intent findTargetIntent(XposedInterface.Chain chain, int[] indices) {
        if (indices == null) return null;
        for (int index : indices) {
            try {
                Object arg = chain.getArg(index);
                Intent intent = extractIntentFromArg(arg);
                if (intent != null && isTargetIntent(intent)) return intent;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Intent extractIntentFromArg(Object arg) {
        if (arg == null) return null;
        if (arg instanceof Intent) return (Intent) arg;
        Class<?> clazz = arg.getClass();
        if (noIntentFieldCache.contains(clazz)) return null;
        try {
            Field field = findIntentField(clazz);
            if (field == null) return null;
            Object value = field.get(arg);
            if (value instanceof Intent) return (Intent) value;
        } catch (Throwable t) { noIntentFieldCache.add(clazz); }
        return null;
    }

    private static Field findIntentField(Class<?> clazz) {
        if (clazz == null || noIntentFieldCache.contains(clazz)) return null;
        Field cached = intentFieldCache.get(clazz);
        if (cached != null) return cached;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (Intent.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        intentFieldCache.put(clazz, field);
                        return field;
                    }
                }
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        noIntentFieldCache.add(clazz);
        return null;
    }

    private static int[] findPotentialIntentArgIndices(Method method) {
        Class<?>[] params = method.getParameterTypes();
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < params.length; i++) if (isPotentialIntentCarrier(params[i])) list.add(i);
        int[] result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) result[i] = list.get(i);
        return result;
    }

    private static boolean isPotentialIntentCarrier(Class<?> clazz) {
        if (clazz == null) return false;
        if (Intent.class.isAssignableFrom(clazz)) return true;
        if (clazz.isPrimitive() || clazz.isArray()) return false;
        if (String.class.equals(clazz) || Integer.class.equals(clazz) || Boolean.class.equals(clazz) || Bundle.class.equals(clazz)) return false;
        return findIntentField(clazz) != null;
    }

    private static int[] findIntArgIndices(Method method) {
        Class<?>[] params = method.getParameterTypes();
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < params.length; i++) if (params[i] == int.class || params[i] == Integer.class) list.add(i);
        int[] result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) result[i] = list.get(i);
        return result;
    }

    private static void addIncludeStoppedFlag(Intent intent) {
        if (intent == null) return;
        try { if ((intent.getFlags() & FLAG_INCLUDE_STOPPED_PACKAGES) == 0) intent.addFlags(FLAG_INCLUDE_STOPPED_PACKAGES); } catch (Throwable ignored) {}
    }

    private static void invokeOplusUnfreeze(String packageName, int uid) {
        try {
            Method method = unfreezeMethod;
            if (method == null) {
                Object instance = oplusWakeLockRef.get();
                if (instance == null) return;
                method = findUnfreezeMethod(instance.getClass());
                if (method == null) return;
                unfreezeMethod = method;
                unfreezeParamCount = method.getParameterTypes().length;
            }
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            Object target = isStatic ? null : oplusWakeLockRef.get();
            if (!isStatic && target == null) return;
            WorkSource workSource = createWorkSource(uid, packageName);
            if (unfreezeParamCount == 4) method.invoke(target, packageName, workSource, "FCM", TAG);
            else if (unfreezeParamCount == 3) method.invoke(target, packageName, workSource, "FCM");
        } catch (Throwable t) {
            logError("invokeOplusUnfreeze failed for " + packageName, t);
        }
    }

    private static Method findUnfreezeMethod(Class<?> clazz) {
        Method fallback = null;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals("unfreezeIfNeed")) continue;
                    int paramCount = method.getParameterTypes().length;
                    if (paramCount != 3 && paramCount != 4) continue;
                    if (!String.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
                    method.setAccessible(true);
                    if (WorkSource.class.isAssignableFrom(method.getParameterTypes()[1])) return method;
                    if (fallback == null) fallback = method;
                }
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        return fallback;
    }

    private static WorkSource createWorkSource(int uid, String packageName) {
        WorkSource workSource = new WorkSource();
        try { WorkSource.class.getMethod("add", int.class).invoke(workSource, uid); } 
        catch (Throwable t) {
            try { WorkSource.class.getMethod("add", int.class, String.class).invoke(workSource, uid, packageName); } catch (Throwable ignored) {}
        }
        return workSource;
    }

    private static Context ensureContext(Object thisObject) {
        Context context = contextRef.get();
        if (context != null) return context;
        if (thisObject != null) {
            context = findContextInObject(thisObject);
            if (context != null) { contextRef.compareAndSet(null, context); return context; }
        }
        context = getSystemContextByReflection();
        if (context != null) contextRef.compareAndSet(null, context);
        return context;
    }

    private static Context getContextFromField(Object object, Field field) {
        if (object == null || field == null) return null;
        try { Object value = field.get(object); if (value instanceof Context) return (Context) value; } catch (Throwable ignored) {}
        return null;
    }

    private static Context findContextInObject(Object object) {
        if (object == null) return null;
        Class<?> clazz = object.getClass();
        if (noContextFieldCache.contains(clazz)) return null;
        Field cached = contextFieldCache.get(clazz);
        if (cached != null) {
            try { Object value = cached.get(object); if (value instanceof Context) return (Context) value; } catch (Throwable ignored) {}
        }
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (Context.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object value = field.get(object);
                        if (value instanceof Context) { contextFieldCache.put(clazz, field); return (Context) value; }
                    }
                }
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        noContextFieldCache.add(clazz);
        return null;
    }

    private static Context getSystemContextByReflection() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            if (activityThread != null) {
                Object context = activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
                if (context instanceof Context) return (Context) context;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> appGlobalsClass = Class.forName("android.app.AppGlobals");
            Object app = appGlobalsClass.getMethod("getInitialApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }

    private static int getPackageUid(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return -1;
        Integer cached = uidCache.get(packageName);
        if (cached != null && cached >= 0) return cached;
        try {
            PackageManager packageManager = context.getPackageManager();
            int uid = -1;
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    Class<?> flagsClass = Class.forName("android.content.pm.PackageManager$PackageInfoFlags");
                    Object flags = flagsClass.getMethod("of", long.class).invoke(null, 0L);
                    Method method = PackageManager.class.getMethod("getPackageUid", String.class, flagsClass);
                    Object result = method.invoke(packageManager, packageName, flags);
                    if (result instanceof Integer) uid = (Integer) result;
                } catch (Throwable ignored) {}
            }
            if (uid < 0) uid = packageManager.getPackageUid(packageName, 0);
            if (uid >= 0) uidCache.put(packageName, uid);
            return uid;
        } catch (Throwable t) { return -1; }
    }

    private static Field findFirstFieldOfType(Class<?> clazz, Class<?> type) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (type.isAssignableFrom(field.getType())) { field.setAccessible(true); return field; }
                }
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean hasAssignableParameterType(Method method, Class<?> type) {
        for (Class<?> param : method.getParameterTypes()) if (param.isAssignableFrom(type) || type.isAssignableFrom(param)) return true;
        return false;
    }

    private static int findFirstParameterIndex(Method method, Class<?> type) {
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) if (type.isAssignableFrom(params[i]) || params[i].isAssignableFrom(type)) return i;
        return -1;
    }

    private void hookAndDeoptimize(Executable executable, XposedInterface.Hooker hooker) {
        try { hook(executable).intercept(hooker); } catch (Throwable t) { logError("hook failed: " + executable, t); }
        try { deoptimize(executable); } catch (Throwable ignored) {}
    }

    private static void logError(String message, Throwable throwable) {
        FcmFixModule module = instance;
        if (module != null) {
            try { module.log(6, TAG, message, throwable); return; } catch (Throwable ignored) {}
        }
        try { Log.e(TAG, message, throwable); } catch (Throwable ignored) {}
    }
}