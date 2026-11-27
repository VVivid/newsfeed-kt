# Agent instructions

`newsfeed-kt` is a native Android reader for a self-hosted AgentNewsfeed server.
This repository is **public**. Everything committed, pushed, attached to a PR or
printed in CI logs is visible to everyone and stays in Git history.

## Start here

1. Read this file, the [README](README.md), the [development workflow](docs/DEVELOPMENT-WORKFLOW.md)
   and the [Kotlin and Android practices](docs/KOTLIN_BEST_PRACTICES.md).
2. Inspect current code and Git state. Resume a matching unfinished local task
   (`.development/<task>/`) before proposing new work.
3. Agree checkpoints with the user before implementation. An explicit instruction
   to implement an agreed plan is sufficient.
4. Use the repository-local [Build -> Translate -> Review skill](.agents/skills/build-translate-review/SKILL.md)
   for application features, fixes and refactors. Documentation-only and
   instruction-only changes use direct relevant checks.

## Repository protection (MUST)

- **Never push anything to any remote without the user's explicit permission for
  that push**, covering the remote and refs. This includes branches, tags and
  deletions through Git, the GitHub API, `gh` or any delegated agent. Plan
  approval, local commits, a successful review or a PR creation request do not
  imply push permission.
- **Standing permission (granted by the user on 2026-11-25):** push directly to
  `origin/main` only for work that finished its required workflow: a coding
  checkpoint after final validation and a Review verdict of `Approved`, or a
  documentation/instruction change after its direct checks. This permission
  covers committing that work too. It does not cover other branches, tags,
  releases or anything unreviewed; those still need explicit permission.
- Never force-push, delete remote branches or tags, or rewrite published history.
  Update `main` only by fast-forward from the reviewed local result.
- Tags and releases each need the corresponding user instruction.
- Never change repository settings, rulesets, branch protection, Actions
  permissions, secrets, deploy keys, webhooks, collaborators or visibility
  without an explicit request for that specific change.
- Never weaken a check to make it pass: do not disable lint rules, delete or skip
  tests, lower CI permissions requirements, or add suppressions without stating
  the reason and getting agreement.

## Public repository: secrets and private data (MUST)

Never commit, push, paste into a PR/issue, or print in CI logs:

- Credentials of any kind: tokens, OAuth client secrets, refresh/access tokens,
  passwords, session cookies, API keys, private keys, `.jks`/`.keystore` files,
  keystore passwords, signing configs, `google-services.json`, `.env` files.
- Real personal data: real article content or titles from a user's feed,
  account subjects, emails, usernames, screenshots of a signed-in app, logs or
  HTTP captures from a real server.
- Private infrastructure details: real server hostnames, internal or tailnet
  hostnames, IP addresses, Keycloak realm names, cluster names, registry
  addresses, or deployment notes. The app has **no built-in server address**;
  the user enters it during onboarding and can change it in Settings.

Use instead:

- Placeholder hosts from reserved domains only: `example.com`, `example.org`,
  `example.net`, `*.example`, `*.test`, `*.invalid` (RFC 2606/6761).
- Synthetic fixtures written for tests. Never record and commit real responses.
- Local, ignored files for anything sensitive (`local.properties`,
  `keystore.properties`, `*.jks`). Release signing material stays outside the
  repository.

Before every commit, inspect the complete staged diff (`git diff --cached`) for
the items above. If a secret was committed locally, stop and tell the user before
any push; if one was ever pushed, tell the user immediately so it can be revoked.
Deleting it in a later commit does not remove it from history.

## App rules

- The app talks only to the server the user configured. Attach access tokens
  only to requests for that exact origin; never send them to article sites,
  thumbnail hosts or redirects to other origins.
- HTTPS only in release builds. Cleartext HTTP is allowed only in debug builds
  for local emulator testing.
- Store tokens only through the Android Keystore-backed storage in the app.
  Never log tokens, authorization headers, cookies or full request/response
  bodies. Log redaction applies to debug builds too.
- Article titles, summaries, Markdown and URLs from the server are untrusted
  data. Render Markdown without raw HTML or script execution; open links in a
  Custom Tab, never in a WebView with JavaScript bridges.
- Exception agreed with the owner (2026-11-27): ```` ```mermaid ```` blocks are drawn by
  Mermaid in `reader/DiagramView.kt`, in a WebView that runs only the bundled
  `assets/mermaid/` scripts, blocks network loads, has no JavaScript bridge, no
  navigation and no file or content access, and passes the diagram source only as
  HTML-escaped text. Keep those properties; do not reuse that WebView for anything else.
- Changing the server signs out and clears all cached data from the previous
  server.
- Keep the design from the agreed mockups: Material 3 with the newsprint theme,
  News (Active, Archive, Trash), article reader, in-app browser with
  finished-reading tracking, onboarding and Settings.

## Development rules

- Follow [Kotlin and Android practices](docs/KOTLIN_BEST_PRACTICES.md).
- Name branches `<type>/<area>/<task>` and use one branch and worktree per task
  (see the [workflow](docs/DEVELOPMENT-WORKFLOW.md#branches-and-worktrees)).
- Keep task plans and handoffs in ignored `.development/<task>/`. Never stage,
  commit or force-add them.
- Pin dependency and plugin versions in `gradle/libs.versions.toml`. Add a
  dependency only for a current requirement and say why in the PR. Pin GitHub
  Actions to full commit SHAs.
- CI runs on pull requests with read-only `contents` permission and no secrets.
  Do not add workflows that run untrusted PR code with secrets or write
  permissions (`pull_request_target`, `workflow_run` with checkout of PR code).
- Preserve unrelated changes. Keep source completion, independent review,
  release and device verification distinct, and report checks actually run
  separately from code inspection.
