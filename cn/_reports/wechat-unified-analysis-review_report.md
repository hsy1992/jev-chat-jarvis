# 微信共用分析链路审查

日期 2026-11-12。审查范围为当前工作区的读树、截图、共用分析和微信回填代码。包名迁移后的源文件尚未跟踪，直接按当前源码审查。未修改业务代码，也未覆盖安装本次审查的 APK。

## Standards

- **P1 回填核验比分析核验弱。** `MyAccessibilityService.kt:1071` 的 `isTreeReviewAlive` 只比较包名、窗口和读取时的白名单标题。`inputForReview` 再读一次根节点后直接定位输入框。微信在同一窗口切换聊天时，旧候选可能在无障碍切换事件到达前通过检查，延迟重试也有同样风险。每次写入和重试应从同一个已核验的根节点确认真实目标。会话身份不足时保留复制。
- **P2 `bkk` 首节点可能是容器。** `MyAccessibilityService.kt:1052` 直接取第一个同 ID 节点。此前真机样本中 `bkk` 同时是 `FrameLayout` 和子 `EditText`。后续检查缺少 `isEditable`，而且任意唯一输入框兜底可能命中搜索。应限定为消息编辑器并验证可写动作。
- 既有 `GuardedInputWriter.kt:17` 的覆盖与清空行为现在也用于微信。如果输入框有未发送文本，首轮会替换它，失败重试还可能先清空再粘贴。这是旧写入器的行为在新入口被扩大，建议补空输入限制或替换确认。

## Spec

读树和截图均进入 `submitSnapshot → runAnalysis → JevClient`。读树的角色校正、判断和候选都由同一 `OverlayController` 呈现。分享 Activity 的任务来源为系统分享。

- **P1 微信无标题时被绑定为普通会话。** `MyAccessibilityService.kt:209` 返回空标题，但 220 行仍生成非空 `Target`。758 行因此会把已分边的无标题快照当作普通会话。`ConversationSession.kt:20` 的身份只看包名、窗口和标题。白名单为空时，同窗口内的两个无标题聊天会得到相同身份。新聊天没有重叠消息时，门控可以不重开一轮，旧结果仍会通过实时会话校验。应将缺少稳定身份的读取结果统一作为独立快照。
- **P1 读树复核回填可能跨会话。** 与 Standards 的回填核验问题为同一个缺陷。
- 现有 README 和分享设计仍有将「校正并分析」描述为进入独立草稿的文字，需要与当前悬浮卡片行为同步。

## 验证

`bash gradlew testDebugUnitTest assembleDebug` 退出码 0。原始输出末尾如下。

```text
BUILD SUCCESSFUL in 4s
42 actionable tasks: 1 executed, 41 up-to-date
```

JUnit XML 共 41 项，0 失败，0 错误。现有写入器测试覆盖模拟 resolver 失效，但没有覆盖两个不同微信会话复用窗口 ID 时，服务 resolver 应当拒绝旧结果。上述风险根据源码可推导，未在真机执行跨会话写入测试。
