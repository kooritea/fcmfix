package com.kooritea.fcmfix.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class XposedUtils {
    public static XC_MethodHook.Unhook findAndHookConstructorAnyParam(String className, ClassLoader classLoader, XC_MethodHook callbacks, Class<?> ...parameterTypes){
        Class<?> clazz = XposedHelpers.findClass(className,classLoader);
        Constructor bestMatch = null;
        int matchCount = 0;
        for(Constructor<?> constructor : clazz.getDeclaredConstructors()){
            Class<?>[] constructorParamTypes = constructor.getParameterTypes();
            int _matchCount = 0;
            for(int i = 0;i<Math.min(constructorParamTypes.length,parameterTypes.length);i++){
                if(parameterTypes[i] == constructorParamTypes[i]){
                    _matchCount++;
                }
            }
            if(_matchCount >= matchCount){
                matchCount = _matchCount;
                bestMatch = constructor;
            }
        }
        if(bestMatch == null){
            throw new NoSuchMethodError(clazz.getName());
        }
        return XposedBridge.hookMethod(XposedHelpers.findConstructorExact(clazz,bestMatch.getParameterTypes()), callbacks);
    }

    public static XC_MethodHook.Unhook findAndHookMethodAnyParam(String className, ClassLoader classLoader, String methodName, XC_MethodHook callbacks, Object ...parameterTypes){
        Class<?> clazz = XposedHelpers.findClass(className,classLoader);
        Method bestMatch = null;
        int matchCount = 0;
        for(Method method : clazz.getDeclaredMethods()){
            if(methodName.equals(method.getName())){
                Class<?>[] methodParamTypes = method.getParameterTypes();
                int _matchCount = 0;
                for(int i = 0;i<Math.min(methodParamTypes.length,parameterTypes.length);i++){
                    if(parameterTypes[i] == methodParamTypes[i]){
                        _matchCount++;
                    }
                }
                if(_matchCount >= matchCount){
                    matchCount = _matchCount;
                    bestMatch = method;
                }
            }
        }
        if(bestMatch == null){
            throw new NoSuchMethodError(clazz.getName() + '#' + methodName);
        }
        return XposedBridge.hookMethod(XposedHelpers.findMethodExact(clazz,methodName,bestMatch.getParameterTypes()), callbacks);
    }
}
