# 微信无障碍对抗 / 内容树不可读 —— 逆向分析记录

日期：2026-11-10。对象：`~/Desktop/base.apk`。样本属本地静态分析，未连接真机。

结论先行：

1. `base.apk` 不是 `com.dianming.phoneapp`，而是 **微信 8.0.80（`com.tencent.mm`）**。
2. 微信内置一套 `com.tencent.mm.accessibility.*` 框架，会枚举设备上已启用的无障碍服务，并对"可疑服务"做节点清空 / 假信息 / 吞点击 / `FLAG_SECURE` 四类处理。
3. 它把 **`com.dianming.phoneapp.MyAccessibilityService`** 写死成"白名单服务"（与 Google SelectToSpeak 并列）。匹配方式是**组件字符串 contains**，所以服务类必须落在根包，放在 `.capture` 子包里就会匹配失败。
4. 分析当时，本工程还没有微信适配器，`maybeCapture()` 会跳过微信。2026-11-11 已加入实验性节点适配器；能否读取仍取决于微信实际向本服务开放的节点，尚未真机验证。

---

## 1. 样本信息

| 项 | 值 |
| --- | --- |
| 文件 | `~/Desktop/base.apk`（286,319,384 B） |
| SHA-256 | `9d9b6c516e8e07e437c81485fde21fe13ce4f61b37d19e793fff40c71be39c0e` |
| 包名 / 版本 | `com.tencent.mm` / versionCode 3220 / versionName 8.0.80 |
| SDK | minSdk 24，targetSdk 34，compileSdk 36 |
| ABI | 仅 `arm64-v8a`（`libwechat*.so`、`libmmv8.so`、`libmmskia.so` …） |
| 结构 | 18 个 dex，2054 个 Activity，123 个 Service |
| 框架痕迹 | `assets/flutter_assets/*`、`libflutter.so`、`libapp.so`（内嵌 Flutter）、XWeb、`libwxa-runtime-binding.so`（小程序） |
| 无障碍声明 | **无**（只有 `com.tencent.mm.plugin.repairer.ui.demo.RepairerAccessibilityDemoUI` 这个调试 Activity） |

与本工程对照：

| 项 | base.apk（微信） | 本工程 `com.dianming.phoneapp` |
| --- | --- | --- |
| 包名 | `com.tencent.mm` | `com.dianming.phoneapp` |
| SDK | 24 / 34 | 30 / 35 |
| 依赖 | Flutter + XWeb + 18 dex | Kotlin + ML Kit OCR，单 dex |
| 无障碍 | 不声明服务，但运行时检测别人的服务 | 声明 `MyAccessibilityService`（读树 + 悬浮窗） |

两者没有任何代码继承关系，只是"被读取方 / 读取方"。

---

## 2. 工具链与限制

```
rea doctor                          → healthy:false（node 24.13.1 不符、ghidra/hopper 未配置）
rea analyze base.apk                → capability_unavailable / target_unsupported
                                       （analyze 只认 ghidra/hopper/ida/kuna 这些 native provider，不吃 APK 容器）
```

因此 APK 部分改用 rea 的 `decode-android-resources`（apktool）思路 + 手工工具：

```bash
AAPT2=~/sdk/androidsdk/build-tools/35.0.1/aapt2
$AAPT2 dump badging base.apk
$AAPT2 dump xmltree --file AndroidManifest.xml base.apk > manifest_tree.txt

unzip -o base.apk 'classes*.dex' -d /tmp/base_apk
for f in classes*.dex; do strings -a "$f"; done > all_strings.txt      # 2,385,883 行

dexdump -d classes7.dex  > dex7_dump.txt      # AccExptServiceKt
dexdump -d classes13.dex > dex13_dump.txt     # AccExptService / MMAccessibilityDelegateWrap / AccUtil / MapExpandKt
dexdump -d classes12.dex > dex12_dump.txt     # obfuscated kotlin.text helpers (xd6.o0)
```

