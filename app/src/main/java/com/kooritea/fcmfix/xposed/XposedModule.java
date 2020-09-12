package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.Intent;

import java.lang.reflect.Method;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedModule {

    protected XC_LoadPackage.LoadPackageParam loadPackageParam;

    protected XposedModule(final XC_LoadPackage.LoadPackageParam loadPackageParam){
        this.loadPackageParam = loadPackageParam;
        this.startHook();
    }

    protected void startHook(){};

    protected XSharedPreferences getXSharedPreferences(){
        XSharedPreferences xSharedPreferences = new XSharedPreferences("com.kooritea.fcmfix","config");
        xSharedPreferences.makeWorldReadable();
        return xSharedPreferences;
    }

    protected void printLog(String text){
        Intent log = new Intent("com.kooritea.fcmfix.log");
        log.putExtra("text",text);
        AndroidAppHelper.currentApplication().getApplicationContext().sendBroadcast(log);
    }
}
