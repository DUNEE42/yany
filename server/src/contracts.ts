export type MessageRole = 'system' | 'user' | 'assistant'

export interface ContextMessage {
  id: string
  role: MessageRole
  content: string
  sequence: number
}

export interface CompanionProfile {
  id: string
  name: string
  persona: string
  speakingStyle: string
  boundaries: string[]
}

export interface GenerationRequest {
  conversationId: string
  clientMessageId: string
  assistantMessageId: string
  companion: CompanionProfile
  modelMode?: 'standard' | 'enhanced'
  context: ContextMessage[]
}

export interface TokenUsage {
  inputTokens: number
  outputTokens: number
}

export interface EntitlementsResponse {
  plan: string
  availableUnits: number
  resetAt: string
}

export type SafetyLevel = 'normal' | 'reminder' | 'dependency_risk' | 'crisis'

export interface SafetySignal {
  level: SafetyLevel
  action: 'none' | 'show_usage_reminder' | 'show_support_resources'
}
