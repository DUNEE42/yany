export type ProviderName = 'mock' | 'deepseek'

export interface AppConfig {
  port: number
  host: string
  provider: ProviderName
  deepseekApiKey: string | undefined
  deepseekBaseUrl: string
  deepseekModel: string
  devBearerToken: string
  initialQuotaUnits: number
}

function positiveInteger(value: string | undefined, fallback: number): number {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const requestedProvider = env.MODEL_PROVIDER === 'deepseek' ? 'deepseek' : 'mock'
  return {
    port: positiveInteger(env.PORT, 3000),
    host: env.HOST?.trim() || '0.0.0.0',
    provider: requestedProvider,
    deepseekApiKey: env.DEEPSEEK_API_KEY?.trim() || undefined,
    deepseekBaseUrl: env.DEEPSEEK_BASE_URL?.trim() || 'https://api.deepseek.com',
    deepseekModel: env.DEEPSEEK_MODEL?.trim() || 'deepseek-flash',
    devBearerToken: env.DEV_BEARER_TOKEN?.trim() || 'dev-token',
    initialQuotaUnits: positiveInteger(env.INITIAL_QUOTA_UNITS, 120),
  }
}

export const APP_CONFIG = Symbol('APP_CONFIG')
