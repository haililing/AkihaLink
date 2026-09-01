package com.akiha.akihalink.security

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.Base64

class ClashSecretStore(
    context: Context,
    private val cipher: SecretCipher,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences("secure_state", Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        preferences.getString("clash_secret", null)?.let { encrypted ->
            runCatching { cipher.decrypt(encrypted) }.getOrNull()?.let { return it }
        }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        preferences.edit { putString("clash_secret", cipher.encrypt(secret)) }
        return secret
    }
}
