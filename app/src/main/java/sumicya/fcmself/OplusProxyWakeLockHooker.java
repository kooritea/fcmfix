package sumicya.fcmself;

import io.github.libxposed.api.XposedInterface;

public class OplusProxyWakeLockHooker implements XposedInterface.Hooker {
    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        FcmFixModule.startupManager = chain.getThisObject();
        return result;
    }
}
