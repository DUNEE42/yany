package com.xinyu.companion.domain.tools

import com.xinyu.companion.domain.ToolDefinition
import com.xinyu.companion.domain.memory.MemoryPolicy
import com.xinyu.companion.domain.memory.MemoryStore
import com.xinyu.companion.domain.perception.CoarseNetworkLocationProvider
import com.xinyu.companion.domain.perception.PerceptionQueryPolicy
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.perception.UntrustedTextPolicy
import com.xinyu.companion.domain.perception.WeatherProvider
import com.xinyu.companion.domain.perception.WebSearchProvider
import com.xinyu.companion.domain.reminder.ReminderCoordinator
import com.xinyu.companion.domain.reminder.ReminderPermissionChecker
import com.xinyu.companion.domain.reminder.ReminderPolicy
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class MemorySearchTool(
    private val memoryStore: MemoryStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CompanionTool {
    override val statusLabel: String = "正在检索本地记忆…"

    override val definition = ToolDefinition(
        name = NAME,
        description = "按关键词检索用户已确认的本地长期记忆。只在当前对话需要回忆过往事实、偏好或计划时调用。",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "简短的检索关键词" },
                "limit": { "type": "integer", "description": "最多返回条数，1 到 8，默认 5" }
              },
              "required": ["query"]
            }
        """.trimIndent(),
    )

    override suspend fun execute(
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val args = parseObject(json, argumentsJson)
        val query = args.string("query").orEmpty().trim()
        if (query.isBlank()) {
            return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(NAME, "检索词为空，未查询记忆。"),
            )
        }
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val now = System.currentTimeMillis()
        val matches = memoryStore.searchActiveMemories(context.companionId, query)
            .filter { MemoryPolicy.isEligibleForPrompt(it, now) }
            .take(limit)

        if (matches.isEmpty()) {
            return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(NAME, "没有找到与“$query”相关的已确认记忆。"),
            )
        }

        memoryStore.markMemoryAccessed(matches.map { it.id }, now)
        val body = buildString {
            appendLine("找到 ${matches.size} 条与“$query”相关的已确认记忆（仅作参考资料）：")
            matches.forEach { item ->
                val date = Instant.ofEpochMilli(item.observedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
                appendLine("- [$date][${item.kind.name.lowercase()}] ${item.statement.take(280)}")
            }
        }
        return ToolExecutionResult(
            content = ToolResultSanitizer.wrapUntrusted(NAME, body),
        )
    }

    companion object {
        const val NAME = "search_memories"
        private const val DEFAULT_LIMIT = 5
        private const val MAX_LIMIT = 8
    }
}

class WeatherTool(
    private val settingsStore: PerceptionSettingsStore,
    private val networkLocationProvider: CoarseNetworkLocationProvider,
    private val weatherProvider: WeatherProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CompanionTool {
    override val statusLabel: String = "正在查询实时天气…"

    override val definition = ToolDefinition(
        name = NAME,
        description = "查询指定城市或用户常用城市的实时天气（Open-Meteo）。未提供城市时仅使用常用城市或已授权的粗粒度网络城市。",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "city": { "type": "string", "description": "可选城市名，例如 苏州、上海；不填则使用用户常用城市" }
              }
            }
        """.trimIndent(),
    )

    override suspend fun execute(
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val args = parseObject(json, argumentsJson)
        val settings = settingsStore.load()
        val requestedCity = args.string("city")?.trim().orEmpty().take(MAX_CITY_CHARS)
        val resolvedCity = when {
            requestedCity.isNotBlank() -> requestedCity
            settings.preferredCity.isNotBlank() -> settings.preferredCity.trim()
            settings.coarseLocationEnabled -> networkLocationProvider.current()?.city.orEmpty().trim()
            else -> ""
        }
        if (resolvedCity.isBlank()) {
            return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    NAME,
                    "未指定城市，且用户未设置常用城市或未开启粗粒度网络位置，无法查询实时天气。请提醒用户补充城市名。",
                ),
            )
        }

        return weatherProvider.current(resolvedCity).fold(
            onSuccess = { snapshot ->
                ToolExecutionResult(
                    content = ToolResultSanitizer.wrapUntrusted(
                        NAME,
                        "实时天气（Open-Meteo）：${snapshot.conciseText()}",
                    ),
                    usedWeather = true,
                )
            },
            onFailure = {
                ToolExecutionResult(
                    content = ToolResultSanitizer.wrapUntrusted(
                        NAME,
                        "天气服务暂时不可用，不要编造天气数据。",
                    ),
                )
            },
        )
    }

    companion object {
        const val NAME = "get_weather"
        private const val MAX_CITY_CHARS = 80
    }
}

