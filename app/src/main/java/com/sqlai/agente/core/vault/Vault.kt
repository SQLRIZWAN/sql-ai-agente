package com.sqlai.agente.core.vault

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM envelope backed by the Android Keystore.
 *
 * The master key NEVER leaves the TEE/StrongBox. Everything persisted by SQL AI AGENTE
 * (provider keys, exchange keys, private material, trading signals) is sealed with it.
 * Cipher state is thread-confined: each call builds a fresh [Cipher] so concurrent
 * traders/agents cannot corrupt IV state.
 */
class Vault(context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_ALG).apply { load(null) }
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(false)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Seals arbitrary bytes under a Keystore AES-256-GCM key. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return ByteArray(1 + iv.size + body.size).also { out ->
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(body, 0, out, 1 + iv.size, body.size)
        }
    }

    /** Reverses [seal]; throws [javax.crypto.AEADBadTagException] on tamper. */
    fun unseal(packed: ByteArray): ByteArray {
        val ivLen = packed[0].toInt()
        val iv = packed.copyOfRange(1, 1 + ivLen)
        val body = packed.copyOfRange(1 + ivLen, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    fun putSecret(alias: String, value: String) {
        prefs.edit().putString(prefsKey(alias), Base64.encodeToString(seal(value.toByteArray()), Base64.NO_WRAP)).apply()
    }

    fun getSecret(alias: String): String? {
        val raw = prefs.getString(prefsKey(alias), null) ?: return null
        return runCatching { String(unseal(Base64.decode(raw, Base64.NO_WRAP))) }.getOrNull()
    }

    fun hasSecret(alias: String): Boolean = prefs.contains(prefsKey(alias))

    fun deleteSecret(alias: String) = prefs.edit().remove(prefsKey(alias)).apply()

    fun putPlain(key: String, value: String) = prefs.edit().putString("p_$key", value).apply()
    fun getPlain(key: String, default: String? = null): String? = prefs.getString("p_$key", default)

    private fun prefsKey(alias: String) = "s_${alias.lowercase()}"

    private fun obtainKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        synchronized(this) {
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_ALG)
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return kg.generateKey()
        }
    }

    companion object {
        private const val KEYSTORE_ALG = "AndroidKeyStore"
        private const val KEY_ALIAS = "sqlai_master_aes256gcm"
        private const val PREFS_FILE = "sqlai_secure_prefs"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        // Stable well-known aliases used across the app (never the values themselves).
        const val KEY_GEMINI = "provider.gemini"
        const val KEY_GROK = "provider.grok"
        const val KEY_DEEPSEEK = "provider.deepseek"
        const val KEY_OPENAI = "provider.openai"
        const val KEY_OLLAMA = "provider.ollama.endpoint"
        const val KEY_BINANCE_API = "exchange.binance.api_key"
        const val KEY_BINANCE_SECRET = "exchange.binance.api_secret"
        const val KEY_BITGET_API = "exchange.bitget.api_key"
        const val KEY_BITGET_SECRET = "exchange.bitget.api_secret"
        const val KEY_BITGET_PASSPHRASE = "exchange.bitget.passphrase"
        const val KEY_BYBIT_API = "exchange.bybit.api_key"
        const val KEY_BYBIT_SECRET = "exchange.bybit.api_secret"
        const val KEY_EXNESS_TOKEN = "exchange.exness.token"
    }
}
