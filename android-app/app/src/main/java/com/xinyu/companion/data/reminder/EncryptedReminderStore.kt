package com.xinyu.companion.data.reminder

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.xinyu.companion.domain.reminder.ReminderRule
import com.xinyu.companion.domain.reminder.ReminderRuleStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class EncryptedReminderStore(context: Context) : ReminderRuleStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    override fun loadAll(): List<ReminderRule> {
        val encrypted = preferences.getString(KEY_RULES, null) ?: return emptyList()
        val plaintext = decrypt(Base64.decode(encrypted, Base64.NO_WRAP))
        return json.decodeFromString(
            ListSerializer(ReminderRecord.serializer()),
            plaintext.toString(Charsets.UTF_8),
        ).map(ReminderRecord::toDomain)
    }

    @Synchronized
    override fun add(rule: ReminderRule) {
        val current = loadAll().toMutableList()
        if (current.any { it.id == rule.id }) return
        current += rule
        persist(current)
    }

    @Synchronized
    override fun remove(id: String): Boolean {
        val current = loadAll()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        persist(remaining)
        return true
    }

    @SuppressLint("ApplySharedPref")
    private fun persist(rules: List<ReminderRule>) {
        val serialized = json.encodeToString(
            ListSerializer(ReminderRecord.serializer()),
            rules.sortedBy(ReminderRule::triggerAtMillis).map(ReminderRecord::fromDomain),
        )
        val encrypted = encrypt(serialized.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_RULES, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }

    private fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(value)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > IV_SIZE) { "本地提醒数据格式不正确" }
        val iv = value.copyOfRange(0, IV_SIZE)
        val encrypted = value.copyOfRange(IV_SIZE, value.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Serializable
    private data class ReminderRecord(
        val id: String,
        val message: String,
        val triggerAtMillis: Long,
        val createdAtMillis: Long,
    ) {
        fun toDomain() = ReminderRule(id, message, triggerAtMillis, createdAtMillis)

        companion object {
            fun fromDomain(rule: ReminderRule) = ReminderRecord(
                id = rule.id,
                message = rule.message,
                triggerAtMillis = rule.triggerAtMillis,
                createdAtMillis = rule.createdAtMillis,
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "yanyu_reminders_v1"
        const val KEY_RULES = "rules"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "yanyu_reminder_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH_BITS = 128
    }
}
