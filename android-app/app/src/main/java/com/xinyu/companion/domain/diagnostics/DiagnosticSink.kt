package com.xinyu.companion.domain.diagnostics

enum class GenerationOutcome {
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

interface DiagnosticSink {
    fun recordGeneration(
        assistantMessageId: String,
        providerId: String,
        model: String,
        outcome: GenerationOutcome,
        durationMs: Long,
        errorCode: String?,
    )
}
