# newsfeed-kt

A native Android reader for a self-hosted
[AgentNewsfeed](https://github.com/VVivid/vvivid-workspace/tree/main/AgentNewsfeed)
server, built with Kotlin, Jetpack Compose and Material 3.

> **Status:** early development. The app is not usable yet.

## Planned features

- **Your own server.** No server address is built in. Enter your server's address
  when you first open the app; change it later in Settings.
- **Sign in** with your server's identity provider in the system browser
  (OAuth 2.0 authorization code with PKCE). The app never sees your password.
- **News** with Active, Archive and Trash, an article reader for saved Markdown,
  and source thumbnails or generated fallback covers.
- **Open original** in an in-app Chrome Custom Tab, with finished-reading
  tracking.
- Light and dark newsprint themes, optional Material You colors.

## Requirements

- An AgentNewsfeed server that supports native-app sign-in (token-based API
  access). Server-side support is tracked separately in the server's repository.
- Android 8.0 (API 26) or newer.

## Development

Read [AGENTS.md](AGENTS.md), the [development workflow](docs/DEVELOPMENT-WORKFLOW.md)
and the [Kotlin and Android practices](docs/KOTLIN_BEST_PRACTICES.md). Build
instructions will be added with the Gradle project.

## Security

This is a public repository. Never commit credentials, signing keys, real server
addresses or personal data. See [SECURITY.md](SECURITY.md) for reporting
vulnerabilities.
