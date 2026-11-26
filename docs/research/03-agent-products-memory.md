# DeepSeek Harness 与同类 Agent 记忆研究

日期：2026-11-25

## “DeepSeek Harness”指什么

本轮能确认的公开项目是 DeepSeek AI 的
[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)：
一个采用“everything is a plugin”架构的 Agent Harness，项目仍标注 developer preview，
并明确提示会有兼容性变化。

需要区分：

- **会话记录**：保存一次任务的调用与结果，用于恢复。
- **会话压缩**：上下文接近上限时生成摘要，让同一任务继续。
- **长期记忆**：跨会话保存用户偏好、项目事实或经验。

DSH 公开文档把长期记忆交给第三方 MCP 服务；这不是 DSH 自带的人格记忆层。

## DeepSeek Harness

公开来源：

- <https://github.com/deepseek-ai/deepseek-harness>
- <https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/mcp-memory.zh.md>
- <https://github.com/deepseek-ai/deepseek-harness/blob/master/.agents/notes/archived/architecture/2026-07-10-after-call-compaction-pressure-and-overflow-recovery.zh.md>

### 记忆策略

官方 MCP 记忆指南提供 Memorix、MCP Reference Memory、Engram 三份默认关闭的参考配置。
DSH 本身只负责：

- 启动/连接 MCP。
- 发现工具并暴露给模型。
- 生命周期与重连。

它不负责第三方服务器的数据库、模型、embedding、账号、迁移或遗忘策略。参考的
MCP Reference Memory 使用本地 JSONL 知识图谱，搜索是实体名称/类型/观察的子串匹配，
没有自动摘要、embedding、冲突消解或遗忘。

这说明 DSH 的关键取舍是：**记忆是可选插件能力，不是 Agent 内核默认拥有的黑盒**。

### 上下文压缩

DSH 的 compaction-basic 在成功调用后的下一步边界测量上下文压力：

- 默认压力阈值约为 0.8，保留尾部预算约为 0.16。
- 先尝试剪枝可移除的大工具结果，再决定是否需要摘要。
- 摘要范围与 token 计量由统一 meter 负责。
- 溢出恢复有明确上限，默认最多重试一次。
- 只有持久可见表层真的发生变化，才允许重试。
- 摘要用于任务继续，不是用户画像。

对言语的启发：

- 压缩是上下文预算系统，不能替代长期事实。
- 摘要必须能证明处理到了哪个消息水位。
- 单次整理失败不能破坏原始聊天。
- 普通短期事件不应借“压缩”升级为长期记忆。

### 言语应取/应舍

应取：

- 记忆整理异步化。
- 明确的水位线、重试和失败隔离。
- 摘要与长期事实分离。
- 没有高价值内容时允许 no-op。

应舍：

- 不能把第三方 MCP 当作无条件可信的用户事实源。
- 不能把大段工具结果或外部文档直接写入用户画像。
- 不能为了“记得更多”而跳过本地来源校验。

## Cline Memory Bank

公开来源：

- <https://github.com/cline/cline/blob/main/docs/best-practices/memory-bank.mdx>
- <https://docs.cline.bot/best-practices/memory-bank>

Cline 的 Memory Bank 是一套结构化 Markdown 文档方法，而不是向量数据库。典型文件包括：

```text
memory-bank/
├── projectbrief.md
├── productContext.md
├── activeContext.md
├── systemPatterns.md
├── techContext.md
└── progress.md
```

它把“长期项目背景”和“当前活跃上下文”分开，并依靠用户触发的“update memory bank”
在上下文重置前保存进展。其优点是透明、可编辑、可版本控制；缺点是需要人工维护，
而且没有自动的事实冲突、来源角色和情绪生命周期。

对言语的映射：

- 稳定偏好/边界类似 project brief。
- 当前 3–5 天经历类似 active context。
- 用户画像只是稳定条目的视图，不单独成为长篇人格文档。
- 不照搬“每次读全部 Markdown”，而是只注入经过筛选的少量条目。

## Aider Repo Map

公开来源：

- <https://github.com/Aider-AI/aider/blob/main/aider/website/docs/repomap.md>
- <https://github.com/Aider-AI/aider/blob/main/aider/repomap.py>

Aider 不保存用户长期记忆，而是为每次请求生成一个紧凑的仓库地图：

- 只保留重要类、函数、类型和调用签名。
- 用依赖图排序，把当前请求最相关的部分放进预算。
- 默认地图 token 预算约 1000，按对话状态动态扩展。
- 需要细节时再让用户加入具体文件。

对言语的启发：

- 记忆检索也应该先返回“索引级片段”，而不是完整日记。
- 相关性排序、预算和按需展开，比单纯增加注入条数更重要。
- 当前问题与记忆冲突时，当前对话优先。

## 对言语的最终判断

三类产品共同收敛出同一个结构：

```text
不可变原始记录
→ 短期压缩/活跃上下文
→ 小型稳定索引或候选
→ 按需读取详细来源
```

言语不应追求一个“AI 自己随便写、每轮自动塞满上下文”的记忆库。更合适的路线是：

1. 聊天原文作为 L0 证据。
2. 3–5 天结构化短期摘要。
3. 默认 7–30 天可过期的近期事件。
4. 用户明确或本地策略确认的稳定偏好、边界、事实和计划。
5. 画像由稳定条目动态计算，不单独持久化人格判断。
6. 达到规模和检索门槛后再引入 embedding/RAG。

这条路线与当前 1.6.1 的实现一致，也保留了以后切换模型、接入收费 API 或开源自托管的空间。
