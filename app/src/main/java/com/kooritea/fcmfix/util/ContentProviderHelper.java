package com.kooritea.fcmfix.util;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.util.HashSet;
import java.util.Set;


public class ContentProviderHelper {

    public ContentResolver contentResolver;
    private final Cursor cursor;
    public Boolean useDefaultValue = false;

    public ContentProviderHelper(Context context, String uri){
        contentResolver = context.getContentResolver();
        this.cursor =contentResolver.query( Uri.parse(uri), null, "all", null,null);
    }

    @SuppressLint("Range")
    public Long getLong(String selection, Long defaultValue){
        if(useDefaultValue || cursor == null || cursor.getCount() == 0){
            return defaultValue;
        }
        cursor.moveToFirst();
        do{
            if(selection.equals(cursor.getString(cursor.getColumnIndex("key")))){
                return cursor.getLong(cursor.getColumnIndex("value"));
            }
        }while (cursor.moveToNext());
        return defaultValue;
    }
    @SuppressLint("Range")
    public String getString(String selection, String defaultValue){
        if(useDefaultValue || cursor == null || cursor.getCount() == 0){
            return defaultValue;
        }
        cursor.moveToFirst();
        do{
            if(selection.equals(cursor.getString(cursor.getColumnIndex("key")))){
                return cursor.getString(cursor.getColumnIndex("value"));
            }
        }while (cursor.moveToNext());
        return defaultValue;
    }
    @SuppressLint("Range")
    public Boolean getBoolean(String selection, Boolean defaultValue){
        if(useDefaultValue || cursor == null || cursor.getCount() == 0){
            return defaultValue;
        }
        cursor.moveToFirst();
        do{
            if(selection.equals(cursor.getString(cursor.getColumnIndex("key")))){
                return "1".equals(cursor.getString(cursor.getColumnIndex("value")));
            }
        }while (cursor.moveToNext());
        return defaultValue;
    }
    @SuppressLint("Range")
    public Set<String> getStringSet(String selection){
        if(useDefaultValue || cursor == null || cursor.getCount() == 0){
            return new HashSet<>();
        }
        cursor.moveToFirst();
        Set<String> result = new HashSet<>();
        do{
            if(selection.equals(cursor.getString(cursor.getColumnIndex("key")))){
                result.add(cursor.getString(cursor.getColumnIndex("value")));
            }
        }while (cursor.moveToNext());
        return result;
    }
    public void close(){
        this.cursor.close();
    }
}
