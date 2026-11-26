import { Controller, Get, Inject, Req, UseGuards } from '@nestjs/common'
import type { AuthenticatedRequest } from '../auth/dev-auth.guard.js'
import { DevAuthGuard } from '../auth/dev-auth.guard.js'
import { QuotaLedger } from './quota-ledger.js'

@Controller('v1/entitlements')
@UseGuards(DevAuthGuard)
export class QuotaController {
  constructor(@Inject(QuotaLedger) private readonly ledger: QuotaLedger) {}

  @Get()
  getEntitlements(@Req() request: AuthenticatedRequest) {
    return this.ledger.snapshot(request.userId)
  }
}
