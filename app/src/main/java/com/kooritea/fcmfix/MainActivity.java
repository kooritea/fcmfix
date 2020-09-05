package com.kooritea.fcmfix;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
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
    SharedPreferences sharedPreferences;
    SharedPreferences.Editor sharedPreferencesEditor;
    Set<String> allowList;

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
            List<AppInfo> _allowList = new ArrayList<>();
            List<AppInfo> _notAllowList = new ArrayList<>();
            PackageManager packageManager = getPackageManager();
            for(PackageInfo packageInfo : packageManager.getInstalledPackages(PackageManager.GET_RECEIVERS)){
                Boolean flag = false;
                if (packageInfo.receivers != null) {
                    for (ActivityInfo  receiverInfo : packageInfo.receivers ){
                        if(receiverInfo.name.equals("com.google.firebase.iid.FirebaseInstanceIdReceiver")){
                            flag = true;
                            break;
                        }
                    }
                }else{
                    continue;
                }
                if(!flag){
                    continue;
                }
                AppInfo appInfo = new AppInfo(packageInfo);
                for(String item : allowList){
                    if(item.equals(appInfo.packageName)){
                        appInfo.isAllow = true;
                        _allowList.add(appInfo);
                        break;
                    }
                }
                if(!appInfo.isAllow){
                    _notAllowList.add(appInfo);
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
            _allowList.addAll(_notAllowList);
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
            View view = mInflater.inflate(R.layout.app_item,null);
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

    @SuppressLint("WrongConstant")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.sharedPreferences = this.getSharedPreferences("config",Context.MODE_PRIVATE);
        this.sharedPreferencesEditor = this.sharedPreferences.edit();
        this.allowList = new HashSet<String>(this.sharedPreferences.getStringSet("allowList",new HashSet<String>()));
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
        this.sharedPreferencesEditor.putStringSet("allowList",this.allowList);
        this.sharedPreferencesEditor.commit();
        this.setWorldReadable();
    }
    private void deleteAppInAllowList(String packageName){
        this.allowList.remove(packageName);
        this.sharedPreferencesEditor.putStringSet("allowList",this.allowList);
        this.sharedPreferencesEditor.commit();
        this.setWorldReadable();
    }

    private void setWorldReadable() {
        File dataDir = new File(getApplicationInfo().dataDir);
        File prefsDir = new File(dataDir, "shared_prefs");
        File prefsFile = new File(prefsDir, "config.xml");
        if (prefsFile.exists()) {
            for (File file : new File[]{dataDir, prefsDir, prefsFile}) {
                file.setReadable(true, false);
                file.setExecutable(true, false);
            }
        }
    }
}