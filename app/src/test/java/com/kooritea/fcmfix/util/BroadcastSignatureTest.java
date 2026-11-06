package com.kooritea.fcmfix.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BroadcastSignatureTest {
    private String[] signature(int intentIndex, int permissionArrays, boolean resultToApp) {
        List<String> types = new ArrayList<>();
        types.add("com.android.server.am.ProcessRecord");
        types.add("java.lang.String");
        if (intentIndex == 3) types.add("java.lang.String");
        types.add("android.content.Intent");
        types.add("java.lang.String");
        if (resultToApp) types.add("com.android.server.am.ProcessRecord");
        types.addAll(Arrays.asList("android.content.IIntentReceiver", "int", "java.lang.String", "android.os.Bundle"));
        for (int i = 0; i < permissionArrays; i++) types.add("[Ljava.lang.String;");
        types.addAll(Arrays.asList("int", "android.os.Bundle", "boolean", "boolean", "int", "int"));
        return types.toArray(new String[0]);
    }

    private void check(String[] types, int intentIndex, int appOpIndex) {
        BroadcastSignature resolved = BroadcastSignature.resolve(types);
        assertNotNull(resolved);
        assertEquals(intentIndex, resolved.intentIndex);
        assertEquals(appOpIndex, resolved.appOpIndex);
    }

    @Test public void android10() { check(signature(2, 1, false), 2, 9); }
    @Test public void android11() { check(signature(3, 1, false), 3, 10); }
    @Test public void android12Variants() {
        check(signature(3, 2, false), 3, 11);
        check(signature(3, 3, false), 3, 12);
    }
    @Test public void android13And14Variants() {
        check(signature(3, 3, false), 3, 12);
        check(signature(3, 3, true), 3, 13);
    }
    @Test public void android17DeviceSignature() {
        String[] head = signature(3, 3, true);
        List<String> types = new ArrayList<>(Arrays.asList(head));
        types.addAll(Arrays.asList("int", "int", "int",
                "android.app.BackgroundStartPrivileges", "[I", "java.util.function.BiFunction"));
        assertEquals(25, types.size());
        check(types.toArray(new String[0]), 3, 13);
    }
    @Test public void rejectsShortUnknownSignature() {
        assertNull(BroadcastSignature.resolve(new String[0]));
        assertNull(BroadcastSignature.resolve(new String[]{"android.content.Intent", "int"}));
    }
    @Test public void android17TracedImplementationSignature() {
        List<String> types = new ArrayList<>(Arrays.asList(signature(3, 3, true)));
        types.set(14, "android.app.BroadcastOptions");
        types.addAll(Arrays.asList("int", "int", "int",
                "android.app.BackgroundStartPrivileges", "[I", "java.util.function.BiFunction",
                "com.android.server.am.BroadcastSentEventRecord"));
        assertEquals(26, types.size());
        check(types.toArray(new String[0]), 3, 13);
    }
    @Test public void rejectsUnknownBroadcastOptionsLayout() {
        String[] types = signature(3, 3, true);
        types[14] = "android.app.BroadcastOptions";
        assertNull(BroadcastSignature.resolve(types));
    }
    @Test public void rejectsMissingOptionsAndAmbiguousIntent() {
        String[] types = signature(3, 3, true);
        types[14] = "java.lang.Object";
        assertNull(BroadcastSignature.resolve(types));
        types = signature(3, 3, true);
        types[0] = "android.content.Intent";
        assertNull(BroadcastSignature.resolve(types));
    }
    @Test public void rejectsAmbiguousAppOpAndMissingPermissions() {
        List<String> types = new ArrayList<>(Arrays.asList(signature(3, 3, true)));
        types.addAll(Arrays.asList("android.os.Bundle", "[Ljava.lang.String;", "int",
                "android.os.Bundle", "boolean", "boolean"));
        assertNull(BroadcastSignature.resolve(types.toArray(new String[0])));
        assertNull(BroadcastSignature.resolve(signature(3, 0, true)));
    }
    public boolean wrongReturn() { return false; }
    @Test public void rejectsWrongReturnType() throws Exception {
        assertNull(BroadcastSignature.resolve(getClass().getMethod("wrongReturn")));
    }
}
