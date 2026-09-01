package com.akiha.akihalink.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Volatile private var cachedKey: SecretKey? = null

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(cipherText: String): String {
        val payload = Base64.getDecoder().decode(cipherText)
        require(payload.size > IV_BYTES) { "Invalid encrypted payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return@synchronized it }
            val loaded = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                    generateKey()
                }
            cachedKey = loaded
            loaded
        }
    }

    private companion object {
        const val KEY_ALIAS = "akihalink_subscription_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