反混淆要点：`xd6.o0` 是 Kotlin `StringsKt` 的 R8 映射类；`o0.G(a,b,ignoreCase)` 只是 `o0.E` 的默认参数转发，而 `o0.E` 实现是 `indexOf(other) >= 0`，即 **`contains`**。

---

## 3. 关键证据

### 3.1 白名单里躺着"点明"

`classes7.dex` → `Lcom/tencent/mm/accessibility/feature/AccExptServiceKt;.<clinit>`（字节码地址 `0x2307fc`）：

```
const-string v0, "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
const-string v1, "com.dianming.phoneapp.MyAccessibilityService"      // string@acd5
filled-new-array {v0, v1}
invoke-static → kotlin.collections.listOf
sput-object → DefaultWhiteServiceList
```

实验开关名：`clicfg_acc_white_service_list`（`AccExptServiceKt.ExptKey_WhiteServiceList`），服务端可追加，分号 `;` 分隔。

### 3.2 匹配算法：组件串 contains

`classes13.dex` → `Lcom/tencent/mm/accessibility/feature/AccExptService.checkHasServiceInList(String, List)`（`0x2b4bc8`）：

1. `AccessibilityManager.getEnabledAccessibilityServiceList(-1)` 取已启用服务；
2. 每条拼成 `ServiceInfo.packageName + "/" + ServiceInfo.name`，与名单项做 `o0.G`（= contains）比对；
3. 再反射读 `Settings.Secure.getString(cr, "enabled_accessibility_services")`，按 `:` 切分后与名单项做 `o0.E`（= contains）比对；
4. 任一命中即 `true`。

推论：`Settings.Secure` 里存的是 `com.dianming.phoneapp/com.dianming.phoneapp.capture.MyAccessibilityService` 时，**不包含**名单串 `com.dianming.phoneapp.MyAccessibilityService` → 判定失败。类必须位于根包。

### 3.3 门控：什么算"无障碍已启用"

`Lcom/tencent/mm/accessibility/uitl/AccUtil.isAccessibilityEnabled()`（`0x2b6cd4`，3 秒缓存）：

```
if (aa.b) return true;                    // 已有缓存/预置标志
if (isForceStrike()) return false;        // 服务端强制打击
if (isTouchExplorationEnable()) return true;   // 读屏类（TalkBack 等）
return hasAccServiceInWhiteList() || isWhiteUser();
```

`isWhiteUser` 来自服务端配置（`fa5/a.q()`），本地不可控。

### 3.4 四种打击手段

**① 清空节点**：`Lcom/tencent/mm/accessibility/core/MMAccessibilityDelegateWrap.onInitializeAccessibilityNodeInfo`（`0x2af57c`）

```
if (needClearNodeInfo()) { IAccessibilityService.onClearNodeInfo(); return; }
...
if (needUseFakeInfo())  { MapExpandKt.toFakeInfo(info, ctx); onFakeNodeInfo(); }
if (needClearNodeInfo()){ MapExpandKt.clearInfo(info); onClearNodeInfo(); }
```

`MapExpandKt.clearInfo`（`0x2ae284`）把 `viewIdResourceName / text / contentDescription / hintText` 全部置空 —— 这就是"树里有节点、但读不到任何文字"的形态。

**② 假信息**：`MapExpandKt.toFakeInfo`（`0x2ae558`）按宿主 Context 类名从 `infoMap` 缓存里取历史 `AccInfo`，把 `text / contentDescription / viewId` 覆盖成缓存值（缓存不足 5 条时直接 clearInfo）。读到的是"旧内容/别人的内容"。

**③ 吞点击**：`performAccessibilityAction`（`0x2af3d4`）

```
if (needInterceptAccAction()) {
    Log.i("MicroMsg.Acc.MMAccessibilityDelegateWrap", "performAccessibilityAction skip");
    onInterceptAccAction(); return true;      // 假装成功
}
```

**④ 截图封禁**：`AccExptService.checkEvilServiceStatus(Activity)`（`0x2b552c`）

```
if (hasEvilServiceInEvilList()) {
    Log.i("...", "has evil service in evil list, set FLAG_SECURE");
    IAccessibilityService.onScreenShotDisable();
    activity.getWindow().setFlags(0x2000, 0x2000);      // FLAG_SECURE
}
```

