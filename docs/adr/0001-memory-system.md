# ADR-0001: Use a local temporal memory system with controlled AI extraction

## Status

Accepted

Date: 2026-11-25

Implemented in 言语 1.6.0.

## Context

言语运行在 Android 手机上，当前使用 Room + SQLCipher 保存聊天，
通过用户自己的 DeepSeek Key 直接调用模型。

产品需要“记住发生过的事情”，但不能接受以下结果：

- AI 把一次情绪误解成永久人格。
- 长期摘要覆盖原始事实。
- 旧偏好无法被新偏好替代。
- 大量无关记忆挤占当前对话。
- 所有记忆同步上传给供应商。
- 后台记忆任务造成重复写入或阻塞聊天。

Mem0、Graphiti、LangMem 和 Letta 提供了有价值的模式，但直接嵌入其
Python、服务端、图数据库或完整 Agent 运行时不适用于当前 Kotlin 单机应用。

## Decision

采用本地轻量时间记忆系统：

1. L0 消息是唯一事实源，摘要和长期记忆都是派生数据。
2. 短期记忆由最近消息和滚动摘要组成，默认覆盖最近 5 天。
3. 长期记忆使用原子记录，不只用长摘要。
4. 每条记忆保存来源、时间有效期、来源类型、确认状态和失效关系。
5. AI 只生成候选；确定性校验通过后才能入库。
6. 用户画像由已确认记忆动态生成，不单独保存人格判断。
7. 记忆写入通过后台批处理任务完成，带检查点、幂等约束和事务。
8. v1 使用 SQLite 全文和元数据检索，暂不引入 embedding 和图数据库。
9. RAG 索引是派生数据，可以重建；不使用“只有索引、没有正文”的方案。

## Consequences

### Positive

- AI 记错时可以追溯到来源并纠正。
- 事实更新保留历史，可以处理时间和冲突。
- 聊天回复不被摘要生成阻塞。
- 移动端依赖和运维复杂度保持可控。
- 用户删除和隐私控制有明确语义。
- 未来升级到混合向量检索时不需要推翻数据模型。

### Negative

- 数据模型和任务状态比当前 `memories` 单表复杂。
- AI 提取仍需要额外 API 调用和费用。
- 时间校验、去重和冲突处理需要额外测试。
- v1 的关键词检索不能覆盖所有语义换词场景。
- 用户需要能查看并纠正记忆，增加 UI 工作。

### Neutral

- 不采用 Graphiti 的图数据库，但借鉴 Episode、Fact、来源和有效时间模型。
- 不采用 Mem0 的完整向量和图存储，但借鉴异步提取和多信号检索。
- 不采用 Letta 的自主改写人格能力，只保留受控的定期反思。

## Alternatives Considered

### 每轮发送完整历史

拒绝。数据量会无限增长，成本、延迟和相关性都会恶化。

### 只保存短期和长期摘要

拒绝。摘要是有损数据，无法保证准确回溯、纠错和时间查询。

### 只使用向量 RAG

拒绝。向量不保存事实状态和时间冲突，也不能单独解释记忆来源。

### 直接集成 Graphiti

拒绝作为 v1 运行时依赖。需要 Python 服务、图数据库、LLM 提取和额外运维，
对 Android 单机版过重。只借鉴 Episode、Entity、Fact 和 temporal validity。

### 直接集成 Mem0 或 LangMem

拒绝直接嵌入。其生态和存储假设与 Kotlin Android 本地数据库不一致。

### 允许 AI 全自主写、改、删和画像

拒绝。陪伴产品中错误人格标签和记忆污染的影响过大。

## References

- Internal: `docs/记忆系统最终设计-2026-11-25.md`
- https://github.com/getzep/graphiti
- https://github.com/mem0ai/mem0
- https://github.com/langchain-ai/langmem
- https://github.com/letta-ai/letta-code
- https://arxiv.org/abs/2501.13956

