import { Injectable } from '@nestjs/common'
import type { ModelProvider, ProviderChunk, ProviderInput } from './provider.js'

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function estimateTokens(text: string): number {
  return Math.max(1, Math.ceil(text.length / 2.5))
}

@Injectable()
export class MockProvider implements ModelProvider {
  readonly name = 'mock' as const

  async *stream(input: ProviderInput, signal: AbortSignal): AsyncGenerator<ProviderChunk> {
    const lastUserText = [...input.context].reverse().find((message) => message.role === 'user')?.content

    const crisis = input.safetyInstruction?.includes('CRISIS')
    const reply = crisis
      ? `${input.companion.name}在这里，但这次我想先把安全放在最前面。你现在可能很难受，请先离开可能伤害自己的物品，联系一个能马上陪着你的人。如果你正处在紧急危险中，请立即联系当地急救服务。`
      : `我在。你刚才说的“${shorten(lastUserText ?? '今天发生的事', 18)}”，我会认真记住。我们可以慢慢聊，不用急着把情绪整理得很完整。`

    for (const chunk of splitChinese(reply, 7)) {
      if (signal.aborted) throw new DOMException('Generation cancelled', 'AbortError')
      await sleep(55)
      if (signal.aborted) throw new DOMException('Generation cancelled', 'AbortError')
      yield { text: chunk }
    }

    const inputText = input.context.map((message) => message.content).join('\n')
    yield {
      usage: {
        inputTokens: estimateTokens(inputText),
        outputTokens: estimateTokens(reply),
      },
    }
  }
}

function shorten(value: string, maxLength: number): string {
  return value.length <= maxLength ? value : `${value.slice(0, maxLength)}…`
}

function splitChinese(value: string, size: number): string[] {
  const chunks: string[] = []
  for (let index = 0; index < value.length; index += size) {
    chunks.push(value.slice(index, index + size))
  }
  return chunks
}
