# 微信手动 OCR 回退修复

日期：2026-11-11。

## 根因与修改

微信适配器将“聊天页面无法确认”和“正文无法分边”都返回为 null。服务的 `targetFor()`、手动截图、离开聊天检测均依赖适配器，因此一条长气泡会同时阻断截图或取消正在执行的 OCR。

- `WeChatAdapter.kt`：能确认输入框和当前标题时，正文缺失或有歧义返回空消息快照；整屏内容不作部分截取，避免把旧消息当成最新消息。无输入框、无当前标题、泛化标题仍拒绝。
- `MyAccessibilityService.kt`：手动 OCR 使用已确认 target 的标题，预先检查会话许可并移除待执行的离开检测；微信加载标题不继承旧会话标题，异步回调时若无法确认实时身份则丢弃旧结果。原有微信自动 OCR 禁用条件保留。
- `WeChatRulesTest.kt`：更新空正文契约并覆盖混合可读／方向不明消息、标题过滤、空消息快照保留令牌与切换聊天失效。
- 审查和微信采集文档更新修复状态。

## 实际验证

命令：`bash gradlew testDebugUnitTest`，退出码 0。

```text
BUILD SUCCESSFUL in 4s
23 actionable tasks: 6 executed, 17 up-to-date
```

读取 JUnit XML 得到 33 项测试、0 失败、0 错误，其中微信规则 6 项、消息门控 7 项、WhatsApp 4 项、会话隔离 9 项、回填保护 7 项。

全工作区 `git diff --check` 指出此前 `app/build.gradle.kts` 的 namespace/applicationId 两行 CRLF 空白差异；该文件本次未修改。

## 验证边界

测试覆盖纯规则、快照契约和会话令牌行为，并编译了 Android 服务调用方；未运行 Android 仪器测试或真机截图，不宣称真机链路已验收。本次未安装 APK。控件树完全隐藏、键盘布局导致输入框无法识别等情况仍可能无法确认会话；分屏判边、群名后缀和无效容器剪枝问题不在本次修复范围。
