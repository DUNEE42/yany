package com.xinyu.companion.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.data.local.ConversationEntity
import com.xinyu.companion.data.local.MessageEntity
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.MessageRole
import com.xinyu.companion.domain.MessageStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ChatRepository { database }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `appendTurn stores distinct user and assistant messages`() = runTest {
        repository.ensureSeeded("c1", CompanionProfile())

        val turn = repository.appendTurn("c1", "你好")
        val messages = repository.getMessages("c1")

        assertThat(messages).hasSize(3)
        assertThat(messages.map { it.sequence }.toSet()).hasSize(3)
        assertThat(turn.user.role).isEqualTo(MessageRole.USER)
        assertThat(turn.assistant.replyToMessageId).isEqualTo(turn.user.id)
        assertThat(messages.last().content).isEmpty()
    }

    @Test
    fun `retry reuses the user message and does not append a new turn`() = runTest {
        repository.ensureSeeded("c1", CompanionProfile())
        val first = repository.appendTurn("c1", "记住这件事")
        repository.updateMessage(first.assistant.id, "半截回复", MessageStatus.FAILED)
        val beforeRetry = repository.getMessages("c1")

        val retried = repository.prepareRetry(first.assistant.id)
        val afterRetry = repository.getMessages("c1")

        assertThat(retried).isNotNull()
        assertThat(retried!!.user.id).isEqualTo(first.user.id)
        assertThat(afterRetry).hasSize(beforeRetry.size)
        assertThat(afterRetry.count { it.role == MessageRole.USER }).isEqualTo(1)
        assertThat(afterRetry.last().content).isEmpty()
        assertThat(afterRetry.last().status).isEqualTo(MessageStatus.STREAMING)
    }

    @Test
    fun `duplicate sequence is rejected instead of replacing a message`() = runTest {
        val dao = database.companionDao()
        dao.insertConversation(
            ConversationEntity(
                id = "c1",
                companionId = "p1",
                title = "test",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val first = MessageEntity(
            id = "m1",
            conversationId = "c1",
            role = MessageRole.USER.name,
            sequence = 1,
            content = "first",
            status = MessageStatus.COMPLETED.name,
            clientMessageId = "client-1",
            replyToMessageId = null,
            createdAt = 1,
            updatedAt = 1,
        )
        val conflicting = first.copy(id = "m2", clientMessageId = "client-2")
        dao.insertMessage(first)

        val failure = runCatching { dao.insertMessage(conflicting) }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(dao.getMessages("c1")).hasSize(1)
        assertThat(dao.getMessage("m1")!!.content).isEqualTo("first")
    }

    @Test
    fun `unfinished messages are interrupted after process recovery`() = runTest {
        repository.ensureSeeded("c1", CompanionProfile())
        val turn = repository.appendTurn("c1", "生成到一半")

        repository.recoverIncomplete("c1")

        val assistant = repository.getMessages("c1").first { it.id == turn.assistant.id }
        assertThat(assistant.status).isEqualTo(MessageStatus.INTERRUPTED)
    }

    @Test
    fun `conversation deletion cascades to messages`() = runTest {
        repository.ensureSeeded("c1", CompanionProfile())
        repository.appendTurn("c1", "会被级联删除")

        database.companionDao().deleteConversation("c1")

        assertThat(repository.getMessages("c1")).isEmpty()
    }

}
