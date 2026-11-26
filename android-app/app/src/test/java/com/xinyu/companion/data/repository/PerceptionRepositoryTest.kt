package com.xinyu.companion.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationEventKind
import com.xinyu.companion.domain.perception.NotificationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerceptionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: PerceptionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PerceptionRepository { database }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `persists sanitized event and source metadata`() = runTest {
        val now = System.currentTimeMillis()
        repository.emit(event(now))
        repository.recordSource(
            NotificationSource(
                packageName = "com.example",
                appLabel = "菜鸟",
                lastSeenAt = now,
            ),
        )

        assertThat(repository.activeEvents(now)).hasSize(1)
        assertThat(repository.observeSources().first().single().appLabel).isEqualTo("菜鸟")
    }

    @Test
    fun `expired event is not returned`() = runTest {
        val now = System.currentTimeMillis()
        repository.emit(event(now, expiresAt = now - 1))

        assertThat(repository.activeEvents(now)).isEmpty()
    }

    @Test
    fun `clear removes all observation summaries`() = runTest {
        repository.emit(event(System.currentTimeMillis()))
        repository.clearEvents()

        assertThat(repository.activeEvents(System.currentTimeMillis())).isEmpty()
    }

    private fun event(
        now: Long,
        expiresAt: Long = now + 60_000,
    ) = NotificationEvent(
        id = "event-$now",
        dedupeKey = "dedupe-$now",
        sourcePackage = "com.example",
        sourceAppLabel = "菜鸟",
        kind = NotificationEventKind.PARCEL_READY,
        title = "包裹到达",
        summary = "有包裹到达，等待领取",
        observedAt = now,
        expiresAt = expiresAt,
        confidence = 0.9,
    )
}
