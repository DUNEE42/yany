package com.xinyu.companion.ui.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.reminder.ReminderCoordinator
import com.xinyu.companion.domain.reminder.ReminderRule
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReminderUiState(
    val rules: List<ReminderRule> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val coordinator: ReminderCoordinator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReminderUiState())
    val uiState: StateFlow<ReminderUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun add(message: String, triggerAtMillis: Long) {
        if (mutableState.value.isSaving) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(isSaving = true, error = null, message = null)
            }
            coordinator.create(message, triggerAtMillis).fold(
                onSuccess = {
                    refreshFromCoordinator(
                        message = "提醒已设为 ${formatRuleTime(it)}",
                    )
                },
                onFailure = { error ->
                    mutableState.update {
                        it.copy(
                            isSaving = false,
                            error = error.message ?: "无法保存提醒",
                        )
                    }
                },
            )
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { coordinator.cancel(id) }
            }.onSuccess {
                refreshFromCoordinator(message = "提醒已删除")
            }.onFailure { error ->
                mutableState.update {
                    it.copy(error = error.message ?: "无法删除提醒")
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching { coordinator.reschedulePending() }
            }
            runCatching {
                withContext(ioDispatcher) { coordinator.loadAll() }
            }.onSuccess { rules ->
                mutableState.update {
                    it.copy(
                        rules = rules.sortedBy(ReminderRule::triggerAtMillis),
                        isLoading = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "无法读取本地提醒",
                    )
                }
            }
        }
    }

    private suspend fun refreshFromCoordinator(message: String) {
        val rules = withContext(ioDispatcher) { coordinator.loadAll() }
        mutableState.update {
            it.copy(
                rules = rules.sortedBy(ReminderRule::triggerAtMillis),
                isLoading = false,
                isSaving = false,
                error = null,
                message = message,
            )
        }
    }

    private fun formatRuleTime(rule: ReminderRule): String =
        java.time.Instant.ofEpochMilli(rule.triggerAtMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}
