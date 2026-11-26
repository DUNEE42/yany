import { randomUUID } from 'node:crypto'
import { BadRequestException, ConflictException, Inject, Injectable, NotFoundException } from '@nestjs/common'
import type { GenerationRequest, SafetyLevel, SafetySignal, TokenUsage } from '../contracts.js'
import type { ModelProvider, ProviderChunk } from '../providers/provider.js'
import { MODEL_PROVIDER } from '../providers/provider.js'
import { QuotaLedger } from '../quota/quota-ledger.js'
import { hashGenerationRequest, IdempotencyRegistry } from './idempotency-registry.js'

export interface GenerationClaim {
  generationId: string
  assistantMessageId: string
  request: GenerationRequest
  reservationId: string
  reservedUnits: number
}

export interface GenerationContext {
  onEvent: (event: string, data: unknown) => void
  onDisconnect: (callback: () => void) => void
}

interface ActiveGeneration {
  userId: string
  controller: AbortController
}

@Injectable()
export class GenerationService {
  private readonly idempotency = new IdempotencyRegistry()
  private readonly active = new Map<string, ActiveGeneration>()
  private readonly reservedUnits = 10

  constructor(
    @Inject(MODEL_PROVIDER) private readonly provider: ModelProvider,
    @Inject(QuotaLedger) private readonly quota: QuotaLedger,
  ) {}

  prepare(userId: string, idempotencyKey: string, request: GenerationRequest): GenerationClaim {
    this.validate(request)
    const generationId = `g_${randomUUID()}`
    const requestHash = hashGenerationRequest(request)
    const existing = this.idempotency.claim(userId, idempotencyKey, requestHash, generationId)
    if (existing) {
      throw new ConflictException({
        code: 'idempotency_replay',
        generationId: existing.generationId,
        message: 'This idempotency key has already been used',
      })
    }

    let reservation: ReturnType<QuotaLedger['reserve']>
    try {
      reservation = this.quota.reserve(userId, this.reservedUnits, generationId)
    } catch (error) {
      this.idempotency.remove(userId, idempotencyKey)
      throw error
    }

    return {
      generationId,
      assistantMessageId: request.assistantMessageId,
      request,
      reservationId: reservation.id,
      reservedUnits: reservation.reservedUnits,
    }
  }

  async run(
    userId: string,
    idempotencyKey: string,
    claim: GenerationClaim,
    context: GenerationContext,
  ): Promise<void> {
    const controller = new AbortController()
    this.active.set(claim.generationId, { userId, controller })
    context.onDisconnect(() => controller.abort())

    let output = ''
    let usage: TokenUsage | undefined
    let settledUnits = 0

    context.onEvent('generation.started', {
      generationId: claim.generationId,
      assistantMessageId: claim.assistantMessageId,
      model: this.provider.name,
    })

    const safety = assessSafety(claim.request)
    context.onEvent('safety.signal', {
      generationId: claim.generationId,
      level: safety.level,
      action: safety.action,
    } satisfies SafetySignal & { generationId: string })

    try {
      const input = {
        companion: claim.request.companion,
        context: claim.request.context,
        modelMode: claim.request.modelMode ?? 'standard',
        safetyInstruction: safetyPrompt(safety.level),
      }
      for await (const chunk of this.provider.stream(input, controller.signal)) {
        if (chunk.text) {
          output += chunk.text
          context.onEvent('message.delta', { generationId: claim.generationId, text: chunk.text })
        }
        if (chunk.usage) usage = chunk.usage
      }

      const finalUsage = usage ?? estimateUsage(claim.request, output)
      settledUnits = this.quota.settle(claim.reservationId, finalUsage)
      context.onEvent('usage.updated', {
        generationId: claim.generationId,
        inputTokens: finalUsage.inputTokens,
        outputTokens: finalUsage.outputTokens,
        reservedUnits: claim.reservedUnits,
        settledUnits,
      })
      context.onEvent('generation.completed', {
        generationId: claim.generationId,
        assistantMessageId: claim.assistantMessageId,
        finishReason: controller.signal.aborted ? 'cancelled' : 'stop',
      })
      this.idempotency.update(userId, idempotencyKey, controller.signal.aborted ? 'cancelled' : 'completed')
    } catch (error) {
      const cancelled = controller.signal.aborted || isAbortError(error)
      if (cancelled) {
        if (output) {
          settledUnits = this.quota.settle(claim.reservationId, estimateUsage(claim.request, output))
        } else {
          this.quota.release(claim.reservationId)
        }
        context.onEvent('generation.completed', {
          generationId: claim.generationId,
          assistantMessageId: claim.assistantMessageId,
          finishReason: 'cancelled',
        })
        this.idempotency.update(userId, idempotencyKey, 'cancelled')
      } else {
        if (output) {
          settledUnits = this.quota.settle(claim.reservationId, estimateUsage(claim.request, output))
        } else {
          this.quota.release(claim.reservationId)
        }
        context.onEvent('generation.failed', {
          generationId: claim.generationId,
          code: 'provider_error',
          message: error instanceof Error ? error.message : '模型服务暂时不可用',
          retryable: true,
          settledUnits,
        })
        this.idempotency.update(userId, idempotencyKey, 'failed')
      }
    } finally {
      this.active.delete(claim.generationId)
    }
  }

  cancel(userId: string, generationId: string): void {
    const active = this.active.get(generationId)
    if (!active) throw new NotFoundException('Generation is not active')
    if (active.userId !== userId) throw new NotFoundException('Generation is not active')
    active.controller.abort()
  }

  decodeChunk(chunk: ProviderChunk): string {
    return chunk.text ?? ''
  }

  private validate(request: GenerationRequest): void {
    if (
      !request?.conversationId?.trim() ||
      !request.clientMessageId?.trim() ||
      !request.assistantMessageId?.trim() ||
      !request.companion?.id?.trim() ||
      !Array.isArray(request.context) ||
      request.context.length === 0
    ) {
      throw new BadRequestException('Invalid generation request')
    }
    if (request.context.length > 80) {
      throw new BadRequestException('Context contains more than 80 messages')
    }
    if (request.context.some((message) => !message.id || !message.content || !message.role)) {
      throw new BadRequestException('Every context message requires id, role and content')
    }
  }
}

function assessSafety(request: GenerationRequest): SafetySignal {
  const latestUserText =
    [...request.context]
      .reverse()
      .find((message) => message.role === 'user')
      ?.content.toLowerCase() ?? ''
  const crisisWords = ['自杀', '不想活', '结束生命', '伤害自己', 'suicide', 'kill myself']
  if (crisisWords.some((word) => latestUserText.includes(word))) {
    return { level: 'crisis', action: 'show_support_resources' }
  }
  return { level: 'normal', action: 'none' }
}

function safetyPrompt(level: SafetyLevel): string {
  if (level === 'crisis') {
    return 'CRISIS: 暂停普通伴侣人格化回复，优先表达关心，建议联系现实中的可信任者和当地紧急服务。'
  }
  return '保持成年人、非露骨、尊重边界的陪伴；不要诱导用户依赖，也不要声称自己是真实人类。'
}

function estimateUsage(request: GenerationRequest, output: string): TokenUsage {
  return {
    inputTokens: Math.max(
      1,
      Math.ceil(request.context.map((message) => message.content).join('\n').length / 2.5),
    ),
    outputTokens: Math.max(1, Math.ceil(output.length / 2.5)),
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
