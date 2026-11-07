package com.barkadakaskas.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the app keeps the server address and the login token between launches. */
interface SessionStore {
    var serverUrl: String
    var token: String?
}

/**
 * Stores the API token encrypted with an AES-GCM key held in the Android Keystore,
 * so the raw token never sits in plain text on disk. The server URL is not secret.
 */
class TokenStore(context: Context) : SessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("barkada_session", Context.MODE_PRIVATE)

    /**
     * Fixed at build time (BuildConfig.DEFAULT_SERVER_URL); people never type it. Any address saved by
     * older versions is ignored, so a mistyped one cannot strand the app.
     */
    override var serverUrl: String
        get() = com.barkadakaskas.app.BuildConfig.DEFAULT_SERVER_URL
        set(_) { prefs.edit().remove(KEY_SERVER).apply() }

    override var token: String?
        // An unreadable token (e.g. the key was reset) simply means "signed out".
        get() = prefs.getString(KEY_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, encrypt(value))
            editor.apply()
        }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(":")
        require(parts.size == 2) { "Malformed token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "barkada_api_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "api_token"
    }
}