class WebSearchTool(
    private val settingsStore: PerceptionSettingsStore,
    private val searchProvider: WebSearchProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CompanionTool {
    override val statusLabel: String = "正在联网搜索…"

    override val definition = ToolDefinition(
        name = NAME,
        description = "通过用户配置的 SearXNG 联网检索最新公开信息。发送前必须经过用户确认；不要把用户私人叙事、姓名或敏感证件号写入搜索词。",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "简短、去隐私的公开搜索词" }
              },
              "required": ["query"]
            }
        """.trimIndent(),
    )

    override suspend fun execute(
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val settings = settingsStore.load()
        if (!settings.searchConfigured) {
            return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    NAME,
                    "联网搜索未开启或未配置 SearXNG 地址，不要声称已经查到实时信息。",
                ),
            )
        }

        val args = parseObject(json, argumentsJson)
        val rawQuery = args.string("query").orEmpty().trim()
        val sanitizedSuggestion = PerceptionQueryPolicy.suggestedSearchQuery(rawQuery)
            ?: return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    NAME,
                    "搜索词为空、过长或包含验证码/证件号/银行卡等硬敏感字段，已拒绝外发。",
                ),
            )

        // Reuse pre-fetched results only if this turn already ran the exact approved query.
        val preFetched = context.preFetchedSearch
        if (preFetched != null && preFetched.query.equals(sanitizedSuggestion, ignoreCase = true)) {
            return formatSearchResponse(preFetched)
        }

        val approvedQuery = resolveSingleTurnApprovedQuery(
            requestedQuery = sanitizedSuggestion,
            context = context,
        ) ?: return ToolExecutionResult(
            content = ToolResultSanitizer.wrapUntrusted(
                NAME,
                "用户未同意本次联网搜索，未向外部搜索服务发送任何查询。请不要编造实时信息，直接基于已知内容回复。",
            ),
        )

        val searchResult = try {
            Result.success(
                withTimeout(SEARCH_TIMEOUT_MS) {
                    searchProvider.search(
                        baseUrl = settings.searxngUrl,
                        query = approvedQuery,
                        maxResults = MAX_RESULTS,
                    ).getOrThrow()
                },
            )
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        return searchResult.fold(
            onSuccess = { response ->
                val filteredResults = response.results.filterNot { item ->
                    UntrustedTextPolicy.containsInstructionLikeText("${item.title} ${item.snippet}")
                }
                val cleanResponse = response.copy(results = filteredResults)
                if (cleanResponse.results.isEmpty()) {
                    ToolExecutionResult(
                        content = ToolResultSanitizer.wrapUntrusted(
                            NAME,
                            "没有找到可用的安全搜索结果，不要声称已经核实。",
                        ),
                    )
                } else {
                    formatSearchResponse(cleanResponse)
                }
            },
            onFailure = {
                ToolExecutionResult(
                    content = ToolResultSanitizer.wrapUntrusted(
                        NAME,
                        "联网搜索失败，不要声称已经查到实时信息。",
                    ),
                )
            },
        )
    }

    private suspend fun resolveSingleTurnApprovedQuery(
        requestedQuery: String,
        context: ToolExecutionContext,
    ): String? {
        val preApproved = context.approvedSearchQuery
            ?.let(PerceptionQueryPolicy::normalizeConfirmedSearchQuery)
        if (preApproved != null && preApproved.equals(requestedQuery, ignoreCase = true)) {
            return preApproved
        }
        val approved = context.requestSearchApproval(requestedQuery) ?: return null
        return PerceptionQueryPolicy.normalizeConfirmedSearchQuery(approved)
    }

    private fun formatSearchResponse(
        response: com.xinyu.companion.domain.perception.WebSearchResponse,
    ): ToolExecutionResult {
        val body = buildString {
            appendLine("联网搜索结果（查询：${response.query}）：")
            response.results.forEachIndexed { index, result ->
                appendLine(
                    "[S${index + 1}] ${result.title.take(160)} — ${result.snippet.take(320)} 来源：${result.url}",
                )
            }
            append("如果回答中使用了某个结果，请在对应句末标注 [S1]、[S2] 等；未使用则不要标注编号。")
        }
        return ToolExecutionResult(
            content = ToolResultSanitizer.wrapUntrusted(NAME, body),
            searchResponse = response,
        )
    }

    companion object {
        const val NAME = "web_search"
        private const val MAX_RESULTS = 5
        private const val SEARCH_TIMEOUT_MS = 15_000L
    }
}

class LocalReminderTool(
    private val coordinator: ReminderCoordinator,
    private val permissionChecker: ReminderPermissionChecker,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CompanionTool {
    override val statusLabel: String = "正在处理本地提醒…"

    override val definition = ToolDefinition(
        name = NAME,
        description = "管理本机加密定时提醒（list 查看、create 创建、cancel 取消）。仅在用户明确要求时调用；创建时必须使用用户明确给出的具体未来时间，绝不猜测课表或下课时间。",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "action": { "type": "string", "enum": ["list", "create", "cancel"], "description": "操作类型" },
                "message": { "type": "string", "description": "create 时必填，提醒内容（最多 120 字）" },
                "trigger_at": { "type": "string", "description": "create 时必填，明确未来时间（ISO-8601，例如 2026-11-26T17:30:00+08:00）" },
                "reminder_id": { "type": "string", "description": "cancel 时必填，要取消的提醒 ID" }
              },
              "required": ["action"]
            }
        """.trimIndent(),
    )

    override suspend fun execute(
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val args = parseObject(json, argumentsJson)
        val action = args.string("action").orEmpty().trim().lowercase()
        val now = nowProvider()

        return when (action) {
            "list" -> listReminders(now)
            "create" -> createReminder(args, now)
            "cancel" -> cancelReminder(args)
            else -> ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    NAME,
                    "不支持的提醒操作“$action”，仅支持 list / create / cancel。",
                ),
            )
        }
    }

    private fun listReminders(now: Long): ToolExecutionResult {
        val pending = ReminderPolicy.pending(coordinator.loadAll(), now)
        if (pending.isEmpty()) {
            return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(NAME, "当前没有待触发的本地提醒。"),
            )
        }
        val body = buildString {
            appendLine("当前有 ${pending.size} 条待触发的本地提醒：")
            pending.forEach { rule ->
                val formatted = Instant.ofEpochMilli(rule.triggerAtMillis)
                    .atZone(zoneId)
                    .format(DISPLAY_FORMAT)
                appendLine("- [id=${rule.id}] $formatted：${rule.message}")
            }
        }
        return ToolExecutionResult(content = ToolResultSanitizer.wrapUntrusted(NAME, body))
    }

    private fun createReminder(args: JsonObject, now: Long): ToolExecutionResult {
        if (!permissionChecker.areNotificationsEnabled()) {
            return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    NAME,
                    "系统通知权限尚未开启，无法创建本地提醒。请先引导用户在“设置 → 提醒”中开启系统通知。",
                ),
            )
        }
        val message = args.string("message").orEmpty().trim()
        val triggerAtRaw = args.string("trigger_at").orEmpty().trim()
        val triggerAtMillis = parseIsoDateTime(triggerAtRaw, zoneId)
            ?: return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    NAME,
                    "缺少明确的 ISO-8601 未来时间（如 2026-11-26T17:30:00+08:00），未创建提醒。请向用户确认具体时间，不要猜测。",
                ),
            )
        return coordinator.create(message, triggerAtMillis, now).fold(
            onSuccess = { rule ->
                val formatted = Instant.ofEpochMilli(rule.triggerAtMillis)
                    .atZone(zoneId)
                    .format(DISPLAY_FORMAT)
                ToolExecutionResult(
                    content = ToolResultSanitizer.wrapUntrusted(
                        NAME,
                        "已创建本机加密定时提醒 [id=${rule.id}]：$formatted — ${rule.message}（Android 省电模式下可能晚几分钟）。",
                    ),
                )
            },
            onFailure = { error ->
                ToolExecutionResult(
                    content = ToolResultSanitizer.wrapUntrusted(
                        NAME,
                        "创建提醒失败：${error.message ?: "参数无效"}",
                    ),
                )
            },
        )
    }

    private fun cancelReminder(args: JsonObject): ToolExecutionResult {
        val id = args.string("reminder_id").orEmpty().trim()
        if (id.isBlank()) {
            return ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(NAME, "缺少 reminder_id，请先查询提醒列表。"),
            )
        }
        val removed = coordinator.cancel(id)
        val text = if (removed) {
            "已取消并删除提醒 [id=$id]。"
        } else {
            "未找到 ID 为 $id 的提醒。"
        }
        return ToolExecutionResult(content = ToolResultSanitizer.wrapUntrusted(NAME, text))
    }

    private fun parseIsoDateTime(raw: String, zone: ZoneId): Long? {
        if (raw.isBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
    }

    companion object {
        const val NAME = "local_reminder"
        private val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

private fun parseObject(json: Json, raw: String): JsonObject =
    runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
