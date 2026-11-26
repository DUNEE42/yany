package com.xinyu.companion.data.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationIntakeTest {
    private lateinit var context: Context
    private lateinit var allowlist: NotificationAllowlistStore
    private lateinit var sink: InMemoryNotificationEventSink
    private lateinit var intake: NotificationIntake

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        allowlist = NotificationAllowlistStore(context)
        sink = InMemoryNotificationEventSink()
        intake = NotificationIntake(
            context = context,
            allowlistStore = allowlist,
            sink = sink,
            parser = NotificationParser(),
        )
    }

    @Test
    fun `unlisted source records package metadata but never stores body`() = runBlocking {
        allowlist.setAllowed(PACKAGE, false)

        intake.process(
            packageName = PACKAGE,
            dedupeKey = "parcel-1",
            title = "包裹已到",
            text = "验证码 123456",
            observedAt = 1_000,
        )

        assertThat(sink.events.value).isEmpty()
        assertThat(sink.sources.value.single().packageName).isEqualTo(PACKAGE)
    }

    @Test
    fun `allowed source stores a sanitized event`() = runBlocking {
        allowlist.setAllowed(PACKAGE, true)

        intake.process(
            packageName = PACKAGE,
            dedupeKey = "parcel-2",
            title = "包裹已到",
            text = "取件码 123456，地址：江苏省苏州市某路 8 号",
            observedAt = 2_000,
        )

        val event = sink.events.value.single()
        assertThat(event.sourcePackage).isEqualTo(PACKAGE)
        assertThat(event.summary).doesNotContain("123456")
        assertThat(event.summary).doesNotContain("8 号")
    }

    @Test
    fun `allowlist defaults to deny`() {
        allowlist.setAllowed(PACKAGE, false)
        assertThat(allowlist.isAllowed(PACKAGE)).isFalse()
    }

    @Test
    fun `separately constructed store observes persisted allowlist immediately`() {
        val writer = NotificationAllowlistStore(context)
        val reader = NotificationAllowlistStore(context)

        writer.setAllowed(PACKAGE, true)

        assertThat(reader.isAllowed(PACKAGE)).isTrue()
    }

    private companion object {
        const val PACKAGE = "com.example.notification"
    }
}
