package com.xinyu.companion.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.CompanionProfile
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `persists nickname and readable avatar file while preserving companion id`() {
        val store = SharedPreferencesCompanionProfileStore(context)
        val avatar = File(context.filesDir, "test_avatar.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val saved = store.save(
            CompanionProfile(
                id = "should-not-change-id",
                name = "  清屿  ",
                avatarPath = avatar.absolutePath,
            ),
        )

        assertThat(saved.id).isEqualTo(CompanionProfile.MAIN_COMPANION_ID)
        assertThat(saved.displayName).isEqualTo("清屿")
        assertThat(saved.avatarGlyph).isEqualTo("清")
        assertThat(store.load().avatarPath).isEqualTo(avatar.absolutePath)

        avatar.delete()
        assertThat(store.load().avatarPath).isNull()
    }

    @Test
    fun `blank name falls back to default companion name`() {
        val store = SharedPreferencesCompanionProfileStore(context)

        val saved = store.save(CompanionProfile(name = "   "))

        assertThat(saved.displayName).isEqualTo(CompanionProfile.DEFAULT_NAME)
        assertThat(store.load().displayName).isEqualTo(CompanionProfile.DEFAULT_NAME)
    }
}
