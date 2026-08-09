package sumicya.fcmself.util;

import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionUtils {
    private ReflectionUtils() {}

    private static final Map<Class<?>, Field> intentFieldCache = new ConcurrentHashMap<>();
    private static final Set<Class<?>> noIntentFieldCache = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Class<?>, Field> contextFieldCache = new ConcurrentHashMap<>();
    private static final Set<Class<?>> noContextFieldCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static Field findFirstFieldOfType(Class<?> clazz, Class<?> type) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (type.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        return field;
                    }
                }
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    public static Field findIntentField(Class<?> clazz) {
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

    public static Intent extractIntentFromArg(Object arg) {
        if (arg == null) return null;
        if (arg instanceof Intent) return (Intent) arg;
        Class<?> clazz = arg.getClass();
        if (noIntentFieldCache.contains(clazz)) return null;
        try {
            Field field = findIntentField(clazz);
            if (field == null) return null;
            Object value = field.get(arg);
            return (value instanceof Intent) ? (Intent) value : null;
        } catch (Throwable t) {
            noIntentFieldCache.add(clazz);
            return null;
        }
    }

    public static Context findContextInObject(Object object) {
        if (object == null) return null;
        Class<?> clazz = object.getClass();
        if (noContextFieldCache.contains(clazz)) return null;

        Field cached = contextFieldCache.get(clazz);
        if (cached != null) {
            try {
                Object value = cached.get(object);
                if (value instanceof Context) return (Context) value;
            } catch (Throwable ignored) {}
        }

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (Context.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object value = field.get(object);
                        if (value instanceof Context) {
                            contextFieldCache.put(clazz, field);
                            return (Context) value;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        noContextFieldCache.add(clazz);
        return null;
    }

    public static Context getSystemContextByReflection() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            if (at != null) {
                Object ctx = atClass.getMethod("getSystemContext").invoke(at);
                if (ctx instanceof Context) return (Context) ctx;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> agClass = Class.forName("android.app.AppGlobals");
            Object app = agClass.getMethod("getInitialApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean hasAssignableParameterType(Method method, Class<?> type) {
        for (Class<?> param : method.getParameterTypes()) {
            if (param.isAssignableFrom(type) || type.isAssignableFrom(param)) return true;
        }
        return false;
    }

    public static int findFirstParameterIndex(Method method, Class<?> type) {
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (type.isAssignableFrom(params[i]) || params[i].isAssignableFrom(type)) return i;
        }
        return -1;
    }
}
