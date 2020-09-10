package com.kooritea.fcmfix;

import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.ReconnectManagerFix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedMain implements IXposedHookLoadPackage {

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if(loadPackageParam.packageName.equals("android")){
            new BroadcastFix(loadPackageParam);
        }
        if(loadPackageParam.packageName.equals("com.google.android.gms")){
            new ReconnectManagerFix(loadPackageParam);
        }
    }


}
