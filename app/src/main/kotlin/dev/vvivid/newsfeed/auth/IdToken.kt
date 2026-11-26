package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.ServerJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Base64
import kotlin.math.floor

/** The ID token from a sign-in was not acceptable; the message says which check failed. */
class InvalidIdTokenException(
    message: String,
) : Exception(message)

/**
 * Checks the claims of the ID token that a sign-in's code exchange returned, as OpenID Connect Core
 * 1.0 section 3.1.3.7 asks: issued by [issuer], for [clientId], not expired, issued recently, and
 * carrying the [nonce] of this sign-in's authorization request, which ties it to this sign-in.
 *
 * The signature is not checked. The token came straight from the issuer's token endpoint over HTTPS,
 * which that section accepts instead, and AppAuth's own check does the same.
 */
internal fun checkIdToken(
    idToken: String,
    issuer: String,
    clientId: String,
    nonce: String,
    now: Instant,
) {
    val claims = decodeClaims(idToken)
    if (claims.string("iss") != issuer) throw InvalidIdTokenException("The ID token is from another issuer")

    val audience =
        when (val aud = claims["aud"]) {
            is JsonPrimitive -> listOfNotNull(aud.stringOrNull())
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.stringOrNull() }
            else -> emptyList()
        }
    if (clientId !in audience) throw InvalidIdTokenException("The ID token is for another client")
    val authorizedParty = claims.string("azp")
    if (authorizedParty != null && authorizedParty != clientId) throw InvalidIdTokenException("The ID token was issued to another client")

    // In whole seconds since 1970. The claims may hold any number, so they are only compared with
    // values near now, which cannot overflow.
    val nowSeconds = now.epochSecond
    val expiresAt = claims.numericDate("exp") ?: throw InvalidIdTokenException("The ID token has no expiry")
    if (expiresAt < nowSeconds - ALLOWED_CLOCK_SKEW_SECONDS) throw InvalidIdTokenException("The ID token has expired")
    val issuedAt = claims.numericDate("iat") ?: throw InvalidIdTokenException("The ID token has no issue time")
    if (issuedAt !in (nowSeconds - ALLOWED_CLOCK_SKEW_SECONDS)..(nowSeconds + ALLOWED_CLOCK_SKEW_SECONDS)) {
        throw InvalidIdTokenException("The ID token was not issued just now")
    }

    if (claims.string("nonce") != nonce) throw InvalidIdTokenException("The ID token belongs to another sign-in")
}

/**
 * How far the phone's clock may be from the issuer's: ten minutes. AppAuth allows the same for the
 * issue time; the token arrives directly from the issuer, so the time checks only catch stale tokens.
 */
private const val ALLOWED_CLOCK_SKEW_SECONDS = 10L * 60

/** The claims of a compact JWS: the middle part, base64url-encoded JSON. */
private fun decodeClaims(idToken: String): JsonObject {
    val parts = idToken.split('.')
    if (parts.size != 3) throw InvalidIdTokenException("The ID token is not a signed JWT")
    return try {
        val payload = Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
        ServerJson.parseToJsonElement(payload).jsonObject
    } catch (e: IllegalArgumentException) {
        // Invalid base64url, invalid JSON (SerializationException) or a payload that is not an object.
        throw InvalidIdTokenException("The ID token's claims cannot be read")
    }
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.stringOrNull()

/**
 * A NumericDate claim (RFC 7519): seconds since 1970 as a JSON number. A fraction is dropped, and a
 * number beyond the range of Long counts as the nearest end of it.
 */
private fun JsonObject.numericDate(name: String): Long? {
    val value = get(name) as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.longOrNull ?: value.doubleOrNull?.takeIf { it.isFinite() }?.let { floor(it).toLong() }
}

private fun JsonPrimitive.stringOrNull(): String? = if (isString) content else null
