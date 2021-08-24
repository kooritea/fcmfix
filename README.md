# fcmfix(Android 10 & 11)

使用xposed让被完全停止的应用响应fcm，让fcm送达率达到100%，不错过任何通知  

- 允许fcm唤醒选中的应用来发送通知
- 解除miui12对后台应用的通知限制(非miui系统没影响)(仅作用于在fcmfix中选中的应用)
- 修复在国内网络下出现重连服务出现负数问题(貌似是miui优化的问题)
- 固定心跳间隔(默认117s,更改需要编辑配置文件)

---

## 注意

~~在国内版miui上，除了在本应用中勾选目标应用之外，还要给予目标应用自启动权限中的允许系统唤醒权限(eu版和国际版则不需要给自启动权限)~~

从0.4.0开始已经不再需要，感谢来自 @MinaMichita 的方法 [https://blog.minamigo.moe/archives/747](https://blog.minamigo.moe/archives/747)

---

## Lsposed
- 唤醒应用和解除miui通知限制需要勾选安卓系统作用(不需要勾选目标应用)
- fcm心跳修复和负数重连问题功能需要勾选com.google.android.gms

---

## 可能出现的问题

### 1、重启之后配置文件被复原
> 一般是你用了mt管理器那个编辑器的问题,可以尝试修改完后删除那个.bak后缀的文件，或者在设置中关闭生成bak文件，或者换一个编辑器 https://play.google.com/store/apps/details?id=in.mfile

### 2、遇到国内版锁屏后连接自动断开的问题请尝试使用针对国内版开发的版本
[https://blog.minamigo.moe/archives/747](https://blog.minamigo.moe/archives/747)

---

## 下面是手动找hook点的方法，从0.3.0版本开始不再需要手动反编译查找hook点了，但不排除会自动查找失败的情况，如果gms频繁崩溃或者gms状态中一直显示无服务，可以先手动找hook点检查或者请带上gms.apk发issues
- 1. 确保xposed模块已经运行，如果存在/data/data/com.google.android.gms/shared_prefs/fcmfix_config.xml则证明模块已经成功运行，这是配置文件，之后都是编辑这个文件的内容。
- 2. 下载MT管理器等可以进行反编译的工具
- 3. 对/data/app/com.google.android.gms-/base.apk进行反编译(在MT管理器对apk文件选择查看，点击classes.dex使用Dex编辑器++打开，全选 -> 确认)
- 4. 搜索 "Previous alarms will stay active" ,路径: / ,搜索类型: 代码，按道理应该只有一个搜索结果，将搜到类名(一般是4个字母)填入配置文件的timer_class项中
- 5. 回到MT管理器点击刚才搜索到的类，看文件最上面第九行左右开始属性声明，`.field private final d:Landroid/content/Intent;`将这个私有属性类型是Intent的属性`d(按自己实际情况填)`填入配置文件的timer_intent_property
- 6. 寻找一个没有返回值，只有一个长整形参数的public方法，一般是第90行左右的`.method public final a(J)V`,认准这个`final`和`(J)`和`V`找这个方法，把方法名`a`填入配置文件的timer_settimeout_method
- 7. 继续查看刚在找到的这个timer_settimeout_method，这个方法往下几行的`iget-wide v0, p0, L[xxxx];->[f]:J`,这个xxxx是最开始的类名，这个f就是我们要找的属性名，将这个找到的属性名`f`填入配置文件的timer_next_time_property
- 8. 修改完上面的配置项，现在配置文件大概是这样的
```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="heartbeatInterval" value="117000" />
    <string name="timer_intent_property">d</string>
    <string name="timer_next_time_property">f</string>
    <boolean name="enable" value="false" />
    <string name="timer_settimeout_method">a</string>
    <string name="timer_class">aazg</string>
    <string name="gms_version">20.39.15 (120400-335085812)</string>
    <boolean name="isInit" value="true" />
</map>

```
注: heartbeatInterval 设置为0的话则不固定心跳间隔时间，使用原本的自适应

- 9. 最后将配置文件的enable修改true，保存，重启手机

- 10. 一般来说gms更新改变的只有类名也就是timer_class


## 一些版本的配置文件

如果你不想自己找hook点的话可以看看下面哪个版本和你使用的版本一致，需要保证gms_version项和你手机上的配置文件一致，其他可以直接复制

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="heartbeatInterval" value="0" />
    <string name="timer_intent_property">d</string>
    <string name="timer_next_time_property">f</string>
    <boolean name="enable" value="true" />
    <string name="timer_settimeout_method">c</string>
    <string name="timer_class">acrp</string>
    <string name="gms_version">21.18.16 (150400-374723149)</string>
    <boolean name="isInit" value="true" />
</map>

<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="heartbeatInterval" value="0" />
    <string name="timer_intent_property">d</string>
    <string name="timer_next_time_property">f</string>
    <boolean name="enable" value="true" />
    <string name="timer_settimeout_method">c</string>
    <string name="timer_class">adbc</string>
    <string name="gms_version">21.21.16 (150400-378233385)</string>
    <boolean name="isInit" value="true" />
</map>

<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="heartbeatInterval" value="0" />
    <string name="timer_intent_property">d</string>
    <string name="timer_next_time_property">f</string>
    <boolean name="enable" value="true" />
    <string name="timer_settimeout_method">c</string>
    <string name="timer_class">adpi</string>
    <string name="gms_version">21.24.18 (150400-383468479)</string>
    <boolean name="isInit" value="true" />
</map>
```
