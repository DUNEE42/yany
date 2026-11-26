import { Inject, Injectable } from '@nestjs/common'
import type { AppConfig } from '../config.js'
import { APP_CONFIG } from '../config.js'
import type { ModelProvider, ProviderChunk, ProviderInput } from './provider.js'

interface DeepSeekPayload {
  choices?: Array<{
    delta?: { content?: string }
    finish_reason?: string | null
  }>
  usage?: {
    prompt_tokens?: number
    completion_tokens?: number
  }
}

@Injectable()
export class DeepSeekProvider implements ModelProvider {
  readonly name = 'deepseek' as const

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  async *stream(input: ProviderInput, signal: AbortSignal): AsyncGenerator<ProviderChunk> {
    if (!this.config.deepseekApiKey) {
      throw new Error('DEEPSEEK_API_KEY is required when MODEL_PROVIDER=deepseek')
    }

    const timeoutSignal = AbortSignal.timeout(120_000)
    const combinedSignal = AbortSignal.any([signal, timeoutSignal])
    const messages = [
      {
        role: 'system',
        content: [
          '你是成年人情感陪伴产品中的 AI 伴侣，不是真实人类。',
          '回复自然、克制、尊重边界，不生成露骨色情内容，不鼓励依赖或封闭现实社交。',
          input.safetyInstruction ?? '',
        ]
          .filter(Boolean)
          .join('\n'),
      },
      ...input.context.map(({ role, content }) => ({ role, content })),
    ]

    const response = await fetch(`${this.config.deepseekBaseUrl.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${this.config.deepseekApiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: this.config.deepseekModel,
        messages,
        stream: true,
        stream_options: { include_usage: true },
        temperature: input.modelMode === 'enhanced' ? 1.0 : 0.8,
        max_tokens: 700,
      }),
      signal: combinedSignal,
    })

    if (!response.ok || !response.body) {
      const safeDetail = response.status === 401 ? 'invalid api key' : `status ${response.status}`
      throw new Error(`DeepSeek request failed: ${safeDetail}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let inputTokens = 0
    let outputTokens = 0

    try {
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (!data || data === '[DONE]') continue
          let payload: DeepSeekPayload
          try {
            payload = JSON.parse(data) as DeepSeekPayload
          } catch {
            continue
          }
          const text = payload.choices?.[0]?.delta?.content
          if (text) {
            outputTokens += Math.max(1, Math.ceil(text.length / 2.5))
            yield { text }
          }
          if (payload.usage) {
            inputTokens = payload.usage.prompt_tokens ?? inputTokens
            outputTokens = payload.usage.completion_tokens ?? outputTokens
          }
        }
      }
    } finally {
      reader.releaseLock()
    }

    yield {
      usage: {
        inputTokens: inputTokens || estimateInputTokens(input.context),
        outputTokens: Math.max(1, outputTokens),
      },
    }
  }
}

function estimateInputTokens(context: ProviderInput['context']): number {
  return Math.max(1, Math.ceil(context.map((message) => message.content).join('\n').length / 2.5))
}
