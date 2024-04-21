package com.kooritea.fcmfix.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class XposedUtils {


    public static XC_MethodHook.Unhook findAndHookConstructorAnyParam(String className, ClassLoader classLoader, XC_MethodHook callbacks, Class<?> ...parameterTypes){
        Class<?> clazz = XposedHelpers.findClass(className,classLoader);
        return findAndHookConstructorAnyParam(clazz, callbacks, parameterTypes );
    }

    public static XC_MethodHook.Unhook findAndHookConstructorAnyParam(Class<?> clazz, XC_MethodHook callbacks, Class<?> ...parameterTypes){
        Constructor<?> bestMatch = null;
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

    public static XC_MethodHook.Unhook findAndHookMethodMostParam(Class<?> clazz, String methodName, XC_MethodHook callbacks){
        Method bestMatch = null;
        for(Method method : clazz.getDeclaredMethods()){
            if(methodName.equals(method.getName())){
                if(bestMatch == null || method.getParameterTypes().length > bestMatch.getParameterTypes().length){
                    bestMatch = method;
                }
            }
        }
        if(bestMatch == null){
            throw new NoSuchMethodError(clazz.getName() + '#' + methodName);
        }
        return XposedBridge.hookMethod(XposedHelpers.findMethodExact(clazz,methodName,bestMatch.getParameterTypes()), callbacks);
    }

    public static XC_MethodHook.Unhook findAndHookMethodAnyParam(Class<?> clazz, String methodName, XC_MethodHook callbacks, Object ...parameterTypes){
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

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, int parameterCount, XC_MethodHook callbacks) {
        Method method = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterTypes().length == parameterCount) {
                method = m;
            }
        }
        return XposedBridge.hookMethod(XposedHelpers.findMethodExact(clazz,methodName, method.getParameterTypes()), callbacks);
    }

    public static Method findMethod(Class<?> clazz, String methodName, int parameterCount) {
        Method method = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterTypes().length == parameterCount) {
                method = m;
            }
        }
        return method;
    }

    public static XC_MethodHook.Unhook findAndHookMethodAnyParam(String className, ClassLoader classLoader, String methodName, XC_MethodHook callbacks, Object ...parameterTypes){
        Class<?> clazz = XposedHelpers.findClass(className,classLoader);
        return findAndHookMethodAnyParam(clazz,methodName,callbacks,parameterTypes);
    }

    public static Object getObjectFieldByPath(Object obj, String pathFieldName, Class<?> clazz){
        Object result = getObjectFieldByPath(obj,pathFieldName);
        if(result.getClass() != clazz){
            throw new NoSuchFieldError(obj.getClass().getName() + "#" +pathFieldName + ";Found " + result.getClass().getName() + " but not equal " + clazz.getName() + ".");
        }
        return result;
    }

    public static Object getObjectFieldByPath(Object obj, String pathFieldName){
        String[] paths = pathFieldName.split("\\.");
        Object tmp = obj;
        try{
            for(String fieldName : paths){
                tmp = XposedHelpers.getObjectField(tmp,fieldName);
            }
        }catch (Exception e){
            throw new NoSuchFieldError(obj.getClass().getName() + "#" +pathFieldName);
        }
        return tmp;
    }
}
