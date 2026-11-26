# Companion Server MVP

NestJS + Fastify 实现的初版生成后端。

## 能力

- `GET /health`
- `GET /v1/entitlements`
- `POST /v1/generations`，SSE 流式回复
- `POST /v1/generations/:generationId/cancel`
- Bearer `dev-token`
- 幂等键和内存额度预留/结算
- 默认 Mock Provider
- 可选 DeepSeek OpenAI-compatible Provider

## 运行

```powershell
pnpm install
pnpm dev
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:3000/health
```

使用 DeepSeek：

```powershell
$env:MODEL_PROVIDER="deepseek"
$env:DEEPSEEK_API_KEY="your-key"
$env:DEEPSEEK_MODEL="deepseek-flash"
pnpm dev
```

## SSE 请求

```powershell
$headers = @{
  Authorization = "Bearer dev-token"
  "Idempotency-Key" = [guid]::NewGuid().ToString()
}
$body = @{
  conversationId = "conversation-1"
  clientMessageId = "user-1"
  assistantMessageId = "assistant-1"
  companion = @{
    id = "companion-1"
    name = "小屿"
    persona = "温柔、稳定、善于倾听"
    speakingStyle = "简短自然，像熟悉的聊天对象"
    boundaries = @("不生成露骨内容", "不声称自己是真人")
  }
  modelMode = "standard"
  context = @(
    @{ id = "user-1"; role = "user"; content = "今天有点累"; sequence = 1 }
  )
} | ConvertTo-Json -Depth 8
curl.exe -N -X POST http://localhost:3000/v1/generations -H "Authorization: Bearer dev-token" -H "Idempotency-Key: $($headers['Idempotency-Key'])" -H "Content-Type: application/json" --data-binary $body
```

## 初版边界

- 额度账本和幂等记录当前保存在进程内存，重启后清空。
- 尚未接入 PostgreSQL、Redis、正式账号、订阅和年龄核验。
- 普通聊天正文不写入日志，也不持久化。
- DeepSeek 供应商是否获准用于最终产品场景仍必须按正式条款核验。

