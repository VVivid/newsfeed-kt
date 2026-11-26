package dev.vvivid.newsfeed.auth

import android.util.AtomicFile
import dev.vvivid.newsfeed.net.ServerJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Keeps the session between launches in one app-private file, replaced atomically on every save:
 * the server's configuration as plain JSON, which holds nothing secret, and the tokens encrypted by
 * [cipher]. After signing out only the last server's origin remains, to offer it again.
 *
 * Every method reads or writes the disk: call them off the main thread.
 *
 * @param file resolved on first use, off the main thread, because finding an app directory may
 *   create it.
 */
class SessionStore(
    file: () -> File,
    private val cipher: TokenCipher,
) {
    private val atomicFile by lazy { AtomicFile(file()) }

    /** What was saved. */
    sealed interface Saved {
        data class SignedIn(
            val server: ServerConfiguration,
            val tokens: Tokens,
        ) : Saved

        /** [lastServerOrigin] is the server last signed in to, unless the user chose another one. */
        data class SignedOut(
            val lastServerOrigin: String?,
        ) : Saved
    }

    /**
     * The saved session. A file that cannot be read or decrypted, for example after the Keystore key
     * was lost, counts as signed out: the user signs in again.
     */
    fun read(): Saved {
        val file =
            try {
                ServerJson.decodeFromString(SessionFile.serializer(), atomicFile.readFully().decodeToString())
            } catch (e: IOException) {
                // Usually FileNotFoundException: nobody has signed in on this phone yet.
                return Saved.SignedOut(lastServerOrigin = null)
            } catch (e: IllegalArgumentException) {
                // Not the JSON this class writes (SerializationException is one).
                return Saved.SignedOut(lastServerOrigin = null)
            }
        val server = file.server
        val encryptedTokens = file.encryptedTokens
        if (server == null || encryptedTokens == null) return Saved.SignedOut(file.lastServerOrigin)
        val tokens =
            try {
                val plaintext = cipher.decrypt(Base64.getDecoder().decode(encryptedTokens))
                ServerJson.decodeFromString(Tokens.serializer(), plaintext.decodeToString())
            } catch (e: GeneralSecurityException) {
                return Saved.SignedOut(server.origin)
            } catch (e: RuntimeException) {
                // Not base64 or not the tokens' JSON (IllegalArgumentException), or a Keystore failure:
                // devices report some, such as a lost key, as runtime exceptions. Signing in again is
                // the way out; failing here would fail every launch.
                return Saved.SignedOut(server.origin)
            }
        return Saved.SignedIn(server, tokens)
    }

    /** Throws [IOException] when the file cannot be written or the tokens cannot be encrypted. */
    fun saveSignedIn(
        server: ServerConfiguration,
        tokens: Tokens,
    ) {
        val plaintext = ServerJson.encodeToString(Tokens.serializer(), tokens).encodeToByteArray()
        val ciphertext =
            try {
                cipher.encrypt(plaintext)
            } catch (e: GeneralSecurityException) {
                throw IOException("The tokens could not be encrypted", e)
            } catch (e: RuntimeException) {
                // Some Keystore failures arrive as runtime exceptions, such as ProviderException.
                throw IOException("The tokens could not be encrypted", e)
            }
        write(SessionFile(server, Base64.getEncoder().encodeToString(ciphertext), lastServerOrigin = server.origin))
    }

    /** Forgets the session and its tokens; keeps [lastServerOrigin] to offer it again, if not null. */
    fun saveSignedOut(lastServerOrigin: String?) {
        write(SessionFile(server = null, encryptedTokens = null, lastServerOrigin = lastServerOrigin))
    }

    private fun write(content: SessionFile) {
        val bytes = ServerJson.encodeToString(SessionFile.serializer(), content).encodeToByteArray()
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (e: IOException) {
            atomicFile.failWrite(stream)
            throw e
        }
    }

    @Serializable
    private class SessionFile(
        val server: ServerConfiguration? = null,
        /** Base64 of [TokenCipher.encrypt] applied to the JSON of [Tokens]. */
        @SerialName("encrypted_tokens") val encryptedTokens: String? = null,
        @SerialName("last_server_origin") val lastServerOrigin: String? = null,
    )
}
