package com.xinyu.companion.ui.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinyu.companion.domain.memory.LongTermMemory
import com.xinyu.companion.domain.memory.MemoryConfirmationState
import com.xinyu.companion.domain.memory.MemoryKind
import com.xinyu.companion.domain.memory.MemoryScope
import com.xinyu.companion.domain.memory.MemorySensitivity
import com.xinyu.companion.domain.memory.MemorySourceKind
import com.xinyu.companion.domain.memory.MemoryStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsAutomaticMemoryControlsAndPendingCandidate() {
        composeRule.setContent {
            MaterialTheme {
                MemoryScreen(
                    state = MemoryUiState(
                        autoMemoryEnabled = true,
                        memories = listOf(candidateMemory()),
                    ),
                    onBack = {},
                    onAutoMemoryChange = {},
                    onConsolidateNow = {},
                    onAdd = { _, _ -> },
                    onUpdate = { _, _ -> },
                    onConfirm = {},
                    onReject = {},
                    onDelete = {},
                    onForgetRecent = {},
                    onDiscardPending = {},
                    onClearAll = {},
                )
            }
        }

        composeRule.onNodeWithText("自动记忆").assertIsDisplayed()
        composeRule.onNodeWithText("现在整理").assertIsDisplayed()
        composeRule.onNodeWithText("用户可能更喜欢安静").assertIsDisplayed()
        composeRule.onNodeWithText("确认").assertIsDisplayed()
        composeRule.onNodeWithText("清理全部待确认").assertIsDisplayed()
    }

    @Test
    fun recentEventsAreSeparatedFromStableMemory() {
        composeRule.setContent {
            MaterialTheme {
                MemoryScreen(
                    state = MemoryUiState(
                        autoMemoryEnabled = true,
                        memories = listOf(eventMemory()),
                    ),
                    onBack = {},
                    onAutoMemoryChange = {},
                    onConsolidateNow = {},
                    onAdd = { _, _ -> },
                    onUpdate = { _, _ -> },
                    onConfirm = {},
                    onReject = {},
                    onDelete = {},
                    onForgetRecent = {},
                    onDiscardPending = {},
                    onClearAll = {},
                )
            }
        }

        composeRule.onNodeWithText("近期经历 1").performClick()
        composeRule.onNodeWithText("用户今天面试不顺").assertIsDisplayed()
    }

    @Test
    fun whenThereAreNoPendingItemsTheActiveSectionIsShown() {
        composeRule.setContent {
            MaterialTheme {
                MemoryScreen(
                    state = MemoryUiState(
                        autoMemoryEnabled = true,
                        memories = listOf(
                            candidateMemory().copy(
                                id = "active",
                                statement = "用户喜欢先听结论",
                                sourceKind = MemorySourceKind.USER_EXPLICIT,
                                confirmationState = MemoryConfirmationState.CONFIRMED,
                                status = MemoryStatus.ACTIVE,
                            ),
                        ),
                    ),
                    onBack = {},
                    onAutoMemoryChange = {},
                    onConsolidateNow = {},
                    onAdd = { _, _ -> },
                    onUpdate = { _, _ -> },
                    onConfirm = {},
                    onReject = {},
                    onDelete = {},
                    onForgetRecent = {},
                    onDiscardPending = {},
                    onClearAll = {},
                )
            }
        }

        composeRule.onNodeWithText("用户喜欢先听结论").assertIsDisplayed()
        composeRule.onNodeWithText("已生效 1").assertIsDisplayed()
    }

    private fun candidateMemory() = LongTermMemory(
        id = "m1",
        companionId = "companion-linyu",
        kind = MemoryKind.PREFERENCE,
        scope = MemoryScope.USER,
        subject = "user",
        predicate = "prefers",
        objectText = "安静",
        statement = "用户可能更喜欢安静",
        normalizedKey = "candidate",
        sourceKind = MemorySourceKind.INFERRED,
        confirmationState = MemoryConfirmationState.UNCONFIRMED,
        status = MemoryStatus.CANDIDATE,
        confidence = 0.6,
        importance = 3,
        sensitivity = MemorySensitivity.NORMAL,
        observedAt = System.currentTimeMillis(),
        validFrom = System.currentTimeMillis(),
        validTo = null,
        supersededById = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        lastAccessedAt = null,
    )

    private fun eventMemory() = candidateMemory().copy(
        id = "event",
        kind = MemoryKind.EVENT,
        statement = "用户今天面试不顺",
        objectText = "面试不顺",
        validTo = System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1_000L,
    )
}

