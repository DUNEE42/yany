package com.xinyu.companion.data.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ApiSettingsStore(context: Context) : ProviderSettingsStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableConfigured = MutableStateFlow(preferences.contains(KEY_API_KEY))

    override val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    @Synchronized
    override fun load(): ProviderConfig {
        val encryptedKey = preferences.getString(KEY_API_KEY, null)
        val apiKey = encryptedKey
            ?.let { runCatching { String(decrypt(Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull() }
            .orEmpty()

        val config = ProviderConfig(
            apiKey = apiKey,
            baseUrl = preferences.getString(KEY_BASE_URL, null)
                ?: ProviderConfig.DEFAULT_BASE_URL,
            model = preferences.getString(KEY_MODEL, null)
                ?: ProviderConfig.DEFAULT_MODEL,
        )
        mutableConfigured.value = config.isConfigured
        return config
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun save(config: ProviderConfig) {
        val editor = preferences.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().ifBlank { ProviderConfig.DEFAULT_BASE_URL })
            .putString(KEY_MODEL, config.model.trim().ifBlank { ProviderConfig.DEFAULT_MODEL })

        if (config.apiKey.isBlank()) {
            editor.remove(KEY_API_KEY)
        } else {
            val encrypted = encrypt(config.apiKey.trim().toByteArray(Charsets.UTF_8))
            editor.putString(KEY_API_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        editor.commit()
        mutableConfigured.value = config.isConfigured
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun clearApiKey() {
        preferences.edit().remove(KEY_API_KEY).commit()
        mutableConfigured.value = false
    }

    private fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(value)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > IV_SIZE) { "Encrypted API key is malformed" }
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

    private companion object {
        const val PREFERENCES_NAME = "xinyu_provider_settings"
        const val KEY_API_KEY = "deepseek_api_key"
        const val KEY_BASE_URL = "provider_base_url"
        const val KEY_MODEL = "provider_model"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "xinyu_provider_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH_BITS = 128
    }
}
