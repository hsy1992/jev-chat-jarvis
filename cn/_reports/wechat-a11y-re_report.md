# wechat-a11y-re 交付报告

任务：用 rea 分析 `~/Desktop/base.apk`，与 `com.dianming.phoneapp` 对比，查明为什么读不到内容树；并把结论落到 docs。

完整分析文档：[docs/wechat-a11y-re.md](../docs/wechat-a11y-re.md)。

## 根因（带证据）

1. `base.apk` = 微信 8.0.80（`com.tencent.mm`，versionCode 3220，targetSdk 34，仅 arm64，18 dex，内嵌 Flutter/XWeb），**没有**声明任何无障碍服务，但代码里带 `com.tencent.mm.accessibility.*` 框架。
2. 微信把 `com.dianming.phoneapp.MyAccessibilityService` 写死在 `AccExptServiceKt.DefaultWhiteServiceList`（`classes7.dex`，`0x2307fc`，string@acd5），与 Google SelectToSpeak 并列。
3. `AccExptService.checkHasServiceInList`（`0x2b4bc8`）读 `getEnabledAccessibilityServiceList(-1)` 与 `Settings.Secure.enabled_accessibility_services`，用 `xd6.o0.E`（`indexOf>=0`，即 contains）做**子串匹配**；`o0.G` 只是转发到 `E`。→ 服务类必须落在根包，`.capture` 子包匹配不上。
4. 非白名单服务的处理：`MMAccessibilityDelegateWrap.onInitializeAccessibilityNodeInfo`（`0x2af57c`）按 `needClearNodeInfo / needUseFakeInfo` 清空或伪造节点（`MapExpandKt.clearInfo` 置空 text/contentDescription/viewId/hintText）；`performAccessibilityAction` 按 `needInterceptAccAction` 吞点击；`checkEvilServiceStatus`（`0x2b552c`）命中 evil 列表时 `setFlags(0x2000,0x2000)` 上 `FLAG_SECURE`。全部由服务端实验配置驱动（5s 复查）。
5. 本工程读不到微信的直接原因：`adapters` 只有 QQ / X / 飞书 / WhatsApp，`maybeCapture()` 对表外包直接 return，微信不进解析分支 —— 与微信对抗无关。

## 改了什么

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/dianming/phoneapp/MyAccessibilityService.kt` | 由 `capture/` 迁到根包，`package com.dianming.phoneapp`，补 `capture.*` import（`ChatAppAdapter` / `ConversationSession` / `KeepAliveService` / `NewMessageGate` / `GuardedInputWriter` / 四个 Adapter / `WhatsAppRules` / `findTitleInActionBar` / `collectFeishuBubbleRects`） |
| `app/src/main/AndroidManifest.xml` | 服务声明改为 `.MyAccessibilityService` |
| `app/src/main/java/com/dianming/phoneapp/MainActivity.kt` | `a11yComponent` 改为 `com.dianming.phoneapp/com.dianming.phoneapp.MyAccessibilityService` |
| `docs/wechat-a11y-re.md` | 新增：完整逆向记录（样本信息、工具链限制、证据地址、匹配算法、四类打击、本工程原因、改动、后续） |
| `README.md` | `docs/` 列表补一行指向新文档 |
| `_reports/wechat-a11y-re_report.md` | 本报告 |

## 验收命令真实输出

```
$ sh gradlew :app:assembleDebug :app:testDebugUnitTest
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 2s
42 actionable tasks: 9 executed, 33 up-to-date

$ ~/sdk/androidsdk/build-tools/35.0.1/aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk | grep MyAccessibilityService
A: ...:name(0x01010003)="com.dianming.phoneapp.MyAccessibilityService" (Raw: "com.dianming.phoneapp.MyAccessibilityService")

$ rea doctor
healthy: false（node 24.13.1 不满足 >=24.22.1、ghidra/hopper 未配置）

$ rea analyze /Users/haosiyuan/Desktop/base.apk
error: Analysis failed / code: capability_unavailable / message: The selected analysis provider does not support this target.
```

## 自验缺口

- **没有真机**：`adb devices` 为空，微信聊天界面的 `uiautomator dump` 没做，`WeChatAdapter` 的 view id / 气泡几何规律无法实测，未写。
- **未验白名单生效**：组件名改为根包后，需要手机上先关再开无障碍服务让 `Settings.Secure` 刷新，本机无法验证微信是否真的把它当"点明"。
- **服务端配置未知**：`isWhiteUser`、`accInfoClearFactor` 等来自微信服务端，静态分析只能确认开关存在，无法确认当前账号的实际取值。
- **rea 只跑到能力探测**：`rea analyze` 不支持 APK 容器，APK 部分是用 `aapt2` + `dexdump` + `strings` 手工完成的，没有产出 rea 的结构化分析结果文件。
