# fcmfix(Android 10以上 )

使用xposed让被完全停止的应用响应fcm，让fcm送达率达到100%，不错过任何通知  

[![Android CI](https://github.com/kooritea/fcmfix/workflows/Android%20CI/badge.svg)](https://github.com/kooritea/fcmfix/actions)

- 允许fcm唤醒选中的应用来发送通知
- 解除miui12对后台应用的通知限制(非miui系统没影响)(仅作用于在fcmfix中选中的应用)
- 修复在国内网络下出现重连服务出现负数问题(貌似是miui优化的问题)
- 固定心跳间隔(默认不开启,更改需要编辑配置文件/data/data/com.google.android.gms/shared_prefs/fcmfix_config.xml中的heartbeatInterval项，最小值为1000，1000==1s)
- 国内版miui电量和性能伪装为国际版，防止锁屏fcm断线
---

这个模块并不是为了让不能使用fcm的机器使用fcm，而是在能正常使用fcm的机器上进行改造。  
fcm的工作原理是通过公用系统级长链接来节省各个应用分别在后台维持长链接所消耗的资源。  
有了fcm，应用只需要占用一点内存静驻在后台即可接收推送，而不需要自己维持长链接。  
但是fcm本身只负责把远端消息通知给APP，但不会通知到用户，发送通知是应用接收到fcm后自己完成的。  
换句话说，应用没有在后台有存活的话，即使有fcm，也是不能收到通知的，这种情况下日志就会出现`Failed to broadcast to stopped app`。  
fcmfix的主要目的就是为了让即使不在后台的app也能顺利接收发送通知，不会出现`Failed to broadcast to stopped app`。  
心跳修复以及反miui屏蔽后台应用通知都是附加产物。  

微信走fcm可能意义不大：
- 不使用fcmfix的情况，无微信后台的时候就像上面说的，有fcm也不会收到消息，有微信后台的时候会走微信自己的长链接，虽然日志也能看到fcm调用成功。
- 使用fcmfix的情况，看接收消息的频率，要是经常收到消息反复启动微信反而耗电。要是偶尔才有消息的话微信+fcmfix才是一个好的选择。
---

## Lsposed
- 唤醒应用和解除miui通知限制需要勾选安卓系统作用(不需要勾选目标应用)
- fcm心跳修复和负数重连问题功能需要勾选com.google.android.gms

---

## SafetyNet 和 Widevine DRM等级
这个模块一般不会影响这两个检测，我的安卓11、miui12.5、magisk hide勾选gms、lsposed    
安装了fcmfix之后能够通过SafetyNet检测且Widevine DRM等级为L1。  
SafetyNet不通过请检查有没有科学上网

## 可能出现的问题

### 1、重启之后配置文件被复原
> 一般是你用了mt管理器那个编辑器的问题,可以尝试修改完后删除那个.bak后缀的文件，或者在设置中关闭生成bak文件，或者换一个编辑器 https://play.google.com/store/apps/details?id=in.mfile

### 2、遇到国内版锁屏后连接自动断开的问题请

> 请用0.4.7或以上版本


