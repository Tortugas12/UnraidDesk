package com.unraiddesk.app.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeystoreCryptoManagerTest {

    private class InMemoryAes256KeyProvider : KeystoreCryptoManager.SecretKeyProvider {
        private val secretKey: SecretKey = KeyGenerator.getInstance("AES").apply {
            init(256)
        }.generateKey()

        override fun getOrCreateKey(): SecretKey = secretKey
    }

    @Test
    fun encryptAndDecryptRoundTripsStringsAndBinaryPayloads() {
        val manager = KeystoreCryptoManager(InMemoryAes256KeyProvider())

        val samples = listOf(
            "",
            "unraid-scoped-api-key-12345",
            "p@ssw0rd!🔐✨UnraidDesk"
        )
        samples.forEach { plaintext ->
            val encrypted = manager.encryptString(plaintext)
            val decrypted = manager.decryptString(encrypted)
            assertEquals(plaintext, decrypted)
        }

        val bytes = byteArrayOf(0x00, 0x01, 0x7F, -128, 0x42)
        val encryptedBytes = manager.encrypt(bytes)
        assertArrayEquals(bytes, manager.decrypt(encryptedBytes))
    }

    @Test
    fun encryptProducesUniqueIvOnEveryCall() {
        val manager = KeystoreCryptoManager(InMemoryAes256KeyProvider())
        val first = manager.encryptString("same-secret")
        val second = manager.encryptString("same-secret")
        assertFalse("Ciphertext should differ due to unique random IV", first == second)
    }

    @Test(expected = AEADBadTagException::class)
    fun decryptRejectsTamperedCiphertext() {
        val manager = KeystoreCryptoManager(InMemoryAes256KeyProvider())
        val encrypted = manager.encrypt("sensitive-token".toByteArray(Charsets.UTF_8))
        encrypted[encrypted.lastIndex] = (encrypted[encrypted.lastIndex].toInt() xor 0xFF).toByte()
        manager.decrypt(encrypted)
    }
}
