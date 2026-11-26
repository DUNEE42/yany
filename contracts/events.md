# Generation SSE Events

Each event uses the standard Server-Sent Events wire format:

```text
event: <name>
data: <json>

```

## Event types

### generation.started

```json
{"generationId":"g_123","assistantMessageId":"m_123","model":"deepseek"}
```

### message.delta

```json
{"generationId":"g_123","text":"我在听。"}
```

### safety.signal

```json
{"generationId":"g_123","level":"normal","action":"none"}
```

Levels: `normal`, `reminder`, `dependency_risk`, `crisis`.

### usage.updated

```json
{"generationId":"g_123","inputTokens":128,"outputTokens":64,"reservedUnits":10,"settledUnits":3}
```

### generation.completed

```json
{"generationId":"g_123","assistantMessageId":"m_123","finishReason":"stop"}
```

### generation.failed

```json
{"generationId":"g_123","code":"provider_timeout","message":"模型响应超时","retryable":true}
```

The client must not submit the same idempotency key with a different request body.

