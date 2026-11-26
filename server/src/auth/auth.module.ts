import { Module } from '@nestjs/common'
import { DevAuthGuard } from './dev-auth.guard.js'

@Module({
  providers: [DevAuthGuard],
  exports: [DevAuthGuard],
})
export class AuthModule {}
