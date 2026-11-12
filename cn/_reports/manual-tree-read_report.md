# 悬浮窗手动读树交付记录

日期：2026-11-11。

聊天卡片顶部新增「读树」，悬浮球菜单新增「读取内容树」。点击后重新读取当前窗口，按应用适配器提取会话标题及消息，预览最近 10 条及发送方。仅能确认会话时显示正文不可读或发送方无法确认。无法取得窗口根、应用未适配或无法确认聊天页时分别提示。

`OverlayController.kt` 负责入口和预览。`MyAccessibilityService.kt` 使用本次解析结果确认会话并构建预览，用户点「分析读取结果」时才提交这份快照。会话令牌失效后要求重新读取。切换会话清除预览。README 补充使用说明。

执行 `bash gradlew testDebugUnitTest assembleDebug`，退出码 0。最终输出如下。

```text
BUILD SUCCESSFUL in 3s
42 actionable tasks: 10 executed, 32 up-to-date
```

JUnit XML 合计 36 项通过。未安装到手机，按钮触摸、面板布局及微信实际节点可见性待设备验证。
