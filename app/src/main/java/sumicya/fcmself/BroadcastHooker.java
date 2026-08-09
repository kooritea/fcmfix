package sumicya.fcmself;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;

public class BroadcastHooker implements XposedInterface.Hooker {
    private final int mode; // 0: AMS broadcast, 1: Queue intercept
    private final Object returnValue;
    private final Object field1; // Invoker or IntentIndices
    private final Object field2; // InfoField or IntIndices
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
            return FcmFixModule.hookBroadcastIntent(type, (XposedInterface.Invoker) field1, (Field) field2, (Field) returnValue, chain);
        } else {
            return FcmFixModule.hookBroadcastQueue(type, returnValue, (int[]) field1, (int[]) field2, chain);
        }
    }
}
