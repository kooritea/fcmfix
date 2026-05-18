package com.kooritea.fcmfix.xposed;

import android.content.res.AssetManager;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class OplusBatteryFix extends XposedModule {

    private static final String OMS_MANIFEST = "oms/oms_1.0.json";
    private static final String OMS_DIR = "oms";

    public OplusBatteryFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHookOmsAssets();
        } catch (Throwable e) {
            printLog("hook error OplusBatteryFix:" + e.getMessage());
        }
    }

    private void startHookOmsAssets() {
        XposedHelpers.findAndHookMethod(AssetManager.class, "open", String.class, new OmsOpenHook());
        XposedHelpers.findAndHookMethod(AssetManager.class, "open", String.class, int.class, new OmsOpenHook());
        XposedHelpers.findAndHookMethod(AssetManager.class, "list", String.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!OMS_DIR.equals(normalizeAssetPath((String) param.args[0]))) {
                    return;
                }
                Object result = param.getResult();
                if (!(result instanceof String[])) {
                    return;
                }
                param.setResult(filterAssetList((String[]) result));
            }
        });
        printLog("hook Oplus battery oms assets");
    }

    private static final class OmsOpenHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String path = normalizeAssetPath((String) param.args[0]);
            if (isRestrictPluginAsset(path)) {
                param.setThrowable(new FileNotFoundException(path));
                return;
            }
            if (!OMS_MANIFEST.equals(path)) {
                return;
            }

            InputStream original = (InputStream) XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            try {
                param.setResult(new ByteArrayInputStream(filterOmsManifest(readAll(original))));
            } finally {
                original.close();
            }
        }
    }

    private static byte[] filterOmsManifest(byte[] manifestBytes) throws Exception {
        JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        JSONArray splits = manifest.optJSONArray("mSplits");
        if (splits == null) {
            return manifestBytes;
        }

        boolean changed = false;
        for (int i = splits.length() - 1; i >= 0; i--) {
            JSONObject split = splits.optJSONObject(i);
            if (split != null && isRestrictSplit(split)) {
                splits.remove(i);
                changed = true;
            }
        }
        return changed ? manifest.toString().getBytes(StandardCharsets.UTF_8) : manifestBytes;
    }

    private static boolean isRestrictSplit(JSONObject split) {
        return containsRestrict(split.optString("mSplitName"))
                || containsRestrict(split.optString("mApplicationName"));
    }

    private static String[] filterAssetList(String[] assets) {
        ArrayList<String> kept = new ArrayList<>(assets.length);
        for (String asset : assets) {
            if (!isRestrictPluginAsset(asset)) {
                kept.add(asset);
            }
        }
        return kept.toArray(new String[0]);
    }

    private static boolean isRestrictPluginAsset(String path) {
        return containsRestrict(normalizeAssetPath(path));
    }

    private static boolean containsRestrict(String value) {
        return value != null && value.toLowerCase().contains("restrict");
    }

    private static String normalizeAssetPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("assets/")) {
            normalized = normalized.substring("assets/".length());
        }
        return normalized;
    }

    private static byte[] readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }
}
