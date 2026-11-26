import { Module } from '@nestjs/common'
import { AuthModule } from '../auth/auth.module.js'
import { QuotaController } from './quota.controller.js'
import { QuotaLedger } from './quota-ledger.js'

@Module({
  imports: [AuthModule],
  controllers: [QuotaController],
  providers: [QuotaLedger],
  exports: [QuotaLedger],
})
export class QuotaModule {}
