package com.kooritea.fcmfix.libxposed;

public abstract class XC_MethodReplacement extends XC_MethodHook {

    public static final XC_MethodReplacement DO_NOTHING = returnConstant(null);

    public static XC_MethodReplacement returnConstant(final Object constant) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return constant;
            }
        };
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        param.setResult(replaceHookedMethod(param));
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
