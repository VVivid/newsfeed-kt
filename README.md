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
and the [Kotlin and Android practices](docs/KOTLIN_BEST_PRACTICES.md).

### Build

You need JDK 17 and the Android SDK with platform 36 (`platforms;android-36`) and
build tools 36.0.0. Point Gradle at the SDK with the `ANDROID_HOME` environment
variable or a `local.properties` file in the project root, which Git ignores:

```properties
sdk.dir=/path/to/Android/sdk
```

The Gradle wrapper downloads the pinned Gradle version on first use.

```sh
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew spotlessApply    # format Kotlin sources and Gradle scripts
./gradlew spotlessCheck lint testDebugUnitTest assembleDebug    # the checks CI runs
```

Release builds (`./gradlew assembleRelease`) are minified with R8 and unsigned;
signing configuration stays outside this repository.

## Security

This is a public repository. Never commit credentials, signing keys, real server
addresses or personal data. See [SECURITY.md](SECURITY.md) for reporting
vulnerabilities.

## License

[MIT](LICENSE)

Headings use [Source Serif 4](https://github.com/adobe-fonts/source-serif) by Adobe,
bundled unmodified from Google Fonts as `app/src/main/res/font/source_serif_4.ttf`
under the SIL Open Font License 1.1. Its license ships inside the app as
[`app/src/main/assets/licenses/SourceSerif4-OFL.txt`](app/src/main/assets/licenses/SourceSerif4-OFL.txt).
