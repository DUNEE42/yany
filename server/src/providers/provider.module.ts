import { Module } from '@nestjs/common'
import type { AppConfig } from '../config.js'
import { APP_CONFIG } from '../config.js'
import { DeepSeekProvider } from './deepseek.provider.js'
import { MockProvider } from './mock.provider.js'
import { MODEL_PROVIDER } from './provider.js'

@Module({
  providers: [
    MockProvider,
    DeepSeekProvider,
    {
      provide: MODEL_PROVIDER,
      inject: [APP_CONFIG, MockProvider, DeepSeekProvider],
      useFactory: (config: AppConfig, mock: MockProvider, deepseek: DeepSeekProvider) =>
        config.provider === 'deepseek' ? deepseek : mock,
    },
  ],
  exports: [MODEL_PROVIDER],
})
export class ProviderModule {}
