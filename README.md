# fcmself (Android 10-15)

Package: **`sumicya.fcmself`**

让 FCM/GCM 唤醒未启动的应用并发送通知。基于 fcmfix，合并 FcmFuck 能力，升级 libxposed API 102。

### 功能

- 阻止系统在应用停止时自动移除通知
- MIUI/HyperOS/OxygenOS/ColorOS 上解除 FCM 相关自启动限制
- 移除 MIUI/HyperOS 后台通知限制
- 合并 FcmFuck：
  - `FLAG_RECEIVER_FOREGROUND`
  - Android 12+ `PowerExemptionManager` 临时白名单（反射调用）
  - 高层 `broadcastIntent` 兜底
- 目标：单独启用即可无代理收消息

### LSPosed 作用域

- `system`
- `com.miui.powerkeeper`（可选）

### 构建

```bash
./gradlew assembleRelease
```

CI 会在签名 Secrets 缺失时上传 **unsigned** APK artifact。

### 说明

原包名 `com.kooritea.fcmfix` 已改为 `sumicya.fcmself`。安装前请卸载旧模块以免冲突。
