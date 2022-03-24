package com.kooritea.fcmfix;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import android.graphics.drawable.Drawable;

import android.os.Bundle;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private AppListAdapter appListAdapter;
    Set<String> allowList = new HashSet<String>();
    JSONObject config = new JSONObject();

    private class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public Boolean isAllow = false;
        public AppInfo(PackageInfo packageInfo){
            this.name = packageInfo.applicationInfo.loadLabel(getPackageManager()).toString();
            this.packageName = packageInfo.packageName;
            this.icon = packageInfo.applicationInfo.loadIcon(getPackageManager());
        }
    }

    private class AppListAdapter  extends BaseAdapter {
        private List<AppInfo> appList;
        private LayoutInflater mInflater;
        AppListAdapter(Context context){
            mInflater = LayoutInflater.from(context);
            Set<String> allowListSet = new HashSet<>(allowList);
            allowListSet.containsAll(allowList);
            List<AppInfo> _allowList = new ArrayList<>();
            List<AppInfo> _notAllowList = new ArrayList<>();
            List<AppInfo> _notFoundFcm = new ArrayList<>();
            PackageManager packageManager = getPackageManager();
            for(PackageInfo packageInfo : packageManager.getInstalledPackages(PackageManager.GET_RECEIVERS)){
                boolean flag = false;
                AppInfo appInfo = new AppInfo(packageInfo);
                if (packageInfo.receivers != null) {
                    for (ActivityInfo  receiverInfo : packageInfo.receivers ){
                        if(receiverInfo.name.equals("com.google.firebase.iid.FirebaseInstanceIdReceiver") || receiverInfo.name.equals("com.google.android.gms.measurement.AppMeasurementReceiver")){
                            flag = true;
                            break;
                        }
                    }
                }else{
                    continue;
                }
                if(allowListSet.contains(appInfo.packageName)){
                    appInfo.isAllow = true;
                    _allowList.add(appInfo);
                }else{
                    if(flag){
                        _notAllowList.add(appInfo);
                    }else{
                        _notFoundFcm.add(appInfo);
                    }
                }
            }
            Collections.sort(_allowList, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo o1, AppInfo o2) {
                    return Collator.getInstance(Locale.CHINESE).compare(o1.name,o2.name);
                }
            });
            Collections.sort(_notAllowList, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo o1, AppInfo o2) {
                    return Collator.getInstance(Locale.CHINESE).compare(o1.name,o2.name);
                }
            });
            Collections.sort(_notFoundFcm, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo o1, AppInfo o2) {
                    return Collator.getInstance(Locale.CHINESE).compare(o1.name,o2.name);
                }
            });
            _allowList.addAll(_notAllowList);
            _allowList.addAll(_notFoundFcm);
            this.appList = _allowList;
        }
        @Override
        public int getCount() {
            return appList.size();
        }

        @Override
        public AppInfo getItem(int position) {
            return appList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppInfo appInfo = getItem(position);
            @SuppressLint({"InflateParams", "ViewHolder"}) View view = mInflater.inflate(R.layout.app_item,null);
            TextView name = view.findViewById(R.id.name);
            name.setText(appInfo.name);
            TextView packageName = view.findViewById(R.id.packageName);
            packageName.setText(appInfo.packageName);
            ImageView icon = view.findViewById(R.id.icon);
            icon.setImageDrawable(appInfo.icon);
            CheckBox checkBox = view.findViewById(R.id.isAllow);
            checkBox.setChecked(appInfo.isAllow);
            return view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            FileInputStream fis = this.openFileInput("config.json");
            InputStreamReader inputStreamReader = new InputStreamReader(fis, StandardCharsets.UTF_8);
            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader reader = new BufferedReader(inputStreamReader);
            String line = reader.readLine();
            while (line != null) {
                stringBuilder.append(line).append('\n');
                line = reader.readLine();
            }
            this.config = new JSONObject(stringBuilder.toString());
            JSONArray jsonAllowList = this.config.getJSONArray("allowList");
            for(int i = 0; i < jsonAllowList.length(); i++){
                this.allowList.add(jsonAllowList.getString(i));
            }
            if(this.config.isNull("heartbeatInterval")){
                this.config.put("heartbeatInterval", "0L");
            }
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        final ListView listView = findViewById(R.id.listView);
        this.appListAdapter = new AppListAdapter(this);
        listView.setAdapter(this.appListAdapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3) {

                AppInfo appInfo = appListAdapter.getItem(position);
                if(appInfo.isAllow){
                    deleteAppInAllowList(appInfo.packageName);
                }else{
                    addAppInAllowList(appInfo.packageName);
                }
                appInfo.isAllow = !appInfo.isAllow;
                appListAdapter.notifyDataSetChanged();
            }
        });
    }

    private void addAppInAllowList(String packageName){
        this.allowList.add(packageName);
        this.updateAllowList();
    }
    private void deleteAppInAllowList(String packageName){
        this.allowList.remove(packageName);
        this.updateAllowList();
    }

    private void updateAllowList(){
        try {
            FileOutputStream fos = this.openFileOutput("config.json", Context.MODE_PRIVATE);
            this.config.put("allowList", new JSONArray(this.allowList));
            fos.write(this.config.toString(2).getBytes());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        this.sendBroadcast(new Intent("com.kooritea.fcmfix.update.config"));
    }
}