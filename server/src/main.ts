import 'reflect-metadata'
import { NestFactory } from '@nestjs/core'
import { FastifyAdapter, type NestFastifyApplication } from '@nestjs/platform-fastify'
import { AppModule } from './app.module.js'
import type { AppConfig } from './config.js'
import { APP_CONFIG } from './config.js'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create<NestFastifyApplication>(AppModule, new FastifyAdapter(), {
    logger: ['error', 'warn', 'log'],
  })
  app.enableCors({
    origin: true,
    allowedHeaders: ['Authorization', 'Content-Type', 'Idempotency-Key'],
  })
  const config = app.get<AppConfig>(APP_CONFIG)
  await app.listen({ port: config.port, host: config.host })
  console.log(`Companion server listening on http://${config.host}:${config.port} (${config.provider})`)
}

void bootstrap()
