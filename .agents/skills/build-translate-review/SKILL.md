---
name: build-translate-review
description: Implement application features, fixes, and refactors through sequential Builder, Translation, and independent Review agents. Use when a coding goal needs working code that future contributors can understand. Excludes read-only investigations and documentation-only changes.
---

# Build -> Translate -> Review

Deliver the user's coding goal through three sequential agents. Treat code
readability as an acceptance criterion: a future contributor must understand the
behavior without this conversation. Translation means improving the actual code
for human readers, not translating languages or merely explaining it.

Adapted from the VVivid workspace skill for this public, single-project Android
repository, with settings for both Codex and Claude Code hosts.

## Coordination and boundaries

**MUST: Never push anything to any remote without the user's explicit permission
for that push**, covering the remote and refs, through Git, APIs, other tools or
delegated agents. Plan approval, implementation, local commits, successful review
and PR creation requests do not imply push permission.

**MUST: This repository is public.** Every stage follows the secrets and private
data rules in [AGENTS.md](../../../AGENTS.md). No stage may write credentials,
real hostnames, real feed content or personal data into code, tests, fixtures,
records or reports. Review treats any such content as a blocking finding.

Read AGENTS.md, the [workflow](../../../docs/DEVELOPMENT-WORKFLOW.md), the
[Kotlin and Android practices](../../../docs/KOTLIN_BEST_PRACTICES.md) and the
existing implementation. Before implementation, present ordered checkpoint
outcomes, scope, dependencies, exit criteria and validation commands, and obtain
the user's agreement once. Seek renewed agreement for material scope or
acceptance changes; resolve routine implementation choices independently.

Before editing, create or reuse the task's dedicated branch and worktree. Supply
its path, branch and verified base SHA to every stage.

Run the complete Builder -> Translation -> final validation -> independent Review
sequence for each coding checkpoint. Advance automatically only when exit criteria
pass and Review returns `Approved`.

## Agent launch settings

Run stages sequentially with one editing stage active at a time. Launch a fresh
agent for every stage, without inheriting the coordinator's conversation. Give it
the original request, scoped plan and acceptance criteria, target directory,
applicable instructions, current diff, and validation commands and results. Do
not supply earlier approval verdicts or persuasive explanations as evidence of
correctness.

| Host | Builder | Translation | Review |
| --- | --- | --- | --- |
| Codex | `model="gpt-6.1-sol"`, `reasoning_effort="max"` | `model="gpt-6.1-sol"`, `reasoning_effort="xhigh"` | `model="gpt-6.1-sol"`, `reasoning_effort="max"` |
| Claude Code | Agent tool, `subagent_type="general-purpose"`, `model="opus"`, `effort="max"` | `subagent_type="general-purpose"`, `model="opus"`, `effort="xhigh"` | `subagent_type="Plan"` (no edit tools), `model="opus"`, `effort="max"` |

On Codex, use `fork_turns="none"` when supplying overrides and verify the
effective model and effort from host launch or runtime metadata.

On Claude Code, never use `subagent_type="fork"`, which inherits the
coordinator's context. Record each stage's agent ID and the launch settings.
Claude Code does not expose separate runtime proof of a sub-agent's model, so the
recorded launch settings are the available evidence; state that limitation in the
handoff instead of claiming runtime verification.

On either host, if independent agents are unavailable or the required settings
cannot be applied, stop and leave the workflow incomplete. Never present a
single-agent pass as independent review.

Keep the agreed plan and progress in ignored `.development/<task>/CHECKPOINTS.md`
and `RESUME.md`. The skill grants no additional permission for Git operations,
dependency changes, device or server access, or external actions.

## 1. Builder

Implement the smallest complete solution within the authorized scope, with unit
tests for its decisions and failure cases. Trace relevant entry points, state
holders, persistence and side effects before changing behavior. Prefer idiomatic
Kotlin, Jetpack libraries and established patterns already in the code.

Make domain decisions, state changes, side effects and failure handling visible.
Avoid unnecessary wrappers, speculative abstractions, duplicate implementations
and unrelated cleanup. Comments explain reasons or constraints the code cannot
express.

Return changed files, resulting behavior, important state changes and side
effects, validation evidence and unresolved issues.

## 2. Translation

Read the implementation independently as a future contributor. Identify exact
files and symbols where understanding requires hidden context. Challenge vague
names, scattered rules, unnecessary indirection, dense control flow and hidden
side effects.

Improve the actual code within scope. Every edit must have a concrete readability
benefit; clear code may pass unchanged. Preserve behavior, public contracts,
validation, authorization, token handling, idempotency, side-effect ordering and
error handling.

If a correctness issue is discovered or an edit would change semantics, return
the issue to Builder and stop Translation edits. After Builder corrects it,
Translation inspects the revised code before advancing.

Finish with one concrete normal-case trace and one failure-case trace through the
final code.

## Final validation

After Translation, run on the final code:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck lint testDebugUnitTest assembleDebug
```

Add instrumented or emulator checks when the checkpoint calls for them. Earlier
validation does not cover later edits. Failed or unavailable required validation
leaves the work incomplete; report the specific limitation.

## 3. Review

Launch a fresh, independent, read-only reviewer after final validation. Check the
implementation against the goal, acceptance criteria, scope, AGENTS.md,
Kotlin practices, security (token scope, origin checks, logging, untrusted
content), public-repository data rules, tests and validation evidence. Inspect the
code and relevant callers; earlier stage reports are context, not proof.

Return concise findings with file and symbol references and exactly one verdict:
`Approved` or `Denied`. A blocking correctness, security, scope, readability,
secret-exposure or required-validation issue results in `Denied`.

Relay the report faithfully. On `Denied`, stop and wait for the user's next
instruction; do not fix, soften or override the verdict. An authorized correction
returns through Builder, Translation, final validation and a fresh Review.

## Handoff

Report completion only after all stages finish, validation passes and Review
returns `Approved`. Provide a short reading order with file links, one
input-to-result walkthrough, one failure case, state changes and side effects,
validation results and limitations, and the reviewer report. Include each stage's
agent ID and launch or runtime settings with their evidence source. Distinguish
checks actually run from cases inspected by reading code.

## Temporary checkpoint lifecycle

Use `.development/<task>/`; verify it is ignored before creating records. Record
actual checks, failures, unresolved work, stage evidence and the next action at
meaningful pauses. Keep records while the task is incomplete, blocked, denied or
awaiting the user. After acceptance and final handoff, delete only that task's
directory. Preserve lasting information in maintained docs, never the diary.