命中 `disableScreenshotUIList` / `disableScreenshot` 时同理。截图全黑，OCR 兜底一并失效。

### 3.5 配置与上报

`AccExptService` 字段（都由服务端下发，5 秒复查，`CheckIntervalTime = 5000`）：

`accInfoClearFactor`、`accInfoStrikeFactor`、`isForceStrike`、`evilPkgList`、`evilStackList`、`disableScreenshot`、`disableScreenshotUIList`、`isWhiteUser`、`whiteServiceList`、`touchExDelayConfirmMs`。

上报类：`AccServiceInfoReporter`、`AccEvilServiceReporter`、`AccFakeInfoInstantReporter`、`AccInjectionInterceptReporter`、`AccInterceptActionReporter`、`AccTouchTimeReporter` —— 即设备上启用的无障碍服务清单会被打包上报。

字符串池里还能看到它按组件名识别的其它对象，例如：

```
com.google.android.accessibility.selecttospeak.SelectToSpeakService
com.huawei.contentsensor/.accessibility.ContentAccessibilityService
com.hihonor.awareness/.server.pageanalysis.AccessibleInfoStuck
com.coloros.colordirectservice/.ColorTextAccessibilityService
com.kugou.android.ringtone/.ringcommon.util.permission.accessibilitysuper.service.AccessibilitySuperService
com.whatsbug.litiaotiao/.MyAccessibilityService
com.dianming.phoneapp.MyAccessibilityService
```

---

## 4. 本工程侧的历史缺口

分析当时 `app/src/main/java/com/dianming/phoneapp/MyAccessibilityService.kt` 的代码：

```kotlin
private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter(), WhatsAppAdapter())
    .associateBy { it.pkg }
...
val adapter = adapters[pkg] ?: run { ...; return }     // maybeCapture()
```

当时微信包名不在 `adapters` 中，`maybeCapture()` 直接返回；只有悬浮球菜单的"截屏识别一次"（OCR）能进。当前已加入 `WeChatAdapter`，此处代码仅作为历史定位证据。适配器无法让微信未开放的节点变得可读。

另外，服务配置本身没问题，无需改动：

```xml
android:accessibilityEventTypes="typeAllMask"
android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
android:canRetrieveWindowContent="true"
android:canTakeScreenshot="true"
```

---

## 5. 已落地的改动

为让微信的 contains 匹配成立，把服务类移到根包：

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/dianming/phoneapp/MyAccessibilityService.kt` | 由 `capture/` 迁到根包，`package com.dianming.phoneapp`，补齐 `capture.*` 的 import |
| `app/src/main/AndroidManifest.xml` | `android:name=".MyAccessibilityService"` |
| `app/src/main/java/com/dianming/phoneapp/MainActivity.kt` | `a11yComponent = "com.dianming.phoneapp/com.dianming.phoneapp.MyAccessibilityService"` |

验证：

```
gradlew :app:assembleDebug :app:testDebugUnitTest     → BUILD SUCCESSFUL
aapt2 dump xmltree ... app-debug.apk                  → com.dianming.phoneapp.MyAccessibilityService
                                                         （BIND_ACCESSIBILITY_SERVICE 保留）
```

注意：组件名变化后，手机上需**先关闭再重新启用**该无障碍服务，`Settings.Secure` 里的条目才会刷新成新串。

---

## 6. 当前实现与后续

1. **WeChatAdapter（实验性实现，待真机验证）**：`pkg = "com.tencent.mm"`，当前只读已观察到的 `id/bkl`，要求可见输入框和标题，并只接受明确左右对齐的正文。现有证据不足以覆盖长气泡、群聊和节点隐藏状态；后续需用自己的服务采集脱敏样本再调规则。
2. **FLAG_SECURE 兜底**：`ScreenCapture` 返回全黑位图时应识别为"截图被禁"，直接提示而不是等 OCR 空结果。
3. **打击状态可观测**：树非空但文字为空时可判定为 clearInfo 形态，走 OCR 而不是判定"无消息"。
