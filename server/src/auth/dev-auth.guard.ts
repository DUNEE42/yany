import {
  type CanActivate,
  type ExecutionContext,
  Inject,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common'
import type { FastifyRequest } from 'fastify'
import type { AppConfig } from '../config.js'
import { APP_CONFIG } from '../config.js'

export interface AuthenticatedRequest extends FastifyRequest {
  userId: string
}

@Injectable()
export class DevAuthGuard implements CanActivate {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>()
    const authorization = request.headers.authorization
    if (authorization !== `Bearer ${this.config.devBearerToken}`) {
      throw new UnauthorizedException('Invalid development bearer token')
    }
    request.userId = 'dev-user'
    return true
  }
}
