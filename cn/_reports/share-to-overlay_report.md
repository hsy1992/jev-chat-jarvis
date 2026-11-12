# 分享到悬浮窗交付记录

日期：2026-11-11。

## 变更

- `AndroidManifest.xml` 注册「分享到 Jev」的单项文本和图片入口，以及独立悬浮窗服务。
- `share/ShareImportActivity.kt` 接收系统分享，保留图片读取权限至本地识别完成，并在无法打开悬浮窗时留在应用内编辑器。
- `share/SharedImageImport.kt` 限额读取并解码图片，复用现有 ML Kit OCR，识别后只保留内存缩略图。
- `share/ShareTaskPanel.kt` 提供双方身份校正、分析、复制回复及任务切换和删除。悬浮窗与应用内使用同一界面。
- `share/ShareTasks.kt` 保存独立草稿和结果。编辑、删除通过修订号使旧结果失效。仅用户确认后请求模型。
- `share/ShareOverlayService.kt` 管理独立悬浮窗。`MainActivity.kt` 提供任务入口，`MyAccessibilityService.kt` 在分享悬浮窗可见期间暂停实时采集。
- `ShareDraftTest.kt` 验证未标注内容无法转换为对方消息、已确认角色顺序、改标时保留原文。README、隐私政策、术语和设计文档同步更新。

## 实际验证

执行 `bash gradlew testDebugUnitTest assembleDebug`，退出码 0，输出末尾如下。

```text
BUILD SUCCESSFUL in 1s
42 actionable tasks: 9 executed, 33 up-to-date
```

JUnit XML 合计 36 项测试、0 失败、0 错误。检查 APK 的二进制 Manifest，确认 `ShareImportActivity` 包含 `ACTION_SEND` 和 `text/plain`、`image/*` 过滤器，`ShareOverlayService` 已打包。

产物：[app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk)。

## 设备验证缺口

`adb devices -l` 未列出设备。尝试启动 Android 16 只读模拟器实例，系统镜像目录缺失，启动输出 `Cannot find AVD system path`。未安装 APK 或实测微信分享、悬浮窗键盘、图片 URI 和 OCR 链路。

设备验收应覆盖无障碍关闭时的文本与截图分享、浮窗授权开关、第二次分享后返回旧任务，以及未标注正文无法发起分析。微信是否在其当前分享菜单中提供相应数据格式需单独记录。
