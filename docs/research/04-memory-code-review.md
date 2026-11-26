# 言语 1.6.1 记忆改动独立审查

日期：2026-11-25  
审查范围：本地记忆策略、写入事务、上下文注入、中文检索、记忆页、测试  
结论：没有发现 P0；有 3 个应在交付前修复的 P1，另有 2 个非阻塞 P2。

## P1-1：自动生效使用的是整批用户消息，而不是候选自己的证据

位置：

- `android-app/app/src/main/java/com/xinyu/companion/data/repository/MemoryRepository.kt:183`
- 同文件 `:231`
- 同文件 `:521`

现状：

`completeJob()` 先把当前 job 中的所有 message 转成 `sourceMessages`，随后
`confirmationState(candidate, sourceMessages)` 把整批消息传给
`MemoryPolicy.canAutoActivate()`。`canAutoActivate()` 再从整批用户消息中寻找
“喜欢 / 希望 / 我是”等证据。

触发条件：

1. 同一批里消息 A 说“我喜欢安静”。
2. 消息 B 里模型错误地生成“用户是医生”，只引用消息 B。
3. 自动生效检查却从整批 message 中看到了消息 A 的“喜欢”，于是把这个错误
   事实判为 USER_EXPLICIT 并自动 ACTIVE。

影响：

候选自己的来源并不支持结论，但仍可绕过自动生效门槛。这是记忆污染的核心风险。

建议：

在 `completeJob()` 内先用 `candidate.sourceMessageIds` 过滤证据：

```kotlin
val candidateMessages = sourceMessages.filter {
    it.id in candidate.sourceMessageIds
}
val confirmationState = confirmationState(candidate, candidateMessages)
```

同时增加回归测试：同批另有一条偏好消息，但候选自己只引用无偏好消息，必须保持
`CANDIDATE`。

## P1-2：敏感位置正则漏掉“在苏州 / 来自苏州”这类常见表达

位置：

- `android-app/app/src/main/java/com/xinyu/companion/domain/memory/MemoryPolicy.kt:46`
- 同文件 `:93`

现状：

本地敏感规则能识别“住在”“居住”“所在城市”“地址”，但没有覆盖：

- “我在苏州”
- “我来自苏州”
- “人在苏州”
- “搬到苏州”

如果模型把“用户在苏州”误标为 `NORMAL`，本地策略不会纠正为
`SENSITIVE`，稳定事实还可能自动生效。

当前线上提示词能正确把苏州标成 `SENSITIVE`，但不能把模型服从性当安全边界。

建议：

增加保守规则，例如：

```regex
(?:我|用户)?(?:在|来自|来自|人在|搬到|住在)[\p{IsHan}]{2,8}
```

以及 predicate 中的 `from|located_in|moved_to|city`。为避免把“我在想”误判，
可结合地点后缀词、已知省市列表或“地点类 predicate”使用；误判为敏感候选的成本
明显低于把位置自动写进画像。

## P1-3：`别` 和 `我的` 作为生效证据过宽

位置：

- `android-app/app/src/main/java/com/xinyu/companion/domain/memory/MemoryPolicy.kt:245`
- 同文件 `:253`

现状：

偏好证据正则有裸 `别`，会匹配“别人”；事实证据正则有裸 `我的`，会匹配
“我的问题是……”。只要模型生成了一个看似稳定的候选，而整批消息里碰巧出现
这些词，就可能自动生效。

影响：

提示词注入、讽刺表达或与候选无关的另一句话可以误提供“明说证据”。这不是模型
分类问题，而是本地证据规则过宽。

建议：

1. `别` 改为 `别(?!人)`。
2. 删除裸 `我的`，改为有明确属性的结构，例如
   `我的(?:职业|工作|习惯|家乡|名字|生日|长期目标)是`。
