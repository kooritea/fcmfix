package com.kooritea.fcmfix.libxposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }

        public void resetReturnEarly() {
            this.returnEarly = false;
        }
    }

    public class Unhook {
        private final XposedBridge.HookHandleWrapper handle;

        Unhook(XposedBridge.HookHandleWrapper handle) {
            this.handle = handle;
        }

        public void unhook() {
            handle.unhook();
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }
}
