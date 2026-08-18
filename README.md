# fcmself

Package: `sumicya.fcmself`  
Version: **1.2.0** (personal build)

让 FCM/GCM 能唤醒未启动的应用并发送通知。基于 fcmfix，合并 FcmFuck、
`FLAG_RECEIVER_FOREGROUND` 和 `PowerExemptionManager` 兼容逻辑，使用 libxposed API 102。

## 版本策略（自用）

本分支按「自用」维护：可接受破坏性变更，优先简化与稳定性，**不再保证旧系统兼容**。

| 版本 | minSdk | 说明 |
|------|--------|------|
| 1.2.0 | **33** (Android 13+) | AutoStartFix 分项探测 + 健康上报；KeepNotification 再简化 |
| 1.1.0 | 31 (Android 12+) | 去掉 10/11 分支；Broadcast / Keep / Reconnect 清理 |
| 1.0.0 | 29 | 初始基线 |

详见 [CHANGELOG.md](CHANGELOG.md)。

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

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/setup-android-sdk.sh
./scripts/check-build-environment.sh
./gradlew testDebugUnitTest assembleDebug
```

完整检查与 release：

```bash
./gradlew testDebugUnitTest assembleRelease lint --stacktrace
```

产物：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/app-release.apk`

Release 默认未配置签名，请用自己的密钥，勿把密钥提交进仓库。

### Android Studio

1. 用支持 AGP 9.3 的 Android Studio 打开根目录。
2. Gradle JDK 设为 21；SDK Manager 安装 API 37、Build Tools 37.0.0。
3. Sync 后 Build APK，或 `./gradlew assembleDebug`。
