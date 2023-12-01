package com.kooritea.fcmfix.xposed;

import android.content.ComponentName;

import java.lang.reflect.Field;
import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MiuiHomeHandler extends XposedModule {
    public MiuiHomeHandler(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHook();
    }

    protected void startHook() {
        try {
            final Class<?> AppFilter = XposedHelpers.findClassIfExists("com.miui.home.launcher.AppFilter", loadPackageParam.classLoader);

            XC_MethodHook methodHook = new XC_MethodHook() { // from class: moe.minamigo.miuigms.XposedMain.13
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    super.afterHookedMethod(methodHookParam);
                    for (Field field : AppFilter.getDeclaredFields()) {
                        if (field.getName().equals("mSkippedItems")) {
                            HashSet hashSet = (HashSet) XposedHelpers.getObjectField(methodHookParam.thisObject, "mSkippedItems");
                            hashSet.remove(new ComponentName("com.google.android.gms", "com.google.android.gms.app.settings.GoogleSettingsActivity"));
                            hashSet.remove(new ComponentName("com.google.android.gms", "com.google.android.gms.common.settings.GoogleSettingsActivity"));
                            hashSet.remove(new ComponentName("com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.SearchActivity"));
                            hashSet.remove(new ComponentName("com.google.android.googlequicksearchbox", "com.google.android.handsfree.HandsFreeLauncherActivity"));
                            printLog("Success: MiuiHome Google items.");
                            return;
                        }
                    }
                    printLog("Error: MiuiHome Google items. Google icons will not show in MiuiLauncher! Field not found: com.miui.home.launcher.AppFilter.mSkippedItems");
                }
            };
            XposedHelpers.findAndHookConstructor(AppFilter, methodHook);
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("Error: MiuiHome Google items. Google icons will not show in MiuiLauncher! Field not found: com.miui.home.launcher.AppFilter.mSkippedItems", false);
        }
    }
}
