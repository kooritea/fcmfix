package com.kooritea.fcmfix.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class ContentProviderHelper {

    public ContentResolver contentResolver;
    private Uri uri;
    private ArrayList<ContentObserver> contentObservers = new ArrayList<>();
    public Boolean useDefaultValue = false;

    public ContentProviderHelper(Context context, String uri){
        contentResolver = context.getContentResolver();
        this.uri = Uri.parse(uri);
    }
    public ContentProviderHelper(){
        useDefaultValue = true;
    }

    public Long getLong(String selection,Long defaultValue){
        if(useDefaultValue){
            return defaultValue;
        }
        Cursor cursor = contentResolver.query(uri, null, selection, null,null);
        if(cursor == null){
            return defaultValue;
        }
        cursor.getCount();
        while(cursor.moveToNext()) {
            if(selection.equals(cursor.getString(cursor.getColumnIndex("key")))){
                cursor.close();
                return cursor.getLong(cursor.getColumnIndex("value"));
            }
        }
        cursor.close();
        return defaultValue;
    }
    public String getString(String selection,String defaultValue){
        if(useDefaultValue){
            return defaultValue;
        }
        Cursor cursor =contentResolver.query(uri, null, selection, null,null);
        if(cursor == null){
            return defaultValue;
        }
        cursor.getCount();
        while(cursor.moveToNext()) {
            if(selection.equals(cursor.getString(cursor.getColumnIndex("key")))){
                cursor.close();
                return cursor.getString(cursor.getColumnIndex("value"));
            }
        }
        cursor.close();
        return defaultValue;
    }
    public Set<String> getStringSet(String selection){
        if(useDefaultValue){
            return new HashSet<String>();
        }
        Cursor cursor = contentResolver.query(uri, null, selection, null,null);
        if(cursor == null){
            return null;
        }
        cursor.getCount();
        Set<String> result = new HashSet<String>();
        while(cursor.moveToNext()) {
            if(selection.equals(cursor.getString(cursor.getColumnIndex("key")))){
                result.add(cursor.getString(cursor.getColumnIndex("value")));
            }
        }
        cursor.close();
        return result;
    }
}
