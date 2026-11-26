package dev.vvivid.newsfeed.auth

import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class IdTokenTest {
    private val issuer = "https://auth.example.com/realms/test"
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val issuedAt = now.epochSecond - 5
    private val expiresAt = now.epochSecond + 295

    @Test
    fun `a token for this client and sign-in is accepted`() {
        check(claims())
        check(claims(audience = """["newsfeed-android","account"]"""))
        // Clocks may differ a little between the phone and the issuer.
        check(claims(), now = now.plusSeconds(60 * 9))
    }

    @Test
    fun `a token from another issuer is refused`() {
        assertRefused(claims(issuerClaim = "https://auth.example.com/realms/other"))
    }

    @Test
    fun `a token for another client is refused`() {
        assertRefused(claims(audience = "\"another-client\""))
        assertRefused(claims(authorizedParty = "another-client"))
    }

    @Test
    fun `a token for another sign-in is refused`() {
        assertRefused(claims(nonce = "another-nonce"))
        assertRefused(claims(nonce = null))
    }

    @Test
    fun `an expired or old token is refused`() {
        assertRefused(claims(), now = Instant.ofEpochSecond(expiresAt).plusSeconds(60 * 11))
        assertRefused(claims(), now = Instant.ofEpochSecond(issuedAt).minusSeconds(60 * 11))
        assertRefused(claims(issuedAt = null))
    }

    @Test
    fun `times beyond any date are refused or accepted, never a crash`() {
        // Regression: these used to throw DateTimeException or ArithmeticException.
        assertRefused(claims(issuedAt = Long.MAX_VALUE.toString()))
        assertRefused(claims(issuedAt = Long.MIN_VALUE.toString()))
        assertRefused(claims(expiresAt = Long.MIN_VALUE.toString()))
        assertRefused(claims(issuedAt = "1e300"))
        // An expiry far in the future is harmless: the token is used once, right away.
        check(claims(expiresAt = Long.MAX_VALUE.toString()))
        check(claims(expiresAt = "1e300"))
    }

    @Test
    fun `times are JSON numbers of seconds, possibly with a fraction`() {
        check(claims(issuedAt = "${now.epochSecond - 5}.75", expiresAt = "${now.epochSecond + 295}.5"))
        assertRefused(claims(issuedAt = "\"${now.epochSecond}\""))
        assertRefused(claims(expiresAt = "null"))
    }

    @Test
    fun `something that is not a JWT is refused`() {
        assertRefused(idToken = "not-a-token")
        assertRefused(idToken = "a.%%%.c")
        assertRefused(idToken = syntheticJwt("[1, 2, 3]"))
    }

    private fun claims(
        issuerClaim: String = issuer,
        audience: String = "\"newsfeed-android\"",
        authorizedParty: String? = "newsfeed-android",
        nonce: String? = "test-nonce",
        issuedAt: String? = this.issuedAt.toString(),
        expiresAt: String = this.expiresAt.toString(),
    ): String {
        // issuedAt and expiresAt are JSON values, so that tests can also pass other kinds of value.
        val fields =
            listOfNotNull(
                "\"iss\":\"$issuerClaim\"",
                "\"sub\":\"test-subject\"",
                "\"aud\":$audience",
                "\"exp\":$expiresAt",
                authorizedParty?.let { "\"azp\":\"$it\"" },
                nonce?.let { "\"nonce\":\"$it\"" },
                issuedAt?.let { "\"iat\":$it" },
            )
        return syntheticJwt(fields.joinToString(",", prefix = "{", postfix = "}"))
    }

    private fun check(
        idToken: String,
        now: Instant = this.now,
    ) = checkIdToken(idToken, issuer, clientId = "newsfeed-android", nonce = "test-nonce", now = now)

    private fun assertRefused(
        idToken: String,
        now: Instant = this.now,
    ) {
        assertThrows(InvalidIdTokenException::class.java) { check(idToken, now) }
    }
}
