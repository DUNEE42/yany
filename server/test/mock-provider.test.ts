import { describe, expect, it } from 'vitest'
import type { TokenUsage } from '../src/contracts.js'
import { MockProvider } from '../src/providers/mock.provider.js'

describe('MockProvider', () => {
  it('streams text and usage', async () => {
    const provider = new MockProvider()
    const chunks: string[] = []
    let usage: TokenUsage | undefined
    for await (const chunk of provider.stream(
      {
        companion: {
          id: 'c1',
          name: '小屿',
          persona: '温柔',
          speakingStyle: '自然',
          boundaries: ['不生成露骨内容'],
        },
        context: [{ id: 'm1', role: 'user', content: '今天有点累', sequence: 1 }],
        modelMode: 'standard',
      },
      new AbortController().signal,
    )) {
      if (chunk.text) chunks.push(chunk.text)
      if (chunk.usage) usage = chunk.usage
    }
    expect(chunks.join('')).toContain('今天有点累')
    expect(usage?.outputTokens).toBeGreaterThan(0)
  })
})
