# fcmself

Package: `sumicya.fcmself`  
Version: **1.4.0** (personal · **Android 16 only**)

让 FCM/GCM 能唤醒未启动的应用并发送通知。  
基于 fcmfix，合并 FcmFuck / `FLAG_RECEIVER_FOREGROUND` / PowerExemption 逻辑，libxposed API 102。

## 作用域（LSPosed）

| 范围 | 用途 |
| --- | --- |
| **system**（必选） | BroadcastFix / AutoStartFix / KeepNotification；小米/一加按机型加载 |
| **com.google.android.gms**（建议勾选） | ReconnectManagerFix（FCM 无响应诊断与重连） |
| **com.miui.powerkeeper**（小米可选） | Powerkeeper 放行 |

配置经 libxposed Remote Preferences 同步；应用内改开关一般即时生效。  
**首次安装或换 APK 后需重启**；仅改白名单/开关通常不用重启。

## Hook 一览（功能未砍）

| 模块 | 进程 | 作用 |
| --- | --- | --- |
| BroadcastFix | system_server | 强制 INCLUDE_STOPPED + FOREGROUND；临时电源豁免；冰箱延后发送 |
| AutoStartFix | system_server | 自启动/后台限制放行 |
| KeepNotification | system_server | 停止应用时可选保留通知 |
| ReconnectManagerFix | GMS | 无响应提示 / 诊断入口相关 |
| MiuiLocalNotificationFix | system（小米） | 本地通知相关 |
| PowerkeeperFix | powerkeeper（小米） | 省电策略放行 |
| OplusProxyFix | system（一加/OPPO/realme） | 解冻/代理相关 |

## 分支

- `master` — 稳定
- `radical-simplify` — 激进精简（UI 方案 A、去测试）；**CI 已通过**

## 编译（本机可选）

JDK 21 · Platform 37 · `./gradlew assembleRelease lint`
