# Kotlin and Android practices

Project defaults for a small native Android reader that talks to one
user-configured AgentNewsfeed server. Keep the code easy to read and the
interface responsive. Revisit a rule when a concrete requirement warrants it;
avoid speculative infrastructure.

## Readable Kotlin

- Follow the [official Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html):
  four spaces, descriptive names, `UpperCamelCase` types and composables,
  `lowerCamelCase` functions and properties.
- Prefer `val`, immutable data classes and read-only collection interfaces. Keep
  mutable state private.
- Use nullability deliberately. Avoid `!!`, unchecked casts, deeply nested scope
  functions and clever expressions that hide decisions.
- Model distinct states with sealed types and exhaustive `when` expressions:
  loading, content, empty, offline/stale, signed out, permission missing, error.
- Name values by meaning with units visible: `scrollPercent`, `readSeconds`,
  `expectedRevision`.
- Comment on constraints and reasons, not syntax. Formatting is mechanical through
  Spotless with ktlint.

## Small architecture

Follow [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
at the scale of this app:

- One app module. Packages by feature as they appear: `onboarding`, `auth`,
  `news`, `reader`, `browser`, `settings`, plus `ui.theme` and `data`.
- Composables render state and emit actions. A screen ViewModel owns loading and
  state transitions. A repository owns server calls and local storage.
- Expose read-only `StateFlow` and collect with `collectAsStateWithLifecycle()`.
- Keep Activity references out of ViewModels and long-lived objects.
- Use simple constructor wiring first. Add a DI framework, interfaces or use-case
  classes only when a current requirement needs them.

## Coroutines and failures

Follow [Android coroutine guidance](https://developer.android.com/kotlin/coroutines/coroutines-best-practices):

- Make repositories main-safe; network, database and Markdown parsing run off the
  main thread.
- Use structured concurrency and lifecycle-owned scopes. No `GlobalScope` or
  `runBlocking` in app flows.
- Preserve cancellation: rethrow `CancellationException` from any broad catch.
- Supersede obsolete requests so a slow response for an old tab or page never
  replaces the current one.
- Make clock and dispatcher inputs injectable where logic depends on them.
- Show recoverable failures with retry. Never convert an error into an empty
  successful list, and never substitute sample data for missing real data.

## Server, auth and security

- The server origin comes only from onboarding or Settings. Normalize it to an
  `https` origin (scheme, host, optional port; no path, query, fragment or user
  info). Debug builds may allow `http` for local emulator testing only.
- Discover sign-in settings from the server; never hard-code a server, issuer or
  realm.
- Sign in with OAuth authorization code + PKCE in a Custom Tab (AppAuth). Never
  collect passwords in the app and never use an embedded WebView for sign-in.
- Store tokens with Android Keystore-backed encryption. Refresh before expiry,
  sign out cleanly on refresh failure, and clear tokens and cached data when the
  server changes.
- Attach the access token only to requests whose origin equals the configured
  server origin. Do not follow redirects to another origin with credentials.
- Writes send `expected_revision` and a stable idempotency key per user intent.
  On a stale revision, reload and tell the user; never overwrite silently.
- Never log tokens, headers, cookies, request or response bodies, or article
  content. Treat all server content as untrusted; render Markdown without raw HTML.

## Compose and smoothness

Apply [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance/bestpractices):

- No I/O, parsing or side effects in composable bodies. Key effects to their real
  dependencies.
- Lazy lists with stable keys (article IDs). Preserve scroll position and visible
  content during refresh; show stale data clearly.
- Theme tokens live in `ui.theme`. Support light/dark, optional dynamic color,
  system insets, font scaling, TalkBack labels and reduced motion.
- Keep animation short and finite. Profile an observed problem on a release-like
  build before optimizing.

## Tests

- Unit-test decisions and failures: origin normalization, server discovery
  errors, token refresh/expiry, cursor paging, revision conflicts, idempotent
  retries, finished-reading rules, cover selection, server change clearing data.
- Use `kotlinx-coroutines-test`, fake repositories and MockWebServer with
  synthetic JSON. Hosts in tests use reserved domains such as `example.com`.
- Add a regression test with every substantive bug fix.
- Do not add generated example tests, tests for trivial getters or large UI
  snapshot suites by default. Add focused Compose UI tests for navigation and
  critical flows when they protect real behavior.
- Check layout, sign-in, Custom Tabs and gestures on an emulator or device;
  compilation cannot verify them.

## Dependencies and build

- Pin every library and plugin version in `gradle/libs.versions.toml`; use the
  Compose BOM for Compose artifacts.
- Prefer Jetpack and widely maintained libraries. Each new dependency needs a
  current requirement stated in the PR.
- Keep release signing configuration outside the repository. Release builds
  enable R8 and HTTPS-only network security config.
