package com.xinyu.companion.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun headerStatesThatContextIsSentToProvider() {
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(hasApiKey = true),
                    companionName = "林",
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule
            .onNodeWithText("本地加密 · 必要上下文会发送给 DeepSeek")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("私人对话 · 仅保存在本机")
            .assertDoesNotExist()
    }

    @Test
    fun emptyChatKeepsOnePrimaryAction() {
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        hasApiKey = true,
                        isConversationReady = true,
                    ),
                    companionName = "林",
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("只属于你的日常").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("发送").assertIsDisplayed()
        composeRule.onNodeWithText("回到最新").assertDoesNotExist()
    }

    @Test
    fun streamingReplacesSendWithStopAction() {
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        hasApiKey = true,
                        isConversationReady = true,
                        isStreaming = true,
                    ),
                    companionName = "林",
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("停止生成").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("发送").assertDoesNotExist()
    }

    @Test
    fun searchConfirmationShowsExactQueryBeforeSending() {
        var confirmed = false
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        hasApiKey = true,
                        isConversationReady = true,
                        searchConfirmation = SearchConfirmationUiState(
                            originalMessage = "查一下张伟的失信记录",
                            query = "失信被执行人 公开信息",
                        ),
                    ),
                    companionName = "林",
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onOpenSettings = {},
                    onConfirmSearch = { confirmed = true },
                )
            }
        }

        composeRule.onNodeWithText("确认这次要搜什么").assertIsDisplayed()
        composeRule.onNodeWithText("失信被执行人 公开信息").assertIsDisplayed()
        composeRule.onNodeWithText("搜索并发送").performClick()
        composeRule.runOnIdle { assert(confirmed) }
    }
}
