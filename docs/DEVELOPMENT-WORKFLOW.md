# Development workflow

This methodology applies to all work in this repository. Read the
[agent instructions](../AGENTS.md) first; they hold the public-repository and
protection rules. The repository-local
[Build -> Translate -> Review skill](../.agents/skills/build-translate-review/SKILL.md)
owns coding-stage instructions and agent settings; do not substitute a personal
installation.

## Agree before implementing

Inspect code, documentation, Git state and matching unfinished local tasks.
Present a plan containing:

- Goal, expected behavior and exclusions.
- Ordered checkpoints with reviewable outcomes, dependencies, exit criteria and
  validation commands.
- Server contract assumptions and any work needed outside this repository.
  Server, identity-provider and infrastructure changes are separate tasks with
  their own authorization.

Obtain the user's agreement once before implementation. An explicit instruction
to implement an agreed plan satisfies this gate. Resolve routine implementation
choices independently. Seek renewed agreement for material changes to scope,
behavior or acceptance criteria.

## Branches and worktrees

Read-only investigation may use the current checkout. Before edits for a new
task, including documentation, create a dedicated task branch and worktree based
on verified current `origin/main`, and record the base SHA.

Branch names use `<type>/<area>/<task>` with lowercase hyphenated slugs:

| Part | Values |
| --- | --- |
| Type | `feature`, `bugfix`, `docs`, `refactor`, `chore`, `release` |
| Area | `app`, `auth`, `news`, `reader`, `settings`, `ci`, `build`, or `repo` for repository-wide work |
| Task | Concise outcome, optionally prefixed by a real issue number; never invent one |

Examples: `feature/news/swipe-archive`, `feature/settings/change-server`,
`chore/ci/pin-actions`.

Prefer `git worktree add -b <branch> <path-outside-checkout> origin/main`.
Synchronize by fetching and merging `origin/main` into the task branch. Do not
rebase, amend, reset or force-push published work.

**Never push anything to any remote without the user's explicit permission for
that push.** Permission must cover the remote and refs. Commit, PR creation,
merge and release actions each require the corresponding user instruction.
Deliver through a PR to `main`; the user controls merging. Never push directly to
`main`.

The only exception was the initial bootstrap commit of the empty repository,
pushed to `main` with the user's explicit permission before protection rules
existed.

## Local checkpoint records

Use `.development/<task>/CHECKPOINTS.md` and `RESUME.md`. `.development/` is
ignored at every depth; verify with `git check-ignore <path>` before creating
records. Never stage, commit or force-add them.

`CHECKPOINTS.md` records the agreed plan, agreement evidence, status (pending,
in progress, blocked, complete), exit criteria, actual validation, stage evidence
and unresolved work. `RESUME.md` records current state, affected paths, commits,
failures, the first incomplete checkpoint and one next action. Keep secrets,
tokens, real hostnames and personal data out of these records too.

## Complete each checkpoint

For each coding checkpoint run the full skill sequence:

1. **Builder** implements the smallest complete agreed outcome with tests.
2. **Translation** independently improves readability while preserving behavior.
3. **Final validation** runs formatting, lint, unit tests and the debug build on
   the final code.
4. **Independent Review** by a fresh read-only reviewer returns exactly
   `Approved` or `Denied`.

Advance only when exit criteria pass and Review returns `Approved`. On `Denied`,
relay findings faithfully and stop for the user's instruction.

## Validation

Standard local checks (available once the Gradle project exists):

```sh
./gradlew spotlessCheck lint testDebugUnitTest assembleDebug
```

Add instrumented or emulator checks when a checkpoint changes navigation, sign-in,
Custom Tabs or layout. Compilation does not verify layout, sign-in or browser
behavior; record emulator and physical-device evidence separately. Use only
synthetic test data and placeholder hosts.

## Pause, resume and clean up

At meaningful pauses update both records. Resume by comparing source and Git
state with the records and continue the first incomplete checkpoint. After the
whole task passes acceptance and the final handoff is delivered, delete only that
task's `.development/<task>/` directory. Before removing a worktree or local
branch, confirm the PR is merged and nothing local is still needed.
