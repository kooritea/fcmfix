package com.kooritea.fcmfix.xposed;

import java.lang.reflect.Field;

import de.robv.android.xposed.XSharedPreferences;

public class XposeModule {
    protected XSharedPreferences getXSharedPreferences(){
        XSharedPreferences xSharedPreferences = new XSharedPreferences("com.kooritea.fcmfix","config");
        xSharedPreferences.makeWorldReadable();
        return xSharedPreferences;
    }
}
