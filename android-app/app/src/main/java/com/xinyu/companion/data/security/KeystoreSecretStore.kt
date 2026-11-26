package com.xinyu.companion.data.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface DatabasePassphraseProvider {
    fun getOrCreatePassphrase(): ByteArray
}

class KeystoreSecretStore(
    context: Context,
    private val alias: String = "xinyu_database_key_v1",
) : DatabasePassphraseProvider {
    private val preferences = context.getSharedPreferences("xinyu_secure_keys", Context.MODE_PRIVATE)

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun getOrCreatePassphrase(): ByteArray {
        val stored = preferences.getString(PASSPHRASE_KEY, null)
        if (stored != null) {
            return decrypt(Base64.decode(stored, Base64.NO_WRAP))
        }

        val generated = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        preferences.edit()
            .putString(PASSPHRASE_KEY, Base64.encodeToString(encrypt(generated), Base64.NO_WRAP))
            .commit()
        return generated
    }

    private fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value)
        return cipher.iv + encrypted
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > IV_SIZE) { "Encrypted database key is malformed" }
        val iv = value.copyOfRange(0, IV_SIZE)
        val encrypted = value.copyOfRange(IV_SIZE, value.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
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
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH_BITS = 128
        const val PASSPHRASE_KEY = "database_passphrase"
    }
}

