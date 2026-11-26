package com.xinyu.companion.ui.perception

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationEventKind
import com.xinyu.companion.domain.perception.NotificationSource
import com.xinyu.companion.domain.perception.PerceptionAccessStatus
import com.xinyu.companion.domain.perception.PerceptionSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerceptionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsPermissionAndSourceControls() {
        composeRule.setContent {
            MaterialTheme {
                PerceptionScreen(
                    state = PerceptionUiState(
                        settings = PerceptionSettings(),
                        access = PerceptionAccessStatus(
                            notificationAccess = true,
                            usageAccess = false,
                        ),
                        sources = listOf(
                            NotificationSource(
                                packageName = "com.example.cainiao",
                                appLabel = "菜鸟",
                                lastSeenAt = 1,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRefreshAccess = {},
                    onNotificationSourceChange = { _, _ -> },
                    onForegroundAppChange = {},
                    onCoarseLocationChange = {},
                    onWebSearchChange = {},
                    onSaveSearchConfiguration = { _, _ -> },
                    onTestSearch = {},
                    onDeleteEvent = {},
                    onClearEvents = {},
                    onClearSources = {},
                )
            }
        }

        composeRule.onNodeWithText("环境感知").assertIsDisplayed()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("菜鸟"))
        composeRule.onNodeWithText("菜鸟").assertIsDisplayed()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("当前前台应用"))
        composeRule.onNodeWithText("当前前台应用").assertIsDisplayed()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("联网搜索"))
        composeRule.onNodeWithText("联网搜索").assertIsDisplayed()
    }

    @Test
    fun showsRecentObservationSummary() {
        composeRule.setContent {
            MaterialTheme {
                PerceptionScreen(
                    state = PerceptionUiState(
                        events = listOf(
                            NotificationEvent(
                                id = "event",
                                dedupeKey = "event",
                                sourcePackage = "com.example.cainiao",
                                sourceAppLabel = "菜鸟",
                                kind = NotificationEventKind.PARCEL_READY,
                                title = "包裹",
                                summary = "有包裹到达，等待领取",
                                observedAt = System.currentTimeMillis(),
                                expiresAt = System.currentTimeMillis() + 60_000,
                                confidence = 0.9,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRefreshAccess = {},
                    onNotificationSourceChange = { _, _ -> },
                    onForegroundAppChange = {},
                    onCoarseLocationChange = {},
                    onWebSearchChange = {},
                    onSaveSearchConfiguration = { _, _ -> },
                    onTestSearch = {},
                    onDeleteEvent = {},
                    onClearEvents = {},
                    onClearSources = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("有包裹到达，等待领取"))
        composeRule.onNodeWithText("有包裹到达，等待领取").assertIsDisplayed()
    }
}
