package dev.vvivid.newsfeed.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerOriginTest {
    @Test
    fun `host name becomes an https origin`() {
        assertValid("https://news.example.com", "news.example.com")
    }

    @Test
    fun `https scheme is optional and case-insensitive`() {
        assertValid("https://news.example.com", "https://news.example.com")
        assertValid("https://news.example.com", "HTTPS://news.example.com")
    }

    @Test
    fun `host is lowercased`() {
        assertValid("https://news.example.com", "News.EXAMPLE.com")
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertValid("https://news.example.com", "  news.example.com \n")
    }

    @Test
    fun `trailing slash, path, query and fragment are ignored`() {
        assertValid("https://news.example.com", "news.example.com/")
        assertValid("https://news.example.com", "https://news.example.com/feed/?tab=archive#top")
        assertValid("https://news.example.com", "news.example.com?tab=archive")
        assertValid("https://news.example.com", "news.example.com#top")
        assertValid("https://news.example.com", "news.example.com/@someone")
    }

    @Test
    fun `explicit port is kept`() {
        assertValid("https://news.example.com:8443", "news.example.com:8443")
        assertValid("https://news.example.com:65535", "news.example.com:65535")
    }

    @Test
    fun `default https port is dropped`() {
        assertValid("https://news.example.com", "https://news.example.com:443/")
    }

    @Test
    fun `port with leading zeros is normalized`() {
        assertValid("https://news.example.com:8443", "news.example.com:08443")
    }

    @Test
    fun `IPv4 address is accepted`() {
        assertValid("https://192.0.2.1", "192.0.2.1")
        assertValid("https://192.0.2.10:8443", "https://192.0.2.10:8443/")
    }

    @Test
    fun `internationalized name in ASCII form is accepted`() {
        assertValid("https://xn--bcher-kva.example", "xn--bcher-kva.example")
    }

    @Test
    fun `longest allowed labels and host names are accepted`() {
        val label63 = "a".repeat(63)
        assertValid("https://$label63.example", "$label63.example")

        val host253 = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61)).joinToString(".")
        assertEquals(253, host253.length)
        assertValid("https://$host253", host253)
    }

    @Test
    fun `empty address is reported`() {
        assertProblem(ServerAddressProblem.Empty, "", "   ", "https://")
    }

    @Test
    fun `http is not allowed`() {
        assertProblem(ServerAddressProblem.HttpNotAllowed, "http://news.example.com", "HTTP://news.example.com:8080/")
    }

    @Test
    fun `other schemes are not supported`() {
        assertProblem(ServerAddressProblem.UnsupportedScheme, "ftp://news.example.com", "wss://news.example.com")
    }

    @Test
    fun `user name and password are not allowed`() {
        assertProblem(
            ServerAddressProblem.UserInfoNotAllowed,
            "someone@news.example.com",
            "https://someone:placeholder@news.example.com/",
        )
    }

    @Test
    fun `bracketed IPv6 literal is not supported`() {
        assertProblem(ServerAddressProblem.Ipv6NotSupported, "[2001:db8::1]", "https://[2001:db8::1]:8443/")
    }

    @Test
    fun `unbracketed IPv6 literal is an invalid host`() {
        assertProblem(ServerAddressProblem.InvalidHost, "2001:db8::1")
    }

    @Test
    fun `spaces inside the address are invalid`() {
        assertProblem(ServerAddressProblem.InvalidHost, "news example.com", "news.example.com /feed")
    }

    @Test
    fun `malformed host names are invalid`() {
        assertProblem(
            ServerAddressProblem.InvalidHost,
            "localhost",
            "news_feed.example.com",
            "-news.example.com",
            "news-.example.com",
            "news..example.com",
            ".example.com",
            "news.example.com.",
            "news.example.123",
            "news.example.1a",
            "bücher.example",
            "news.example.com\\feed",
            "news.exa%6Dple.com",
            "https:///feed",
            ":8443",
            "https:news.example.com",
        )
    }

    @Test
    fun `too long labels and host names are invalid`() {
        val label64 = "a".repeat(64)
        val host254 = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(62)).joinToString(".")
        assertEquals(254, host254.length)

        assertProblem(ServerAddressProblem.InvalidHost, "$label64.example", host254)
    }

    @Test
    fun `malformed IPv4 addresses are invalid`() {
        assertProblem(
            ServerAddressProblem.InvalidHost,
            "192.0.2.256",
            "192.0.2",
            "192.0.2.1.5",
            "192.0.2.01",
        )
    }

    @Test
    fun `malformed ports are invalid`() {
        assertProblem(
            ServerAddressProblem.InvalidPort,
            "news.example.com:",
            "news.example.com:0",
            "news.example.com:65536",
            "news.example.com:123456",
            "news.example.com:https",
            "news.example.com:-1",
            "news.example.com:80:80",
            "news.example.com: 8443",
        )
    }

    private fun assertValid(
        expectedOrigin: String,
        input: String,
    ) {
        assertEquals("input \"$input\"", ServerOriginResult.Valid(expectedOrigin), normalizeServerOrigin(input))
    }

    private fun assertProblem(
        expected: ServerAddressProblem,
        vararg inputs: String,
    ) {
        for (input in inputs) {
            assertEquals("input \"$input\"", ServerOriginResult.Invalid(expected), normalizeServerOrigin(input))
        }
    }
}
