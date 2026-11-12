# 微信结构读树修复

日期：2026-11-11。

## 根因

当前微信 8.0.80 树的 RecyclerView `bp0` 内，`bn1` 行的 `bkl` 正文包含 `OCR_TEST_123`，同一行 `bk1` 头像位于正文右侧。输入框为 `bkk`。标题区 `obq` 及其可见内容没有标题文字。此前适配器把有效标题作为返回快照的条件，手动读树又要求目标会话，因此丢弃了正文。

## 修改

- `WeChatAdapter.kt` 从实测列表和输入框识别聊天结构，仅采集列表消息行内的正文。发送方优先使用同一行的头像，再使用列表内的相对坐标。容器边界为空时仍遍历其后代。
- `MyAccessibilityService.kt` 允许对结构明确、标题未公开的微信聊天作手动预览和 Debug 日志。需要按名称检查会话白名单时仍拒绝无法核对的会话。该快照不绑定实时会话身份。
- `WeChatRulesTest.kt` 新增脱敏结构回归测试与无结构时的拒绝用例。

## 实际验证

`bash gradlew testDebugUnitTest assembleDebug` 输出 `BUILD SUCCESSFUL in 2s`。38 项测试通过，0 失败。ADB 覆盖安装输出 `Performing Streamed Install` 和 `Success`。

小米 14 同一微信页面点击「读取内容树」，项目自身服务的 `JevManualTree` 输出会话未公开、消息数 1，正文日志包含 `1. 我：OCR_TEST_123`。这次实测覆盖单条右侧测试正文。未验证其他微信版本、群聊、对方长消息或标题缺失时的分析与回填。
