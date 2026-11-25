# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub's
**Report a vulnerability** button on this repository's Security tab. Do not open
a public issue, and do not include real tokens, server addresses or personal data
in the report; describe the problem with placeholders.

## What this repository never contains

- Credentials, tokens, OAuth client secrets or session data.
- App signing keys, keystores or their passwords.
- Real server addresses, infrastructure details or personal data.

If you find any of these in the repository or its history, report it privately as
above so it can be revoked.

## App security model

- The app connects only to the server the user enters, over HTTPS.
- Sign-in uses OAuth 2.0 authorization code with PKCE in the system browser; the
  app never handles passwords.
- Tokens are stored with Android Keystore-backed encryption and sent only to the
  configured server origin.
- Server content is treated as untrusted and rendered without raw HTML.
