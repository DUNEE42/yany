import type { CompanionProfile, ContextMessage, TokenUsage } from '../contracts.js'

export interface ProviderInput {
  companion: CompanionProfile
  context: ContextMessage[]
  modelMode: 'standard' | 'enhanced'
  safetyInstruction?: string
}

export interface ProviderChunk {
  text?: string
  usage?: TokenUsage
}

export interface ModelProvider {
  readonly name: 'mock' | 'deepseek'
  stream(input: ProviderInput, signal: AbortSignal): AsyncGenerator<ProviderChunk>
}

export const MODEL_PROVIDER = Symbol('MODEL_PROVIDER')
