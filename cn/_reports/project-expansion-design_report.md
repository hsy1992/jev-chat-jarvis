# 项目扩展设计交付报告

日期：2026-11-11。

## 做法与依据

根据当前 Android 工作区源码，将上一轮优化分析展开为可实施设计。重点核对 `MyAccessibilityService` 的 OCR 与分析流程、`JevClient` 的生成排序链路、消息模型、联系人匹配、上下文预算、历史追加、Manifest 和现有测试。

确认的设计输入包括：整屏 OCR 全部标为对方；导入分享尚无 Manifest 入口；分析要求判断密钥；多候选生成后排序失败影响候选返回；常驻资料不受现有 1500 字预算约束；历史无重叠时按旧屏跳过。后者的真实漏记场景仍需 fixture 和真机复现，报告没有把静态风险写成已复现故障。

采用已读取的 Android 架构技能，保持 Views 和 JSON 的项目约束，规划通过纯 Kotlin 契约、分阶段状态、Repository 和 Hilt 逐步拆分职责。图片选择、分享 URI 与 Views 架构的外部依据已链接到技术方案中的 Android 官方文档。

## 文件变化

| 文件 | 交付内容 |
|---|---|
| [docs/README.md](../docs/README.md) | 新建文档导航，区分未来设计与历史研究 |
| [product-design.md](../docs/next/product-design.md) | 产品目标、现状证据、页面与流程、失败恢复、记忆和扩展范围 |
| [architecture.md](../docs/next/architecture.md) | 组件职责、数据契约、修订隔离、OCR 管线、网络降级、迁移和设计决定 |
| [delivery-plan.md](../docs/next/delivery-plan.md) | 5 个阶段、14 个任务、16 项验收场景、指标、验证和回滚 |
| [README.md](../README.md) | 在常见问题前增加开发规划入口，标注尚未实施 |
| 本报告 | 记录交付范围、实际验证及缺口 |

保留工作区原有代码和文档修改。本轮未修改 Kotlin、Gradle、Manifest、历史研究或 v1.3 方案，没有执行提交、推送、构建、安装或手机操作。

## 实际验证

对四份新增规划文档运行 Python 检查，验证 UTF-8 读取、本地链接目标存在、代码围栏成对和任务编号连续。退出码为 0，原始输出如下。

```text
docs/README.md: 23 lines, UTF-8 OK
docs/next/architecture.md: 221 lines, UTF-8 OK
docs/next/delivery-plan.md: 238 lines, UTF-8 OK
docs/next/product-design.md: 160 lines, UTF-8 OK
Local links checked: 27
Task IDs: 14 unique, T01-T14
PASS: local links, code fences, task IDs
```

执行 `git diff --check -- README.md docs/README.md docs/next`，未输出空白错误。由于 Git diff 默认不包含未跟踪文件，新增文件另由 Python 检查；链接检查不依赖文件是否被 Git 跟踪。

## 自验缺口

- 本轮只交付设计，不代表任务已实施或验收通过。
- 未运行 Gradle、模型调用、ADB 或真机性能测试；文档中的时延和限额均明确标为建议值。
- Mermaid 已检查围栏与文本结构，未进行图形渲染验证。
- 检查了本地链接存在性；外部官方文档已查询，未做所有远程链接的批量可达性测试。
- 依赖库版本、OCR 阈值、迁移兼容、应用自有发布身份、备份加密方案仍需相应实施任务验证。

## 建议下一步

从 T01 基线与 T02 契约开始，先交付 T03 的候选降级和 T04/T05 的导入校正闭环。首轮演示应包含人为制造排序失败后，候选仍然可以编辑与复制的结果。
