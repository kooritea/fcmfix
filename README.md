# fcmself

Package: `sumicya.fcmself`

让 FCM/GCM 能唤醒未启动的应用并发送通知。基于 fcmfix，合并 FcmFuck、
`FLAG_RECEIVER_FOREGROUND` 和 `PowerExemptionManager` 兼容逻辑，使用 libxposed API 102。

## 作用域

- 必选：`system`
- 小米设备可选：`com.miui.powerkeeper`

应用界面用于维护允许唤醒的包名列表；配置通过 libxposed Remote Preferences 同步到
System Server 和其他已启用作用域。灰色状态表示服务或配置尚未就绪，并不代表应用已被禁用。

模块作用域含 `system_server`（无法干净地软重载），因此 `module.prop` 关闭了自动热重载
（`autoHotReload=false`）：应用内改配置会通过配置广播即时生效，只有升级 APK 后才需要重启。

## 检查

需要 JDK 21 和 Android SDK 37：

```bash
./gradlew testDebugUnitTest lint assembleRelease
```
