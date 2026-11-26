import { HttpException, HttpStatus, Inject, Injectable } from '@nestjs/common'
import type { AppConfig } from '../config.js'
import { APP_CONFIG } from '../config.js'
import type { TokenUsage } from '../contracts.js'

type ReservationStatus = 'reserved' | 'settled' | 'released'

interface Reservation {
  id: string
  userId: string
  reference: string
  reservedUnits: number
  status: ReservationStatus
  settledUnits?: number
}

export interface QuotaSnapshot {
  plan: string
  availableUnits: number
  resetAt: string
}

export function unitsFromUsage(usage: TokenUsage): number {
  const total = Math.max(0, usage.inputTokens) + Math.max(0, usage.outputTokens)
  return Math.max(1, Math.ceil(total / 80))
}

@Injectable()
export class QuotaLedger {
  private readonly balances = new Map<string, number>()
  private readonly reservations = new Map<string, Reservation>()
  private sequence = 0

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  snapshot(userId: string): QuotaSnapshot {
    return {
      plan: 'beta',
      availableUnits: this.balanceFor(userId),
      resetAt: this.nextResetAt(),
    }
  }

  reserve(userId: string, units: number, reference: string): Reservation {
    if (units <= 0 || !Number.isInteger(units)) {
      throw new HttpException('Reserved units must be a positive integer', HttpStatus.BAD_REQUEST)
    }
    const available = this.balanceFor(userId)
    if (available < units) {
      throw new HttpException('Insufficient usage units', HttpStatus.PAYMENT_REQUIRED)
    }
    this.balances.set(userId, available - units)
    const reservation: Reservation = {
      id: `res_${++this.sequence}`,
      userId,
      reference,
      reservedUnits: units,
      status: 'reserved',
    }
    this.reservations.set(reservation.id, reservation)
    return { ...reservation }
  }

  settle(reservationId: string, usage: TokenUsage): number {
    const reservation = this.requireReserved(reservationId)
    const billedUnits = Math.min(reservation.reservedUnits, unitsFromUsage(usage))
    this.refund(reservation, reservation.reservedUnits - billedUnits)
    reservation.status = 'settled'
    reservation.settledUnits = billedUnits
    return billedUnits
  }

  release(reservationId: string): void {
    const reservation = this.requireReserved(reservationId)
    this.refund(reservation, reservation.reservedUnits)
    reservation.status = 'released'
  }

  private requireReserved(reservationId: string): Reservation {
    const reservation = this.reservations.get(reservationId)
    if (!reservation) throw new Error(`Unknown reservation ${reservationId}`)
    if (reservation.status !== 'reserved') {
      throw new Error(`Reservation ${reservationId} is already ${reservation.status}`)
    }
    return reservation
  }

  private refund(reservation: Reservation, units: number): void {
    if (units > 0) {
      this.balances.set(reservation.userId, this.balanceFor(reservation.userId) + units)
    }
  }

  private balanceFor(userId: string): number {
    return this.balances.get(userId) ?? this.config.initialQuotaUnits
  }

  private nextResetAt(): string {
    const reset = new Date()
    reset.setUTCHours(24, 0, 0, 0)
    return reset.toISOString()
  }
}
