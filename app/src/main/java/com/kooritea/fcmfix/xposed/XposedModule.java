package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.Intent;
import android.util.Log;

import com.kooritea.fcmfix.util.ContentProviderHelper;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedModule {

    protected XC_LoadPackage.LoadPackageParam loadPackageParam;

    protected XposedModule(final XC_LoadPackage.LoadPackageParam loadPackageParam){
        this.loadPackageParam = loadPackageParam;
    }

    protected void printLog(String text){
        Intent log = new Intent("com.kooritea.fcmfix.log");
        log.putExtra("text",text);
        try{
            AndroidAppHelper.currentApplication().getApplicationContext().sendBroadcast(log);
        }catch (Exception e){
            e.printStackTrace();;
        }

    }
}
