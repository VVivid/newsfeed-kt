package dev.vvivid.newsfeed.auth

/**
 * How the app identifies itself to the identity provider of the user's server. The provider has to
 * register a public client (no secret) with this ID, this exact redirect URI, and the authorization
 * code flow with PKCE S256.
 */
internal object NativeClient {
    const val ID = "newsfeed-android"

    /**
     * Where the sign-in page sends the browser back to. Its scheme is the `appAuthRedirectScheme`
     * manifest placeholder in app/build.gradle.kts, so AppAuth's redirect activity receives it.
     */
    const val REDIRECT_URI = "dev.vvivid.newsfeed:/oauth2redirect"

    /** The OpenID Connect scope for a refresh token that outlives the browser session. */
    private const val OFFLINE_ACCESS = "offline_access"

    /**
     * OpenID Connect sign-in; `offline_access`, so that the sign-in lasts as long as the provider
     * keeps offline sessions rather than ending with its browser session; and permission to read
     * articles and to change their state. A provider that does not grant `offline_access` gives an
     * ordinary session, which works the same until it ends.
     */
    const val SCOPE = "openid $OFFLINE_ACCESS ${NewsfeedScopes.READ} ${NewsfeedScopes.ARTICLES_WRITE}"
}

/** The AgentNewsfeed API's permissions. */
internal object NewsfeedScopes {
    /** Listing and reading articles and their thumbnails. */
    const val READ = "newsfeed:read"

    /** Archiving, unarchiving, trashing and restoring articles (together with [READ]). */
    const val ARTICLES_WRITE = "newsfeed:articles:write"
}
