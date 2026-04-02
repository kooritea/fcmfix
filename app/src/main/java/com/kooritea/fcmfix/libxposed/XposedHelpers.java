package com.kooritea.fcmfix.libxposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static class ClassNotFoundError extends Error {
        public ClassNotFoundError(String className, Throwable cause) {
            super(className, cause);
        }
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                          String methodName, Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz,
                                                          String methodName, Object... parameterTypesAndCallback) {
        ParsedHookArgs parsed = parseHookArgs(parameterTypesAndCallback);
        Method method = findMethodExact(clazz, methodName, parsed.parameterTypes);
        return XposedBridge.hookMethod(method, parsed.callback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        ParsedHookArgs parsed = parseHookArgs(parameterTypesAndCallback);
        Constructor<?> constructor = findConstructorExact(clazz, parsed.parameterTypes);
        return XposedBridge.hookMethod(constructor, parsed.callback);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            return field.getLong(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            Field field = findField(clazz, fieldName);
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
        try {
            Field field = findField(clazz, fieldName);
            field.setBoolean(null, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        Method method = findBestMethod(obj.getClass(), methodName, args);
        try {
            return method.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Class<?>[] declaredParamTypes = null;
        Object[] invokeArgs = args;
        if (args.length > 0 && args[0] instanceof Class<?>[]) {
            declaredParamTypes = (Class<?>[]) args[0];
            invokeArgs = Arrays.copyOfRange(args, 1, args.length);
        }

        Method method;
        if (declaredParamTypes != null) {
            method = findMethodExact(clazz, methodName, declaredParamTypes);
        } else {
            method = findBestMethod(clazz, methodName, invokeArgs);
        }

        try {
            return method.invoke(null, invokeArgs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ParsedHookArgs parseHookArgs(Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0 ||
                !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("Last argument must be XC_MethodHook callback");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object type = parameterTypesAndCallback[i];
            if (!(type instanceof Class<?>)) {
                throw new IllegalArgumentException("Parameter type must be Class at index " + i);
            }
            parameterTypes[i] = (Class<?>) type;
        }
        return new ParsedHookArgs(parameterTypes, callback);
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    private static Method findBestMethod(Class<?> clazz, String methodName, Object[] args) {
        Method best = null;
        int bestScore = -1;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != args.length) {
                continue;
            }
            int score = 0;
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    continue;
                }
                Class<?> boxed = boxPrimitive(pts[i]);
                if (!boxed.isAssignableFrom(arg.getClass())) {
                    ok = false;
                    break;
                }
                if (boxed == arg.getClass()) {
                    score++;
                }
            }
            if (ok && score >= bestScore) {
                best = m;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
        best.setAccessible(true);
        return best;
    }

    private static Class<?> boxPrimitive(Class<?> cls) {
        if (!cls.isPrimitive()) {
            return cls;
        }
        if (cls == int.class) return Integer.class;
        if (cls == long.class) return Long.class;
        if (cls == boolean.class) return Boolean.class;
        if (cls == float.class) return Float.class;
        if (cls == double.class) return Double.class;
        if (cls == byte.class) return Byte.class;
        if (cls == short.class) return Short.class;
        if (cls == char.class) return Character.class;
        return cls;
    }

    private static final class ParsedHookArgs {
        final Class<?>[] parameterTypes;
        final XC_MethodHook callback;

        ParsedHookArgs(Class<?>[] parameterTypes, XC_MethodHook callback) {
            this.parameterTypes = parameterTypes;
            this.callback = callback;
        }
    }
}
