# ADR-0003: Use a permissioned, ephemeral perception gateway

## Status

Accepted

Date: 2026-11-25

Implemented in 言语 1.7.0.

## Context

言语需要的不只是用户当前输入，还包括有限的外部上下文：

- 用户允许的应用通知。
- 最近一次前台应用。
- 用于天气的粗粒度城市。
- 带真实来源的网页搜索结果。

Android 不允许普通应用读取其他应用的内部后台状态。通知、UsageStats 和屏幕
分别需要不同的系统授权。开放这些能力若缺少硬边界，会把陪伴应用变成持续监控工具。

## Decision

采用“观察网关”：

1. 每个来源单独授权，默认全部关闭。
2. 通知来源默认拒绝；只保存允许应用的少量结构化事件。
3. 普通通知正文不持久化；首版只接受包裹、课程、日历三类事件。
4. 前台应用只读取最近一次 Activity resumed 事件，2 分钟后失效。
5. 公网 IP 只用于取得粗粒度城市；原始 IP 不存储、不注入模型。
6. 天气使用 Open-Meteo；搜索使用用户配置的 SearXNG HTTPS 实例。
7. 搜索仅对明确时效意图触发，私人叙事不原样发送给搜索服务。
8. 搜索最多 5 条结果、12 秒超时；回答只附加模型实际引用的来源。
9. 所有观察写入 SQLCipher Room，并设置 24–72 小时 TTL。
10. 观察数据只进入当轮 `<live_context trust="untrusted-data">`，不自动晋升长期记忆。
11. 首版不实现屏幕截图、无障碍读取、全量通知保存或后台应用监控。

## Alternatives Considered

### Always-on screen capture

Rejected. 需要持续系统采集提示，容易捕获第三方隐私、密码页和验证码，电池与信任成本高。

### Accessibility service to read other apps

Rejected. 权限过宽，行为近似监控，开源分发和应用商店审核风险都不可接受。

### Store every notification for later retrieval

Rejected. 用户允许一个应用，不等于允许该应用全部通知正文进入云端模型。

### Infer reminders from user identity

Rejected. “大学生”不足以推断课程表和下课时间；必须来自明确日程、课程通知或用户设置。

### Automatic full-message web search

Rejected. 私人叙事、人名、学校和关系信息会泄露给搜索服务。只发送抽取后的短查询，私人查询不自动发送。

## Consequences

### Positive

- 应用可以看到足够有用的上下文，同时保留明确权限边界。
- 通知、应用、网络、搜索可以独立关闭和清理。
- 观察失败不会阻塞普通聊天。
- 来源可追溯，搜索引用不会凭空生成。
- 本地数据库和长期记忆保持分离。

### Negative

- 首次使用需要分别理解通知使用权、使用情况访问和搜索配置。
- 用户关闭原应用通知时，言语也拿不到事件。
- 来源包名在部分 Android 版本上可能只能显示包名。
- 粗粒度 IP 城市可能因 VPN、运营商出口或网络切换而错误。
- 首版不能读取屏幕，因此不能理解图表、图片或用户可见页面布局。

### Neutral

- SearXNG 需要用户自建或提供兼容 HTTPS 实例。
- 天气无需 Key，但依赖 Open-Meteo 可用性。
- 进一步扩展屏幕读取时，需要新增 MediaProjection 前台服务和单次采集确认。