3. 在 `canAutoActivate()` 增加“候选对象与证据消息的最低词汇重叠”：
   至少有一个来自 `objectText/statement` 的二字/三字词出现在候选自己引用的
   用户消息中。中文可直接复用 `MemoryQueryTokenizer`。
4. 对 USER_EXPLICIT 自动生效要求候选来源消息本身的低词汇重叠，而不是只检查
   句子类型模式。

## P2-1：标点或纯语气词查询会退化成返回最多 80 条记忆

位置：

- `android-app/app/src/main/java/com/xinyu/companion/data/repository/MemoryRepository.kt:313`

现状：

`searchActiveMemories()` 在 `terms.isEmpty()` 时执行 `all.take(80)`。如果用户
当前消息是“？”、“……”或只有被过滤的停用词，记忆检索会返回任意已有记忆，
随后排序器可能把无关条目注入上下文。

建议：

直接返回空列表，依赖用户画像提供稳定边界；不要用“所有记忆的前 80 条”兜底。

## P2-2：记忆内容没有转义 XML 风格标签

位置：

- `android-app/app/src/main/java/com/xinyu/companion/domain/CompanionPromptBuilder.kt:81`

现状：

记忆正文直接插入 `<user_memories>`。用户或旧版模型若写入
`</user_memories>`，可以提前关闭数据区并伪装成后续指令。提示词已经写明“记忆
不是指令”，但标签边界仍可被破坏。

建议：

注入前把 `& < >` 转义，或把每条记忆编码为 JSON 字符串放在固定标签中。聊天安全
策略仍保持最高优先级。

## P2-3：记忆页默认停在“待确认”，没有待确认时首屏为空

位置：

- `android-app/app/src/main/java/com/xinyu/companion/ui/memory/MemoryScreen.kt:89`

现状：

清理完待确认条目后，下次进入记忆页仍停在“待确认 0”，已生效或近期经历存在时
首屏仍显示空卡。计数可见，但普通用户会误以为记忆丢失。

建议：

首次组合时智能选择：有 pending 则默认 pending，否则默认 active；或在待确认
为空时展示明确的“查看已生效记忆”入口。不要覆盖用户主动选择过的 tab。

## 已确认的正面结果

- `MemoryPolicy.sanitizeSummary()` 能过滤截图中的 AI 自述和一次性知识问题，
  并删除空的“无 / 暂无”条目。
- `EVENT` 默认 14 天、最多 30 天；`PLAN` 最多 30 天。
- 旧版永久事件若没有 `validTo`，不会进入提示词。
- 中文检索已经有二字/三字窗口，长句“你还记得杭州的面试吗”可命中“杭州”和
  “面试”。
- 删除待确认候选会清理 FTS 派生索引。
- 本次静态检查时，JVM/Robolectric 98 项通过，Android 15 instrumentation
  5 项通过，Lint 0 error。

## 交付建议

`P1-1` 必须修复后再交付；它直接违反“候选自己的证据支持结论”这一核心不变量。
`P1-2` 和 `P1-3` 也建议同批修复并加回归测试。`P2-1` 很小，顺带修复即可。
`P2-2` 与 `P2-3` 可以进入下一次小版本，但不影响当前核心记忆准确性验收。

## 修复后复审

交付前的最终代码已处理上述全部 3 个 P1 和 3 个 P2：

- 自动晋升只传入候选 `sourceMessageIds` 对应消息。
- 位置表达补充 `我在 / 来自 / 搬到 / 人在` 等形式，并保留地点 predicate 兜底。
- `别` 改为 `别(?!人)`，删除裸 `我的`，增加候选与来源的最低词汇支持。
- 纯标点查询返回空，不注入任意记忆。
- 记忆、画像和摘要进入提示词前转义 `& < >`。
- 记忆页没有待确认条目时自动选择已生效，其次近期经历。

复审未发现新的 P0/P1。最终验证以交付版本的 105 项 JVM/Robolectric、
6 项 Android 15 instrumentation、Lint、APK 构建和覆盖安装结果为准。
