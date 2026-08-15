package sumicya.fcmself.libxposed;

import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

public final class XposedBridge {

    private static volatile XposedInterface xposedInterface;
    /** One native libxposed interceptor per member; logical callbacks are dispatched from it. */
    private static final Map<Member, HookRegistration> HOOKS = new ConcurrentHashMap<>();

    private XposedBridge() {
    }

    public static void init(XposedInterface xposed) {
        xposedInterface = xposed;
    }

    public static void log(String text) {
        android.util.Log.i("fcmself", text);
    }

    public static SharedPreferences getRemotePreferences(String group) {
        ensureInit();
        return xposedInterface.getRemotePreferences(group);
    }

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        ensureInit();
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only Method/Constructor can be hooked");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        synchronized (HOOKS) {
            HookRegistration registration = HOOKS.get(member);
            if (registration == null) {
                registration = new HookRegistration();
                registration.add(callback);
                final HookRegistration installedRegistration = registration;
                XposedInterface.HookHandle handle = xposedInterface
                        .hook((java.lang.reflect.Executable) member)
                        .intercept(chain -> {
                            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                            param.method = (Member) chain.getExecutable();
                            param.thisObject = chain.getThisObject();
                            List<?> argList = chain.getArgs();
                            param.args = argList.toArray(new Object[argList.size()]);

                            // Publish as a stable reference so unhook cannot invalidate the
                            // callbacks array midway through this invocation (no lock held). Note
                            // the MethodHookParam itself and the args copy below are still
                            // allocated per invocation — that is inherent to the hook abstraction
                            // (callbacks may mutate args/result in place).
                            XC_MethodHook[] callbacks = installedRegistration.callbacks;
                            if (callbacks.length == 0) {
                                return chain.proceed(param.args);
                            }

                            int beforeCount = 0;
                            for (XC_MethodHook hook : callbacks) {
                                Object previousResult = param.getResult();
                                Throwable previousThrowable = param.getThrowable();
                                boolean previousReturnEarly = param.isReturnEarly();
                                try {
                                    hook.beforeHookedMethod(param);
                                } catch (Throwable t) {
                                    restoreCallbackState(
                                            param,
                                            previousResult,
                                            previousThrowable,
                                            previousReturnEarly);
                                    android.util.Log.w("fcmself",
                                            "beforeHookedMethod error", t);
                                }
                                beforeCount++;
                                if (param.isReturnEarly()) {
                                    break;
                                }
                            }

                            if (!param.isReturnEarly()) {
                                try {
                                    Object result = chain.proceed(param.args);
                                    if (member instanceof Constructor<?> && param.thisObject == null) {
                                        Object constructed = chain.getThisObject();
                                        param.thisObject = constructed != null ? constructed : result;
                                    }
                                    param.setResult(result);
                                } catch (Throwable t) {
                                    param.setThrowable(t);
                                }
                                param.resetReturnEarly();
                            }

                            for (int i = beforeCount - 1; i >= 0; i--) {
                                Object previousResult = param.getResult();
                                Throwable previousThrowable = param.getThrowable();
                                boolean previousReturnEarly = param.isReturnEarly();
                                try {
                                    callbacks[i].afterHookedMethod(param);
                                } catch (Throwable t) {
                                    restoreCallbackState(
                                            param,
                                            previousResult,
                                            previousThrowable,
                                            previousReturnEarly);
                                    android.util.Log.w("fcmself",
                                            "afterHookedMethod error", t);
                                }
                            }

                            if (param.hasThrowable()) {
                                throw param.getThrowable();
                            }
                            return param.getResult();
                        });
                registration.handle = handle;
                HOOKS.put(member, registration);
            } else {
                registration.add(callback);
            }
            return callback.new Unhook(new HookHandleWrapper(member, callback));
        }
    }

    private static void restoreCallbackState(
            XC_MethodHook.MethodHookParam param,
            Object result,
            Throwable throwable,
            boolean returnEarly) {
        if (throwable != null) {
            param.setThrowable(throwable);
        } else {
            param.setResult(result);
        }
        if (!returnEarly) {
            param.resetReturnEarly();
        }
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        ensureInit();
        if (method instanceof Method) {
            Method m = (Method) method;
            XposedInterface.Invoker<?, Method> invoker = xposedInterface.getInvoker(m);
            invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
            try {
                return invoker.invoke(thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
        if (method instanceof Constructor<?>) {
            Constructor<?> c = (Constructor<?>) method;
            XposedInterface.CtorInvoker<?> invoker = xposedInterface.getInvoker(c);
            try {
                return invoker.newInstance(args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
        throw new IllegalArgumentException("Unsupported member type: " + method);
    }

    private static final class HookRegistration {
        volatile XC_MethodHook[] callbacks = new XC_MethodHook[0];
        XposedInterface.HookHandle handle;

        void add(XC_MethodHook callback) {
            XC_MethodHook[] current = callbacks;
            XC_MethodHook[] updated = Arrays.copyOf(current, current.length + 1);
            updated[current.length] = callback;
            callbacks = updated;
        }

        boolean remove(XC_MethodHook callback) {
            XC_MethodHook[] current = callbacks;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == callback) {
                    XC_MethodHook[] updated = new XC_MethodHook[current.length - 1];
                    System.arraycopy(current, 0, updated, 0, i);
                    System.arraycopy(current, i + 1, updated, i, current.length - i - 1);
                    callbacks = updated;
                    return true;
                }
            }
            return false;
        }
    }

    static final class HookHandleWrapper {
        private final Member member;
        private final XC_MethodHook callback;
        private boolean active = true;

        HookHandleWrapper(Member member, XC_MethodHook callback) {
            this.member = member;
            this.callback = callback;
        }

        void unhook() {
            synchronized (HOOKS) {
                if (!active) {
                    return;
                }
                active = false;
                HookRegistration registration = HOOKS.get(member);
                if (registration == null || !registration.remove(callback)) {
                    return;
                }
                if (registration.callbacks.length == 0) {
                    HOOKS.remove(member, registration);
                    registration.handle.unhook();
                }
            }
        }
    }

    private static void ensureInit() {
        if (xposedInterface == null) {
            throw new IllegalStateException("XposedBridge not initialized");
        }
    }
}
