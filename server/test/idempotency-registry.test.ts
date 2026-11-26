import { describe, expect, it } from 'vitest'
import { hashGenerationRequest, IdempotencyRegistry } from '../src/generation/idempotency-registry.js'

describe('IdempotencyRegistry', () => {
  it('returns the original generation when a key is reused', () => {
    const registry = new IdempotencyRegistry()
    const hash = hashGenerationRequest({ message: 'hello' })
    expect(registry.claim('u1', 'key', hash, 'g1')).toBeNull()
    expect(registry.claim('u1', 'key', hash, 'g2')).toEqual({
      generationId: 'g1',
      requestHash: hash,
    })
  })

  it('scopes keys by user', () => {
    const registry = new IdempotencyRegistry()
    const hash = hashGenerationRequest({ message: 'hello' })
    expect(registry.claim('u1', 'key', hash, 'g1')).toBeNull()
    expect(registry.claim('u2', 'key', hash, 'g2')).toBeNull()
  })
})
