package dev.vvivid.newsfeed.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A server and its identity provider on reserved example domains, as discovery describes them. */
fun testServer(
    origin: String = "https://news.example.com",
    issuerOrigin: String = "https://auth.example.com",
): ServerConfiguration {
    val issuer = "$issuerOrigin/realms/test"
    return ServerConfiguration(
        origin = origin,
        apiResource = "$origin/api/v1",
        issuer = issuer,
        authorizationEndpoint = "$issuer/protocol/openid-connect/auth",
        tokenEndpoint = "$issuer/protocol/openid-connect/token",
        revocationEndpoint = "$issuer/protocol/openid-connect/revoke",
    )
}

/** Synthetic tokens; [accessTokenExpiresAt] defaults to far in the future. */
fun testTokens(
    accessToken: String = "test-access-1",
    refreshToken: String? = "test-refresh-1",
    accessTokenExpiresAt: Instant = Instant.parse("2100-01-01T00:00:00Z"),
): Tokens = Tokens(accessToken, accessTokenExpiresAt.toEpochMilli(), refreshToken)

/** AES-GCM with a key in memory, standing in for the Android Keystore, which tests do not have. */
class TestTokenCipher : TokenCipher {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }
}

/** A clock that stays at [now] until a test moves it. */
class TestClock(
    var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/** A synthetic, unsigned JWT whose payload is [claimsJson]. Only its claims are ever read. */
fun syntheticJwt(claimsJson: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload = encoder.encodeToString(claimsJson.toByteArray())
    return "$header.$payload.c3ludGhldGljLXNpZ25hdHVyZQ"
}
