import { createHash } from 'node:crypto'

export type IdempotencyState = 'processing' | 'completed' | 'failed' | 'cancelled'

export interface IdempotencyClaim {
  generationId: string
  requestHash: string
}

interface IdempotencyRecord extends IdempotencyClaim {
  userId: string
  key: string
  state: IdempotencyState
}

export function hashGenerationRequest(value: unknown): string {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex')
}

export class IdempotencyRegistry {
  private readonly records = new Map<string, IdempotencyRecord>()

  claim(userId: string, key: string, requestHash: string, generationId: string): IdempotencyClaim | null {
    const compoundKey = `${userId}:${key}`
    const existing = this.records.get(compoundKey)
    if (existing) return { generationId: existing.generationId, requestHash: existing.requestHash }
    this.records.set(compoundKey, { userId, key, requestHash, generationId, state: 'processing' })
    return null
  }

  update(userId: string, key: string, state: IdempotencyState): void {
    const record = this.records.get(`${userId}:${key}`)
    if (record) record.state = state
  }

  remove(userId: string, key: string): void {
    this.records.delete(`${userId}:${key}`)
  }

  get(userId: string, key: string): Readonly<IdempotencyRecord> | undefined {
    return this.records.get(`${userId}:${key}`)
  }
}
