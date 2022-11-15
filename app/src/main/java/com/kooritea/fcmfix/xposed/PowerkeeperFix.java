package com.kooritea.fcmfix.xposed;

import com.kooritea.fcmfix.util.XposedUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class PowerkeeperFix extends XposedModule {
    public PowerkeeperFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHook();
    }

    protected void startHook(){
        try{
            XposedUtils.findAndHookMethodAnyParam("android.os.SystemProperties",loadPackageParam.classLoader,"get",new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String name = (String)param.args[0];
                    if("ro.product.mod_device".equals(name)){
                        String device = (String)XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.SystemProperties", loadPackageParam.classLoader),"get", "ro.product.name");
                        String modDevice = (String)param.getResult();
                        if(!modDevice.endsWith("_global") && !"".equals(device) && device != null){
                            printLog("[powerkeeper]" + device + "_global");
                            param.setResult(device + "_global");
                        }
                    }
                }
            });
            XposedHelpers.setStaticBooleanField(XposedHelpers.findClass("miui.os.Build",loadPackageParam.classLoader), "IS_INTERNATIONAL_BUILD", true);
            XposedHelpers.setStaticBooleanField(XposedHelpers.findClass("miui.os.Build",loadPackageParam.classLoader), "IS_GLOBAL_BUILD", true);
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart", false);
        }
    }
}
