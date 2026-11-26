package com.xinyu.companion.domain.memory

import com.xinyu.companion.domain.MessageRole
import java.security.MessageDigest

class MemoryValidationException(message: String) : IllegalArgumentException(message)

object MemoryValidator {
    private const val MAX_STATEMENT_CHARS = 320
    private const val MAX_CANDIDATES = 8

    fun validate(
        input: MemoryJobInput,
        result: MemoryExtractionResult,
    ): MemoryExtractionResult {
        // A quiet batch may contain no durable timeline. It still advances the
        // checkpoint, but does not manufacture a narrative just to fill the UI.
        val summary = MemoryPolicy.sanitizeSummary(result.summary)
        if (result.candidates.size > MAX_CANDIDATES) {
            throw MemoryValidationException("单批记忆候选过多")
        }

        val messagesById = input.messages.associateBy { it.id }
        val allowedIds = messagesById.keys
        val seen = mutableSetOf<String>()
        val candidates = result.candidates.mapNotNull { candidate ->
            val statement = candidate.statement.trim()
            if (statement.isBlank()) {
                return@mapNotNull null
            }
            if (statement.length > MAX_STATEMENT_CHARS) {
                return@mapNotNull null
            }
            if (candidate.sourceMessageIds.isEmpty() ||
                candidate.sourceMessageIds.any { it !in allowedIds }
            ) {
                return@mapNotNull null
            }
            val sourceMessages = candidate.sourceMessageIds.mapNotNull(messagesById::get)
            val hasAssistantSource = sourceMessages.any { it.role == MessageRole.ASSISTANT }
            if (candidate.sourceKind in setOf(
                    MemorySourceKind.USER_EXPLICIT,
                    MemorySourceKind.USER_FEEDBACK,
                ) && sourceMessages.none { it.role == MessageRole.USER }
            ) {
                return@mapNotNull null
            }
            if (candidate.sourceKind == MemorySourceKind.COMPANION_EXPLICIT &&
                !hasAssistantSource
            ) {
                return@mapNotNull null
            }
            val prepared = MemoryPolicy.prepare(input.messages, candidate)
                ?: return@mapNotNull null

            val normalizedKey = normalizedKey(
                kind = prepared.kind,
                scope = prepared.scope,
                subject = prepared.subject,
                predicate = prepared.predicate,
                objectText = prepared.objectText,
            )
            if (!seen.add(normalizedKey)) return@mapNotNull null

            prepared
        }
        return MemoryExtractionResult(summary = summary, candidates = candidates)
    }

    fun normalizedKey(
        kind: MemoryKind,
        scope: MemoryScope,
        subject: String,
        predicate: String,
        objectText: String,
    ): String {
        val raw = listOf(
            kind.name,
            scope.name,
            normalizePart(subject),
            normalizePart(predicate),
            normalizePart(objectText),
        ).joinToString("|")
        return sha256(raw)
    }

    fun isEligibleForPrompt(memory: LongTermMemory): Boolean {
        return MemoryPolicy.isEligibleForPrompt(memory)
    }

    private fun normalizePart(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}

