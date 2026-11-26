import { Module } from '@nestjs/common'
import { ConfigModule } from './config.module.js'
import { GenerationModule } from './generation/generation.module.js'
import { HealthController } from './health.controller.js'
import { QuotaModule } from './quota/quota.module.js'

@Module({
  imports: [ConfigModule, QuotaModule, GenerationModule],
  controllers: [HealthController],
})
export class AppModule {}
