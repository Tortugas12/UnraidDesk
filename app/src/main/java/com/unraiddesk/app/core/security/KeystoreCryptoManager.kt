package com.unraiddesk.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.unraiddesk.app.AppBranding
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Direct AndroidKeyStore AES-256-GCM encryption manager for secrets (API keys, SMB credentials).
 * Never uses deprecated androidx.security:security-crypto.
 */
class KeystoreCryptoManager internal constructor(
    private val keyProvider: SecretKeyProvider
) {
    constructor(keyAlias: String = DEFAULT_KEY_ALIAS) : this(AndroidKeyStoreProvider(keyAlias))

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == GCM_IV_LENGTH_BYTES) {
            "Unexpected GCM IV length: ${iv.size}"
        }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(iv.size + ciphertext.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(ciphertext, 0, this, iv.size, ciphertext.size)
        }
    }

    fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        require(ivAndCiphertext.size > GCM_IV_LENGTH_BYTES) {
            "Encrypted payload is too short."
        }
        val iv = ivAndCiphertext.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = ivAndCiphertext.copyOfRange(GCM_IV_LENGTH_BYTES, ivAndCiphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider.getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(ciphertext)
    }

    fun encryptString(plaintext: String): String {
        val encrypted = encrypt(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun decryptString(encoded: String): String {
        val decoded = Base64.getDecoder().decode(encoded)
        return String(decrypt(decoded), Charsets.UTF_8)
    }

    internal interface SecretKeyProvider {
        fun getOrCreateKey(): SecretKey
    }

    private class AndroidKeyStoreProvider(
        private val keyAlias: String
    ) : SecretKeyProvider {
        override fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
            if (existingKey != null) {
                return existingKey
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "${AppBranding.KEYSTORE_ALIAS_PREFIX}master"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
