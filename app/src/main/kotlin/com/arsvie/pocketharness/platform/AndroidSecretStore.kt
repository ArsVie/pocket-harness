package com.arsvie.pocketharness.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import ph.ports.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] over the Android Keystore (ADR-003 §6).
 *
 * The AES-256-GCM key is generated inside the Keystore and never leaves it; this class only ever
 * holds a [SecretKey] *handle*. Ciphertext (iv || tag || bytes) is base64-encoded into a private
 * SharedPreferences file. The plaintext value is never logged, never written to disk, and never
 * placed in a `String` that outlives the call.
 *
 * A missing/undecryptable entry returns null rather than throwing: a re-install that loses the key
 * material must read as "no key set", not as a crash on startup.
 */
class AndroidSecretStore(context: Context) : SecretStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(ref: String): String? {
        val encoded = prefs.getString(ref, null) ?: return null
        return try {
            decrypt(encoded)
        } catch (t: Throwable) {
            // Never log the value; the ref name is enough to diagnose.
            Log.e(TAG, "could not decrypt secret '$ref': ${t.javaClass.simpleName}")
            null
        }
    }

    override fun put(ref: String, value: String) {
        prefs.edit().putString(ref, encrypt(value)).apply()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + bytes, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = all[0].toInt()
        val iv = all.copyOfRange(1, 1 + ivLength)
        val body = all.copyOfRange(1 + ivLength, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /** The Keystore entry, created on first use. */
    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "PocketHarness"
        const val PREFS_NAME = "ph_secrets"
        const val KEY_ALIAS = "ph_secret_store_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
    }
}
