import { Module } from '@nestjs/common'
import { AuthModule } from '../auth/auth.module.js'
import { ProviderModule } from '../providers/provider.module.js'
import { QuotaModule } from '../quota/quota.module.js'
import { GenerationController } from './generation.controller.js'
import { GenerationService } from './generation.service.js'

@Module({
  imports: [AuthModule, ProviderModule, QuotaModule],
  controllers: [GenerationController],
  providers: [GenerationService],
})
export class GenerationModule {}
