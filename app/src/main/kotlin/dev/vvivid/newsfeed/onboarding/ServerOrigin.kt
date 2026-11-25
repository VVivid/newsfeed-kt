package dev.vvivid.newsfeed.onboarding

/** Result of [normalizeServerOrigin]. */
sealed interface ServerOriginResult {
    /**
     * [origin] is `https://host` or `https://host:port`: lowercase, with no path, and without
     * port 443 because that is the https default.
     */
    data class Valid(
        val origin: String,
    ) : ServerOriginResult

    data class Invalid(
        val problem: ServerAddressProblem,
    ) : ServerOriginResult
}

/** Why a server address cannot be used. */
enum class ServerAddressProblem {
    /** Nothing was entered. */
    Empty,

    /** The address starts with `http://`; the app connects only over HTTPS. */
    HttpNotAllowed,

    /** The address names another scheme, such as `ftp://`. */
    UnsupportedScheme,

    /** The address contains a user name or password (`user@host`). */
    UserInfoNotAllowed,

    /** The host is not a DNS name with at least one dot, nor a dotted IPv4 address. */
    InvalidHost,

    /** The host is a bracketed IPv6 literal such as `[2001:db8::1]`, which this version rejects. */
    Ipv6NotSupported,

    /** The port is empty, not a decimal number, or outside 1..65535. */
    InvalidPort,
}

/**
 * Turns what the user typed into the HTTPS origin of their server, without any network access.
 *
 * Accepted: an ASCII host name with at least one dot (`news.example.com`) or a dotted-decimal IPv4
 * address, optionally preceded by `https://` (any case) and followed by `:port`. Surrounding
 * whitespace is trimmed and the host is lowercased. A path, query or fragment after the host is
 * ignored, because the app only talks to the server's origin.
 *
 * Rejected, each with its own [ServerAddressProblem]: an empty address, `http://` and other
 * schemes, user names or passwords, malformed hosts or ports, and IPv6 literals. IPv6 is out of
 * scope for now; bracketed literals get a specific message, unbracketed ones are invalid hosts.
 * Internationalized names must be entered in their ASCII (`xn--`) form.
 */
fun normalizeServerOrigin(input: String): ServerOriginResult {
    val address = input.trim()
    if (address.isEmpty()) return invalid(ServerAddressProblem.Empty)

    // Without a scheme, https is implied.
    val schemePrefix = SCHEME_PREFIX.find(address)?.value
    val withoutScheme =
        when (schemePrefix?.lowercase()) {
            null -> address
            "https://" -> address.removePrefix(schemePrefix)
            "http://" -> return invalid(ServerAddressProblem.HttpNotAllowed)
            else -> return invalid(ServerAddressProblem.UnsupportedScheme)
        }
    if (withoutScheme.isEmpty()) return invalid(ServerAddressProblem.Empty)

    // The authority ends where a path, query or fragment starts; those parts are dropped.
    val authority = withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    if ('@' in authority) return invalid(ServerAddressProblem.UserInfoNotAllowed)
    if (authority.startsWith('[')) return invalid(ServerAddressProblem.Ipv6NotSupported)

    val host = authority.substringBefore(':')
    if (!isValidHost(host)) return invalid(ServerAddressProblem.InvalidHost)

    val port =
        if (':' in authority) {
            parsePort(authority.substringAfter(':')) ?: return invalid(ServerAddressProblem.InvalidPort)
        } else {
            HTTPS_DEFAULT_PORT
        }

    val normalizedHost = host.lowercase()
    val origin = if (port == HTTPS_DEFAULT_PORT) "https://$normalizedHost" else "https://$normalizedHost:$port"
    return ServerOriginResult.Valid(origin)
}

private fun invalid(problem: ServerAddressProblem) = ServerOriginResult.Invalid(problem)

/** A scheme such as `https://` at the start of the address (RFC 3986 scheme syntax). */
private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_HOST_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63

private fun isValidHost(host: String): Boolean {
    if (host.length > MAX_HOST_LENGTH) return false
    val labels = host.split('.')
    // Single-label names such as "localhost" cannot carry a publicly trusted HTTPS certificate.
    if (labels.size < 2) return false
    val topLabel = labels.last()
    return when {
        // A host ending in a number can only be an IPv4 address, as in URL parsing.
        topLabel.isNotEmpty() && topLabel.all(::isAsciiDigit) -> isIpv4Address(labels)

        // Top-level domains start with a letter.
        else -> labels.all(::isDnsLabel) && isAsciiLetter(topLabel.first())
    }
}

/** A letter-digit-hyphen label: 1 to 63 characters, not starting or ending with a hyphen. */
private fun isDnsLabel(label: String): Boolean =
    label.length in 1..MAX_LABEL_LENGTH &&
        label.first() != '-' &&
        label.last() != '-' &&
        label.all { isAsciiLetter(it) || isAsciiDigit(it) || it == '-' }

/** Four decimal numbers 0 to 255. Leading zeros are rejected because some parsers read them as octal. */
private fun isIpv4Address(labels: List<String>): Boolean =
    labels.size == 4 &&
        labels.all { part ->
            part.length in 1..3 &&
                part.all(::isAsciiDigit) &&
                (part.length == 1 || part.first() != '0') &&
                part.toInt() <= 255
        }

/** A decimal port from 1 to 65535, or null. At most five digits, so the conversion cannot overflow. */
private fun parsePort(text: String): Int? {
    if (text.length !in 1..5 || !text.all(::isAsciiDigit)) return null
    return text.toInt().takeIf { it in 1..65535 }
}

private fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'

private fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'
