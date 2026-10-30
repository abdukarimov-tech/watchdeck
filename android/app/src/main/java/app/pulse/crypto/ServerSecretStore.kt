package app.pulse.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранение секрета сервера (токен) через AndroidKeyStore.
 *
 * Ключ генерируется внутри Keystore (при возможности — с StrongBox),
 * никогда не покидает secure hardware; наружу сохраняется только
 * зашифрованный blob (AES/GCM). Учетные данные не попадают в backup
 * (allowBackup=false в манифесте).
 */
class ServerSecretStore(context: Context) {

    data class ServerConfig(val relayUrl: String, val token: String)

    private val prefs = context.getSharedPreferences("pulse_secrets", Context.MODE_PRIVATE)

    fun save(config: ServerConfig) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        val encrypted = cipher.doFinal(config.token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_URL, config.relayUrl)
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): ServerConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val blob = prefs.getString(KEY_TOKEN, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return runCatching {
            val plain = cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP))
            ServerConfig(url, String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(spec.setIsStrongBoxBacked(true).build())
                return generator.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                spec.setIsStrongBoxBacked(false)
            }
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pulse_master_key"
        const val KEY_URL = "relay_url"
        const val KEY_TOKEN = "token_blob"
        const val KEY_IV = "token_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
