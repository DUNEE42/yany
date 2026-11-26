import { describe, expect, it } from 'vitest'
import { loadConfig } from '../src/config.js'
import { QuotaLedger, unitsFromUsage } from '../src/quota/quota-ledger.js'

describe('QuotaLedger', () => {
  it('reserves, settles and refunds the unused portion', () => {
    const ledger = new QuotaLedger(loadConfig({ INITIAL_QUOTA_UNITS: '20' }))
    expect(ledger.snapshot('u1').availableUnits).toBe(20)

    const reservation = ledger.reserve('u1', 10, 'g1')
    expect(ledger.snapshot('u1').availableUnits).toBe(10)

    const settled = ledger.settle(reservation.id, { inputTokens: 100, outputTokens: 60 })
    expect(settled).toBe(2)
    expect(ledger.snapshot('u1').availableUnits).toBe(18)
  })

  it('releases an unused reservation exactly once', () => {
    const ledger = new QuotaLedger(loadConfig({ INITIAL_QUOTA_UNITS: '20' }))
    const reservation = ledger.reserve('u1', 7, 'g1')
    ledger.release(reservation.id)
    expect(ledger.snapshot('u1').availableUnits).toBe(20)
    expect(() => ledger.release(reservation.id)).toThrow(/already released/)
  })

  it('converts token usage into integer units', () => {
    expect(unitsFromUsage({ inputTokens: 0, outputTokens: 1 })).toBe(1)
    expect(unitsFromUsage({ inputTokens: 80, outputTokens: 0 })).toBe(1)
    expect(unitsFromUsage({ inputTokens: 81, outputTokens: 0 })).toBe(2)
  })
})
