# ADR-0005：受控、可中断的 Tool-Use Agent Loop

状态：Accepted  
日期：2026-11-26  

## 背景

1.8.0 之前，环境感知（天气、搜索）、记忆检索和本地提醒通过固定关键词规则或独立入口触发，模型无法在对话过程中按需补充检索或组合多步信息。
如果直接放开无界 Agent Loop，又会带来死循环、高费用、静默外发隐私搜索词、绕过提示词注入防线和危机场景失控的风险。

## 决策

在保持单轮流式体验与领域边界不变的前提下，升级 `ChatEngine` 与 `DeepSeekApi`：

1. **受控步数与即时中断**：单轮最多执行 4 步工具调用，每步最多 3 个工具；超过步数上限后发起一次无工具（`tools = emptyList()`）的收尾生成。所有网络流、工具执行与确认等待均绑定当前运行协程，用户点击“停止”立即取消并落盘 `INTERRUPTED`。
2. **危机模式禁用工具**：`SafetyPolicy` 判定为 `crisis` 时不下发任何工具定义，确保现实安全支持不被打断。
3. **四项内置受控工具**：
   - `search_memories`：复用 `MemoryPolicy.isEligibleForPrompt`，绝不暴露未确认敏感记忆或历史污染数据。
   - `get_weather`：复用 `PerceptionSettingsStore`、`CoarseNetworkLocationProvider`（仅开启时使用，不暴露原始 IP）与 `OpenMeteoWeatherProvider`。
   - `web_search`：严格保留联网前用户确认。发送前已确认搜索词仅限当轮单次使用；模型中途主动发起搜索时，先经硬敏感词拦截，再挂起确认门复用现有底部确认面板由用户审核/编辑或拒绝。
   - `local_reminder`：遵循 ADR-0004，支持 `list / create / cancel`，创建必须提供明确 ISO-8601 未来时间并通过系统通知权限与 `ReminderPolicy` 校验。
4. **防提示注入统一清洗**：所有工具输出经 `ToolResultSanitizer` 去除控制字符、转义 XML 标签、通过 `UntrustedTextPolicy` 过滤指令注入文本并包裹在 `<tool_output trust="untrusted-data">` 中。
5. **单轮向后兼容**：当 `tools` 为空或 Provider 不支持工具时，请求格式与执行路径与 1.8.0 完全一致。
