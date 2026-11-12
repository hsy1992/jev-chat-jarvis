# 手动读树日志

日期：2026-11-11。

`MyAccessibilityService.kt` 在 Debug 版手动读树成功后，通过 `JevManualTree` 逐条输出会话、发送方和正文。长正文分段输出，空树或无法识别聊天时输出原因。`PRIVACY.md` 补充了 Debug 手动日志的说明。

`bash gradlew testDebugUnitTest assembleDebug` 退出码为 0，输出 `BUILD SUCCESSFUL in 2s`。ADB 覆盖安装到设备 `8caa9292`，输出 `Performing Streamed Install` 和 `Success`。本轮未在手机上点击「读树」验证实际聊天输出。

查看命令为 `adb logcat -s JevManualTree:D`。
