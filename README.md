# fcmself

Package: `sumicya.fcmself`

让 FCM/GCM 能唤醒未启动的应用并发送通知。基于 fcmfix，合并 FcmFuck、
`FLAG_RECEIVER_FOREGROUND` 和 `PowerExemptionManager` 兼容逻辑，使用 libxposed API 102。

## 作用域

- 必选：`system`
- 小米设备可选：`com.miui.powerkeeper`

模块作用域含 `system_server`（无法干净地软重载），因此 `module.prop` 关闭了自动热重载
（`autoHotReload=false`）：应用内改配置会通过配置广播即时生效，只有升级 APK 后才需要重启。

## 界面

应用完全基于 Jetpack Compose + Material 3（Dynamic Color、深色模式），底部三页签：

| 页签 | 内容 |
| --- | --- |
| 应用 | 应用网格 + 搜索 + 四种筛选（全部 / FCM / 已开 / 已关）+ 下拉刷新 + 长按详情 |
| 概览 | 服务状态、应用统计、功能开关、Hook 健康报告（进入页签自动发起 nonce 问答） |
| 日志 | 实时 FCM 事件日志：`system_server` 里的 BroadcastFix 每处理一次 FCM 广播，就通过跨进程广播回传事件，应用进程动态注册接收器实时渲染 |

灰色状态表示服务或配置尚未就绪，并不代表应用已被禁用。

## 代码结构

界面进程与被 Hook 的进程共用一份「协议」常量，其余代码按职责分层，`MainActivity` 只负责
把 Compose 主题和导航接起来：

| 包 | 职责 | 依赖框架 |
| --- | --- | --- |
| `core` | `Contract`：跨进程广播 Action、权限、远程配置键、FCM 日志与健康检查 extra | 无 |
| `model` | `AppInfo`、`FilterMode`（ALL / FCM_ONLY / ALLOWED / DISALLOWED）、`AppFilter`（搜索/筛选）、`AppCatalogOrder`（分组排序） | 无 |
| `data` | `AppCatalog`（进程级缓存 + 投递 + 生命周期）、`AppScanner`（纯扫描，无状态）、`IconLoader`（有界图标缓存）、`ConfigStore`（允许列表与开关，写穿远程配置 + `Snapshot` 原子读）、`XposedServiceBridge`（框架服务绑定）、`PackageChangeWatcher` | Android |
| `health` | `HookHealthReport`（纯文本渲染）、`HookHealthMonitor`（nonce 广播问答） | 部分 |
| `ui/navigation` | 三个页签的 `Screen` 定义与底部导航（日志页签带事件计数角标） | Compose |
| `ui/screens` | `AppListScreen`（网格 + 筛选 + 搜索 + 详情）、`DashboardScreen`（状态/统计/开关/健康）、`FcmLogScreen`（实时日志） | Compose |
| `ui/theme` | `FcmselfTheme`（M3 Dynamic Color + 深色回退配色） | Compose |
| `ui/viewmodel` | `AppViewModel`：用 StateFlow 桥接旧 Java 回调层（AppCatalog / XposedServiceBridge / PackageChangeWatcher / HookHealthMonitor），并接收 FCM 事件广播 | Android + Compose 生态 |
| `xposed` | 各 Hook 实现（运行在 `system_server` 等被注入进程）+ `ConfigManager`（线程安全配置快照）、`NotificationHelper`、`IceBoxDeliveryQueue`（冰箱延迟投递队列） | Xposed |

`core`、`model` 与 `HookHealthReport` 不引用任何 Android 类，因此排序、筛选、状态标记和
健康面板文案都由 JVM 单元测试覆盖（`./gradlew testDebugUnitTest`）。

## 本地编译

### 环境要求

- 64 位 Linux、macOS 或 Windows
- **JDK 21**（推荐 [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)）
- Android SDK Platform 37
- Android SDK Build Tools 37.0.0
- 网络能够访问 Google Maven、Maven Central、Xposed API 仓库和 Gradle 分发站点

项目已经包含 Gradle Wrapper，不需要单独安装 Gradle。请勿使用系统 Gradle 代替
`./gradlew`。

### Linux / macOS 命令行

先安装 JDK 21 并确保 `java -version` 显示 21。若系统中有多个 JDK，需要先选择
正确版本，例如：

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
```

然后在项目根目录执行：

```bash
./scripts/setup-android-sdk.sh
./scripts/check-build-environment.sh
./gradlew testDebugUnitTest assembleDebug
```

初始化脚本会将 Android 命令行工具和所需 SDK 组件安装到标准用户目录（Linux 为
`~/Android/Sdk`，macOS 为 `~/Library/Android/sdk`），接受 Android SDK 许可证，并生成
不纳入版本控制的 `local.properties`。也可以提前通过 `ANDROID_SDK_ROOT` 指定已有 SDK：

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk ./scripts/setup-android-sdk.sh
```

完整执行与 CI 相同的检查和 release 构建：

```bash
./gradlew testDebugUnitTest assembleRelease lint --stacktrace
```

常用产物位置：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/app-release.apk`
- Lint 报告：`app/build/reports/lint-results-*.html`

Release 构建默认未配置发布签名。如需直接安装或分发，请使用自己的密钥签名，且不要将
密钥或密码提交到仓库。

### Android Studio / Windows

1. 使用支持 AGP 9.3 的新版 Android Studio 打开项目根目录。
2. 在 **Settings > Build, Execution, Deployment > Build Tools > Gradle** 中将 Gradle JDK
   设为 JDK 21。
3. 在 **SDK Manager** 中安装 Android API 37、Build Tools 37.0.0 和 Platform Tools，并
   接受许可证。
4. 等待 Gradle Sync 完成后选择 **Build > Build APK(s)**；也可在 PowerShell 中执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

若首次同步失败，先检查代理是否允许访问 `google()`、Maven Central、
`https://api.xposed.info/` 和 `https://services.gradle.org/`。
