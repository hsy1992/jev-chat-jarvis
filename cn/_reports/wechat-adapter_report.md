# 微信节点适配器交付记录

日期：2026-11-11。

## 做法与证据

已有真机对照仅确认微信 8.0.80 在读屏服务开启时，一个测试文本出现在 `com.tencent.mm:id/bkl`，有屏幕坐标。尚无项目自身服务可读节点的真机证据，也没有逐条人工标注双方的完整节点样本。因此仅实现保守规则：可见聊天输入框、可见正文、可提取的非泛化标题、所有正文都能明确左右分类，才返回快照。无法满足条件返回 `null`，不自动把未知文本判为对方。

## 修改

- 新增 `app/src/main/java/com/dianming/phoneapp/capture/WeChatAdapter.kt`，包含 Android 节点包装与可单测的解析规则。只识别观察过的 `id/bkl`；按垂直位置排除顶栏和输入区；拒绝跨中线的方向不明气泡。
- 在 `MyAccessibilityService.kt` 注册适配器。微信节点解析为空时不触发自动整屏 OCR，避免整屏 OCR 将本人消息全记为对方。手动截图入口仍保留原有提示。
- 新增 `WeChatRulesTest.kt`，覆盖左右分类、顺序、方向不明、缺少输入框、无正文、顶栏与输入区过滤。
- 更新 README 与两份微信研究记录，将节点适配标为实验性待真机验证，并保留原研究发生时的代码状态。

## 实际验证

执行 `bash gradlew testDebugUnitTest`。退出码 0，末尾原始输出：

```text
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1s
23 actionable tasks: 3 executed, 20 up-to-date
```

## 自验缺口

- 未验证项目服务在手机上能否读到微信节点。默认沙箱中 `adb devices -l` 无法启动 ADB server，报告 `could not install *smartsocket* listener: Operation not permitted`；没有进一步操作手机。
- `id/bkl` 仅在微信 8.0.80 观察到，其他版本不保证相同。已有测试坐标记录了节点位置，但当时未记录该消息由谁发送，因此几何分类测试不能证明真机准确率。
- 长气泡、列表虚拟化、无输入框、群聊标题变化、节点隐藏状态均按保守不识别处理。后续需在项目自身服务可读取的条件下，对测试账号双方消息采集脱敏节点树，校正阈值及消息容器规则。
