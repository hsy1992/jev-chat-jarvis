# Xiaomi-OCR-0 Android 离线部署

- Android 源码在 `litert-samples/samples/litert_lm/receipt_to_json/android`。样例使用 LiteRT-LM 0.19.0 的 CPU 后端，从照片提取字段并返回 JSON。
- 模型在 `models/Xiaomi-OCR-0_int8.litertlm`，来自 LiteRT Community 的 `d4cad64` 版本。APK 和模型分别分发。

在 Android 项目目录运行 `./gradlew :app:assembleDebug :app:testDebugUnitTest`。构建需要 JDK 17 或更高版本、Android SDK 36。运行设备需支持 Android 12+ 和 arm64-v8a。

安装应用后可按[上游样例的 Side-load 步骤](litert-samples/samples/litert_lm/receipt_to_json/README.md#development)导入本地模型。打开 Receipt to JSON，加载模型后依次点 Example 和 Extract。样例启动引擎要求约 3.2 GB 可用内存。
