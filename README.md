# fcmself

Package: `sumicya.fcmself`  
Version: **1.4.0** (personal · **Android 16 only**)

让 FCM/GCM 能唤醒未启动的应用并发送通知。  
基于 fcmfix，合并 FcmFuck / `FLAG_RECEIVER_FOREGROUND` / PowerExemption 逻辑，libxposed API 102。

## 作用域

- 必选：`system`
- 小米可选：`com.miui.powerkeeper`
- 一加 / OPPO / realme：Oplus 相关 Hook 自动按机型加载

配置经 libxposed Remote Preferences 同步。

## 代码结构（精简后）

| 包 | 职责 |
| --- | --- |
| `core` | 跨进程协议常量 |
| `model` | AppInfo / 筛选 |
| `data` | 扫描、配置、Xposed 桥 |
| `health` | Hook 健康检查 |
| `ui` | 列表 + 底部 Dock（方案 A）+ FAB 菜单 |
| `xposed` | system_server / GMS / 小米 / 一加 Hook |

## 本地编译

- JDK **21**
- Android SDK Platform **37**、Build Tools **37.0.0**

```bash
./scripts/setup-android-sdk.sh
./scripts/check-build-environment.sh
./gradlew assembleDebug
```

Release：`./gradlew assembleRelease lint --stacktrace`

## 分支

- `master` — 稳定
- `radical-simplify` — 激进精简（UI 方案 A、去测试、去历史 workflow）
