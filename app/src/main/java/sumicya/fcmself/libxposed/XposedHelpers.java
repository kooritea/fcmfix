package sumicya.fcmself.libxposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public final class XposedHelpers {

    /** Class identity is part of the key; OEM/Dynamite class loaders may reuse class names. */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> FIELD_CACHE =
            new ConcurrentHashMap<>();
    /** Runtime-signature cache for callMethod/callStaticMethod hot paths. */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<MethodCacheKey, Method>>
            METHOD_CACHE = new ConcurrentHashMap<>();

    private XposedHelpers() {
    }

    public static class ClassNotFoundError extends Error {
        private static final long serialVersionUID = 1L;

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
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
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
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getLong(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
        try {
            findField(clazz, fieldName).setBoolean(null, value);
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
        ConcurrentHashMap<String, Field> classCache = FIELD_CACHE.computeIfAbsent(
                clazz, ignored -> new ConcurrentHashMap<>());
        Field cached = classCache.get(fieldName);
        if (cached != null) {
            return cached;
        }
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                Field existing = classCache.putIfAbsent(fieldName, field);
                return existing != null ? existing : field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    private static Method findBestMethod(Class<?> clazz, String methodName, Object[] args) {
        ConcurrentHashMap<MethodCacheKey, Method> classCache = METHOD_CACHE.computeIfAbsent(
                clazz, ignored -> new ConcurrentHashMap<>());
        MethodCacheKey cacheKey = new MethodCacheKey(methodName, args);
        Method cached = classCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        Class<?> current = clazz;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != args.length) {
                    continue;
                }
                int score = 0;
                boolean compatible = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    Object arg = args[i];
                    if (arg == null) {
                        // Reflection cannot pass null to a primitive. Reject it here instead of
                        // selecting an arbitrary overload that will fail during invoke().
                        if (parameterTypes[i].isPrimitive()) {
                            compatible = false;
                            break;
                        }
                        continue;
                    }
                    Class<?> boxed = boxPrimitive(parameterTypes[i]);
                    Class<?> actual = arg.getClass();
                    if (!boxed.isAssignableFrom(actual)) {
                        compatible = false;
                        break;
                    }
                    // Prefer exact matches, then the nearest assignable superclass/interface.
                    score += 1_000 - typeDistance(actual, boxed);
                }
                // Walk from the concrete class upward and preserve its method on a true tie. Within
                // one class, resolve null/equal-distance ties toward the narrower parameter types.
                if (compatible && (score > bestScore
                        || (score == bestScore && isMoreSpecific(method, best)))) {
                    best = method;
                    bestScore = score;
                }
            }
            current = current.getSuperclass();
        }
        if (best == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
        best.setAccessible(true);
        Method existing = classCache.putIfAbsent(cacheKey, best);
        return existing != null ? existing : best;
    }

    private static int typeDistance(Class<?> actual, Class<?> target) {
        if (actual == target) {
            return 0;
        }
        if (target.isInterface()) {
            // Interface graphs are generally shallow; specificity ties are resolved below.
            return 1;
        }
        int distance = 0;
        Class<?> current = actual;
        while (current != null && current != target) {
            current = current.getSuperclass();
            distance++;
        }
        return current == target ? distance : 100;
    }

    private static boolean isMoreSpecific(Method candidate, Method currentBest) {
        if (currentBest == null) {
            return false;
        }
        Class<?>[] candidateTypes = candidate.getParameterTypes();
        Class<?>[] bestTypes = currentBest.getParameterTypes();
        boolean narrower = false;
        for (int i = 0; i < candidateTypes.length; i++) {
            Class<?> candidateType = boxPrimitive(candidateTypes[i]);
            Class<?> bestType = boxPrimitive(bestTypes[i]);
            if (candidateType == bestType) {
                continue;
            }
            if (bestType.isAssignableFrom(candidateType)) {
                narrower = true;
            } else if (!candidateType.isAssignableFrom(bestType)) {
                return false;
            } else {
                return false;
            }
        }
        return narrower;
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

    private static final class MethodCacheKey {
        final String name;
        final Class<?>[] argumentTypes;
        final int hashCode;

        MethodCacheKey(String name, Object[] args) {
            this.name = name;
            this.argumentTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                argumentTypes[i] = args[i] == null ? null : args[i].getClass();
            }
            this.hashCode = 31 * name.hashCode() + Arrays.hashCode(argumentTypes);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MethodCacheKey)) return false;
            MethodCacheKey that = (MethodCacheKey) other;
            return name.equals(that.name) && Arrays.equals(argumentTypes, that.argumentTypes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
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
