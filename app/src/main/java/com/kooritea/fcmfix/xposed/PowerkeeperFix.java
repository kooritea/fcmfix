package com.kooritea.fcmfix.xposed;

import android.content.Context;

import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Field;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class PowerkeeperFix extends XposedModule {
    public PowerkeeperFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHook();
    }

    protected void startHook(){
        try {

            Class<?> MilletConfig = XposedHelpers.findClassIfExists("com.miui.powerkeeper.millet.MilletConfig", loadPackageParam.classLoader);
            XposedHelpers.setStaticBooleanField(MilletConfig, "isGlobal", true);
            printLog("Set com.miui.powerkeeper.millet.MilletConfig.isGlobal to true");

            Class<?> Misc = XposedHelpers.findClassIfExists("com.miui.powerkeeper.provider.SimpleSettings.Misc", loadPackageParam.classLoader);
            printLog("[fcmfix] start hook com.miui.powerkeeper.provider.SimpleSettings.Misc.getBoolean");
            XposedUtils.findAndHookMethod(Misc, "getBoolean", 3, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    if("gms_control".equals((String) methodHookParam.args[1])) {
                        printLog("Success: Success: PowerKeeper GMS Limitation. ", false);
                        methodHookParam.setResult(false);
                    }
                }
            });

            Class<?> MilletPolicy = XposedHelpers.findClassIfExists("com.miui.powerkeeper.millet.MilletPolicy", loadPackageParam.classLoader);

            XC_MethodHook methodHook = new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    Field[] declaredFields = null;
                    super.afterHookedMethod(methodHookParam);
                    boolean mSystemBlackList = false;
                    boolean whiteApps = false;
                    boolean mDataWhiteList = false;

                    for (Field field : MilletPolicy.getDeclaredFields()) {
                        if (field.getName().equals("mSystemBlackList")) {
                            mSystemBlackList = true;
                        } else if (field.getName().equals("whiteApps")) {
                            whiteApps = true;
                        } else if (field.getName().equals("mDataWhiteList")) {
                            mDataWhiteList = true;
                        }
                    }

                    if (mSystemBlackList) {
                        List blackList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "mSystemBlackList");
                        blackList.remove("com.google.android.gms");
                        XposedHelpers.setObjectField(methodHookParam.thisObject, "mSystemBlackList", blackList);
                        printLog("Success: MilletPolicy mSystemBlackList.");
                    } else {
                        printLog("Error: MilletPolicy. Field not found: com.miui.powerkeeper.millet.MilletPolicy.mSystemBlackList");
                    }
                    if (whiteApps) {
                        List whiteAppList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "whiteApps");
                        whiteAppList.remove("com.google.android.gms");
                        whiteAppList.remove("com.google.android.ext.services");
                        XposedHelpers.setObjectField(methodHookParam.thisObject, "whiteApps", whiteAppList);
                        printLog("Success: MilletPolicy whiteApps.");
                    } else {
                        printLog("Error: MilletPolicy. Field not found: com.miui.powerkeeper.millet.MilletPolicy.whiteApps");
                    }
                    if (mDataWhiteList) {
                        List dataWhiteList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "mDataWhiteList");
                        dataWhiteList.add("com.google.android.gms");

                        XposedHelpers.setObjectField(methodHookParam.thisObject, "mDataWhiteList", dataWhiteList);
                        printLog("Success: MilletPolicy mDataWhiteList.");
                    }

                }
            };
            printLog("[fcmfix] start hook com.miui.powerkeeper.millet.MilletPolicy constructor");
            XposedHelpers.findAndHookConstructor(MilletPolicy, new Object[] {Context.class, methodHook});

        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.ProcessMemoryCleaner.checkBackgroundAppException", false);
        }
    }
}
