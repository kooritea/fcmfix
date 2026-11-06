package com.kooritea.fcmfix.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One immutable configuration for all hook threads. Invalid reads are rejected. */
public final class ConfigSnapshot {
    public final Set<String> allowList;
    private final Map<String, Boolean> options;

    public ConfigSnapshot(Map<String, ?> values) {
        Set<String> selected = new HashSet<>();
        Object rawList = values.get("allowList");
        if (rawList != null) {
            if (!(rawList instanceof Set<?>)) throw new IllegalArgumentException("Invalid allowList");
            for (Object item : (Set<?>) rawList) {
                if (!(item instanceof String)) throw new IllegalArgumentException("Invalid package name");
                selected.add((String) item);
            }
        }
        Map<String, Boolean> flags = new HashMap<>();
        for (String key : new String[]{"disableAutoCleanNotification", "includeIceBoxDisableApp", "noResponseNotification"}) {
            Object value = values.get(key);
            if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("Invalid option: " + key);
            if (value != null) flags.put(key, (Boolean) value);
        }
        allowList = Collections.unmodifiableSet(selected);
        options = Collections.unmodifiableMap(flags);
    }

    public boolean getBoolean(String key, boolean fallback) {
        Boolean value = options.get(key);
        return value == null ? fallback : value;
    }
}
