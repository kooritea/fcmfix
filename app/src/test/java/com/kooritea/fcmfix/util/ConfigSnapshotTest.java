package com.kooritea.fcmfix.util;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class ConfigSnapshotTest {
    @Test public void emptyConfigDoesNotAllowApps() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Collections.emptyMap());
        assertTrue(snapshot.allowList.isEmpty());
        assertFalse(snapshot.getBoolean("includeIceBoxDisableApp", false));
    }
    @Test public void copiesSetBeforePublishingToHookThreads() {
        Set<String> selected = new HashSet<>();
        selected.add("com.tencent.mm");
        Map<String, Object> values = new HashMap<>();
        values.put("allowList", selected);
        values.put("disableAutoCleanNotification", true);
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        selected.clear();
        values.put("disableAutoCleanNotification", false);
        assertTrue(snapshot.allowList.contains("com.tencent.mm"));
        assertTrue(snapshot.getBoolean("disableAutoCleanNotification", false));
    }
    @Test(expected = UnsupportedOperationException.class) public void publishedSetIsImmutable() {
        new ConfigSnapshot(Collections.emptyMap()).allowList.add("org.telegram.messenger");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidAllowListType() {
        new ConfigSnapshot(Collections.singletonMap("allowList", "com.tencent.mm"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidPackageType() {
        new ConfigSnapshot(Collections.singletonMap("allowList", Collections.singleton(123)));
    }
    @Test public void failedReadKeepsLastCompleteConfiguration() {
        ConfigSnapshot current = new ConfigSnapshot(Collections.singletonMap("allowList", Collections.singleton("org.telegram.messenger")));
        ConfigSnapshot original = current;
        try {
            current = new ConfigSnapshot(Collections.singletonMap("noResponseNotification", "invalid"));
            fail("Invalid options must not replace the last successful snapshot");
        } catch (IllegalArgumentException expected) {
            assertSame(original, current);
            assertTrue(current.allowList.contains("org.telegram.messenger"));
        }
    }
    @Test public void removalTakesEffectInNextSnapshot() {
        ConfigSnapshot before = new ConfigSnapshot(Collections.singletonMap("allowList", Collections.singleton("com.tencent.mm")));
        ConfigSnapshot after = new ConfigSnapshot(Collections.emptyMap());
        assertTrue(before.allowList.contains("com.tencent.mm"));
        assertFalse(after.allowList.contains("com.tencent.mm"));
    }
}
