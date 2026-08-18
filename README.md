# fcmself

Package: `sumicya.fcmself`  
Version: **1.3.0** (personal · **Android 16 only**)

让 FCM/GCM 能唤醒未启动的应用并发送通知。基于 fcmfix，合并 FcmFuck、
`FLAG_RECEIVER_FOREGROUND` 和 `PowerExemptionManager` 兼容逻辑，使用 libxposed API 102。

## 版本策略（自用）

| 版本 | minSdk | 说明 |
|------|--------|------|
| **1.3.0** | **36 (A16)** | 统一动画/边距 token；KeepNotification 仅 A16 |
| 1.2.0 | 33 | AutoStartFix 分项健康上报 |
| 1.1.0 | 31 | 去掉 10/11 |
| 1.0.0 | 29 | 初始基线 |

详见 [CHANGELOG.md](CHANGELOG.md)。

## 作用域

- 必选：`system`
- 小米设备可选：`com.miui.powerkeeper`

配置经 libxposed Remote Preferences 同步；`autoHotReload=false`（含 system_server）。

## 代码结构

| 包 | 职责 |
| --- | --- |
| `core` | 跨进程协议常量 |
| `model` | AppInfo / 筛选排序 |
| `data` | 扫描、配置、Xposed 桥 |
| `health` | Hook 健康检查 |
| `ui` | 主题、列表、Dock、FAB 菜单 |
| `xposed` | system_server / GMS / OEM Hook |

## 本地编译

- JDK **21**
- Android SDK Platform **37**、Build Tools **37.0.0**

```bash
./scripts/setup-android-sdk.sh
./scripts/check-build-environment.sh
./gradlew testDebugUnitTest assembleDebug
```

Release：`./gradlew assembleRelease lint --stacktrace`
