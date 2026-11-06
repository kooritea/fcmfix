package com.kooritea.fcmfix.util;

import java.lang.reflect.Method;

/** Resolve the permission/app-op/options block without relying on SDK numbers. */
public final class BroadcastSignature {
    public final int intentIndex;
    public final int appOpIndex;

    private BroadcastSignature(int intentIndex, int appOpIndex) {
        this.intentIndex = intentIndex;
        this.appOpIndex = appOpIndex;
    }

    public static BroadcastSignature resolve(Method method) {
        if (method.getReturnType() != int.class) return null;
        Class<?>[] types = method.getParameterTypes();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].getName();
        return resolve(names);
    }

    public static BroadcastSignature resolve(String[] types) {
        int intent = -1;
        int appOp = -1;
        for (int i = 0; i < types.length; i++) {
            if ("android.content.Intent".equals(types[i])) {
                if (intent != -1) return null;
                intent = i;
            }
            // resultExtras, permission arrays, appOp, options, ordered, sticky.
            if (i > 0 && i + 3 < types.length && "int".equals(types[i])
                    && ("android.os.Bundle".equals(types[i + 1])
                        || ("android.app.BroadcastOptions".equals(types[i + 1])
                            && "com.android.server.am.BroadcastSentEventRecord".equals(types[types.length - 1])))
                    && "boolean".equals(types[i + 2])
                    && "boolean".equals(types[i + 3])) {
                int previous = i - 1;
                while (previous >= 0 && "[Ljava.lang.String;".equals(types[previous])) previous--;
                if (previous == i - 1 || previous < 0
                        || !"android.os.Bundle".equals(types[previous])) continue;
                if (appOp != -1) return null;
                appOp = i;
            }
        }
        return intent >= 0 && appOp > intent ? new BroadcastSignature(intent, appOp) : null;
    }
}
