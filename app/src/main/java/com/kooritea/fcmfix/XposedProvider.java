package com.kooritea.fcmfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class XposedProvider extends ContentProvider {

    private static UriMatcher uriMatcher;

    static
    {
        uriMatcher=new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI("com.kooritea.fcmfix.provider","config",0);
    }

    @Override
    public boolean onCreate() {
        return false;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch (uriMatcher.match(uri))
        {
            default:
                break;
        }
        return null;
    }


    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        //这里填写查询逻辑
        SharedPreferences sharedPreferences = getContext().getSharedPreferences("config", Context.MODE_PRIVATE);
        String[] COLUMN_NAME = { "key", "value" };
        MatrixCursor data = new MatrixCursor(COLUMN_NAME);
        switch (selection){
            case "heartbeatInterval":
                data.addRow(new Object[]{"heartbeatInterval",sharedPreferences.getLong("heartbeatInterval",117000L)});
                break;
            case "allowList":
                for(String item : sharedPreferences.getStringSet("allowList",new HashSet<String>())){
                    data.addRow(new Object[]{"allowList",item});
                }
                break;
        }
        return data;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        //这里填写插入逻辑
        return null;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        //这里填写更新逻辑
        return 0;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        //这里填写删除逻辑
        return 0;
    }
}