package com.kooritea.fcmfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
        JSONObject config = new JSONObject();
        try {
            FileInputStream fis = getContext().openFileInput("config.json");
            InputStreamReader inputStreamReader = new InputStreamReader(fis, StandardCharsets.UTF_8);
            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader reader = new BufferedReader(inputStreamReader);
            String line = reader.readLine();
            while (line != null) {
                stringBuilder.append(line).append('\n');
                line = reader.readLine();
            }
            config = new JSONObject(stringBuilder.toString());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        String[] COLUMN_NAME = { "key", "value" };
        MatrixCursor data = new MatrixCursor(COLUMN_NAME);
        try{
            data.addRow(new Object[]{"disableAutoCleanNotification", config.isNull("disableAutoCleanNotification") ? "0" : (config.getBoolean("disableAutoCleanNotification") ? "1" : "0") });
            JSONArray jsonAllowList = config.getJSONArray("allowList");
            for(int i = 0; i < jsonAllowList.length(); i++){
                data.addRow(new Object[]{"allowList",jsonAllowList.getString(i)});
            }
        } catch (JSONException e) {
            e.printStackTrace();
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