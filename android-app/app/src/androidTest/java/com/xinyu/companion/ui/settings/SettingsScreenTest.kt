package com.xinyu.companion.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.ReplyLength
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstRunShowsConnectionOnly() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(loaded = true, hasApiKey = false),
                    onBack = {},
                    onKeyChange = {},
                    onModelChange = {},
                    onTestAndSave = {},
                    onStyleChange = { _: Float, _: Float, _: ReplyLength -> },
                    autoMemoryEnabled = true,
                    memoryCount = 0,
                    onAutoMemoryChange = {},
                    onOpenMemory = {},
                    onOpenPerception = {},
                    onClearApiKey = {},
                    onClearChatMessages = {},
                )
            }
        }

        composeRule.onNodeWithText("连接 DeepSeek").assertIsDisplayed()
        composeRule.onNodeWithText("测试并保存").assertIsDisplayed()
        composeRule.onNodeWithText("对话手感").assertDoesNotExist()
    }

    @Test
    fun configuredStateShowsStylePrivacyAndAbout() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        loaded = true,
                        hasApiKey = true,
                        conversationStyle = ConversationStyle(),
                    ),
                    onBack = {},
                    onKeyChange = {},
                    onModelChange = {},
                    onTestAndSave = {},
                    onStyleChange = { _: Float, _: Float, _: ReplyLength -> },
                    autoMemoryEnabled = true,
                    memoryCount = 2,
                    onAutoMemoryChange = {},
                    onOpenMemory = {},
                    onOpenPerception = {},
                    onClearApiKey = {},
                    onClearChatMessages = {},
                )
            }
        }

        assertTrue(
            composeRule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText("对话手感").assertExists()
        composeRule.onNodeWithText("记忆").assertExists()
        composeRule.onNodeWithText("提醒").assertExists()
        composeRule.onNodeWithText("环境感知").assertExists()
        composeRule.onNodeWithText("隐私与数据").assertExists()
        composeRule.onNodeWithText("关于").assertExists()
    }
}
