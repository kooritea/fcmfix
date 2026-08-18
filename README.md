# fcmself

Package: `sumicya.fcmself`  
Version: **1.1.0** (personal build)

让 FCM/GCM 能唤醒未启动的应用并发送通知。基于 fcmfix，合并 FcmFuck、
`FLAG_RECEIVER_FOREGROUND` 和 `PowerExemptionManager` 兼容逻辑，使用 libxposed API 102。

## 1.1.0 变更（自用激进重构）

- **minSdk 提升到 31（Android 12+）**，彻底放弃 Android 10/11 特殊分支。
- `BroadcastFix`：移除 Q/R 详细参数表，优先现代默认位置 + 参数名回退；锁定钩子为主、高层钩子仅作兜底。
- `KeepNotification`：仅保留 API 31+ 布局逻辑。
- `ReconnectManagerFix`：清理 experimental TODO，探测失败时健康面板状态更明确，过旧 GMS 直接禁用。
- 版本号 → 1.1.0 / versionCode 56。

> 本分支按「自用」策略维护：可接受破坏性变更，优先简化与稳定性，不再保证旧系统兼容。

## 作用域

- 必选：`system`
- 小米设备可选：`com.miui.powerkeeper`

应用界面用于维护允许唤醒的包名列表；配置通过 libxposed Remote Preferences 同步到
System Server 和其他已启用作用域。灰色状态表示服务或配置尚未就绪，并不代表应用已被禁用。

模块作用域含 `system_server`（无法干净地软重载），因此 `module.prop` 关闭了自动热重载
（`autoHotReload=false`）：应用内改配置会通过配置广播即时生效，只有升级 APK 后才需要重启。

## 代码结构

界面进程与被 Hook 的进程共用一份「协议」常量，其余代码按职责分层，`MainActivity` 只负责
把这些组件接起来：

| 包 | 职责 | 依赖框架 |
| --- | --- | --- |
| `core` | `Contract`：跨进程广播 Action、权限、远程配置键、健康检查 extra | 无 |
| `model` | `AppInfo`、`FilterMode`、`AppFilter`（搜索/筛选）、`AppCatalogOrder`（分组排序） | 无 |
| `data` | `AppCatalog`（应用扫描与进程级缓存）、`IconLoader`（有界图标缓存）、`ConfigStore`（允许列表与开关，写穿远程配置）、`XposedServiceBridge`（框架服务绑定）、`PackageChangeWatcher` | Android |
| `health` | `HookHealthReport`（纯文本渲染）、`HookHealthMonitor`（nonce 广播问答） | 部分 |
| `ui` | `AppTheme`（M3E 取色）、`AppListAdapter`、`BottomDock`（筛选段 + 搜索 + FAB）、`FabMenuController`、`FeedbackBar`、`WindowChrome` | Android |
| `xposed` | 各 Hook 实现，运行在 `system_server` 等被注入进程中 | Xposed |

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
