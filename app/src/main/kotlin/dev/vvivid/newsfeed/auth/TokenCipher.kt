package dev.vvivid.newsfeed.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the session's tokens before they are written to storage. */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] when [ciphertext] was not made by [encrypt] with the same key. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that the Android Keystore creates and keeps: the key never leaves the
 * Keystore, and an app backup or a copied file cannot be decrypted elsewhere. The output is a version
 * byte, the random 12-byte IV and the ciphertext with its authentication tag. The associated data
 * binds every ciphertext to this purpose and format.
 */
class KeystoreTokenCipher(
    private val keyAlias: String = "newsfeed-session-tokens",
) : TokenCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore chooses a random IV for every encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ASSOCIATED_DATA)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV size" }
        return byteArrayOf(FORMAT_VERSION) + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size <= 1 + IV_BYTES || ciphertext[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("Unknown token format")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, ciphertext, 1, IV_BYTES))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(ciphertext, 1 + IV_BYTES, ciphertext.size - 1 - IV_BYTES)
    }

    /** Synchronized so that two first uses cannot both create the key, one replacing the other. */
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey ?: createKey()
    }

    private fun createKey(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val FORMAT_VERSION: Byte = 1
        val ASSOCIATED_DATA = "newsfeed session tokens v1".toByteArray()
    }
}
