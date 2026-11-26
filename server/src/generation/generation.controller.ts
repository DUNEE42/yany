import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  HttpCode,
  Inject,
  Param,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common'
import type { FastifyReply } from 'fastify'
import type { AuthenticatedRequest } from '../auth/dev-auth.guard.js'
import { DevAuthGuard } from '../auth/dev-auth.guard.js'
import type { GenerationRequest } from '../contracts.js'
import { GenerationService } from './generation.service.js'

@Controller('v1/generations')
@UseGuards(DevAuthGuard)
export class GenerationController {
  constructor(@Inject(GenerationService) private readonly generations: GenerationService) {}

  @Post()
  async create(
    @Req() request: AuthenticatedRequest,
    @Res() reply: FastifyReply,
    @Headers('idempotency-key') idempotencyKey: string | undefined,
    @Body() body: GenerationRequest,
  ): Promise<void> {
    if (!idempotencyKey?.trim()) {
      throw new BadRequestException('Idempotency-Key header is required')
    }

    const claim = this.generations.prepare(request.userId, idempotencyKey, body)
    reply.hijack()
    const raw = reply.raw
    raw.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    raw.flushHeaders?.()

    const writeEvent = (event: string, data: unknown): void => {
      if (raw.writableEnded || raw.destroyed) return
      raw.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    }

    raw.write(': connected\n\n')
    try {
      await this.generations.run(request.userId, idempotencyKey, claim, {
        onEvent: writeEvent,
        onDisconnect: (callback) => {
          raw.once('close', callback)
        },
      })
    } finally {
      if (!raw.writableEnded) raw.end()
    }
  }

  @Post(':generationId/cancel')
  @HttpCode(202)
  cancel(
    @Req() request: AuthenticatedRequest,
    @Param('generationId') generationId: string | undefined,
  ): { accepted: true } {
    if (!generationId?.trim()) {
      throw new BadRequestException('generationId is required')
    }
    this.generations.cancel(request.userId, generationId)
    return { accepted: true }
  }
}
