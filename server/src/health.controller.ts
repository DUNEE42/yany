import { Controller, Get, Inject } from '@nestjs/common'
import type { AppConfig } from './config.js'
import { APP_CONFIG } from './config.js'

@Controller('health')
export class HealthController {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  @Get()
  health() {
    return { status: 'ok', provider: this.config.provider }
  }
}
