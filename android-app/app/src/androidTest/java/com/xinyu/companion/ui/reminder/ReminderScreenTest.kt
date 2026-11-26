package com.xinyu.companion.ui.reminder

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinyu.companion.domain.reminder.ReminderRule
import com.xinyu.companion.ui.theme.XinYuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersExplicitLocalReminderAndExportsVisualSnapshot() {
        val now = System.currentTimeMillis()
        composeRule.setContent {
            XinYuTheme {
                ReminderScreen(
                    state = ReminderUiState(
                        isLoading = false,
                        rules = listOf(
                            ReminderRule(
                                id = "future",
                                message = "17:30 去驿站取快递",
                                triggerAtMillis = now + 60 * 60 * 1_000,
                                createdAtMillis = now,
                            ),
                            ReminderRule(
                                id = "past",
                                message = "记得给同学回消息",
                                triggerAtMillis = now - 60 * 60 * 1_000,
                                createdAtMillis = now,
                            ),
                        ),
                    ),
                    onBack = {},
                    onAdd = { _, _ -> },
                    onCancel = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("新建提醒").assertExists()
        composeRule.onNodeWithText("17:30 去驿站取快递").assertExists()
        composeRule.onNodeWithText("记得给同学回消息").assertExists()

        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "yanyu-1.8.0-reminder-screen.png")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: error("Cannot create screenshot")
        resolver.openOutputStream(uri).use { output ->
            requireNotNull(output)
            composeRule.onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
    }
}
