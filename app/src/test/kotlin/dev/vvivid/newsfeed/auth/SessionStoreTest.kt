package dev.vvivid.newsfeed.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Runs with Robolectric for Android's AtomicFile. */
@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "session.json") }
    private val cipher = TestTokenCipher()
    private val server = testServer()

    @Test
    fun `nothing saved means signed out without a server`() {
        assertEquals(SessionStore.Saved.SignedOut(lastServerOrigin = null), store().read())
    }

    @Test
    fun `a saved session comes back with its tokens`() {
        store().saveSignedIn(server, testTokens(accessToken = "test-access-1", refreshToken = "test-refresh-1"))

        val saved = store().read() as SessionStore.Saved.SignedIn

        assertEquals(server, saved.server)
        assertEquals("test-access-1", saved.tokens.accessToken)
        assertEquals("test-refresh-1", saved.tokens.refreshToken)
        assertEquals(testTokens().accessTokenExpiresAtMillis, saved.tokens.accessTokenExpiresAtMillis)
    }

    @Test
    fun `the tokens are stored encrypted`() {
        store().saveSignedIn(server, testTokens(accessToken = "test-access-1", refreshToken = "test-refresh-1"))

        val content = file.readText()
        assertFalse(content.contains("test-access-1"))
        assertFalse(content.contains("test-refresh-1"))
        assertTrue(content.contains(server.origin))
    }

    @Test
    fun `signing out forgets the session and keeps only the server's address`() {
        store().saveSignedIn(server, testTokens())

        store().saveSignedOut(lastServerOrigin = server.origin)

        assertEquals(SessionStore.Saved.SignedOut(server.origin), store().read())
        assertFalse(file.readText().contains("encrypted_tokens"))
    }

    @Test
    fun `tokens that cannot be decrypted count as signed out from that server`() {
        store().saveSignedIn(server, testTokens())

        // As after the Keystore key was lost: another key cannot read the tokens.
        val withAnotherKey = SessionStore({ file }, TestTokenCipher())

        assertEquals(SessionStore.Saved.SignedOut(server.origin), withAnotherKey.read())
    }

    @Test
    fun `a damaged file counts as signed out`() {
        file.writeText("{ not json")

        assertEquals(SessionStore.Saved.SignedOut(lastServerOrigin = null), store().read())
    }

    private fun store() = SessionStore({ file }, cipher)
}
