# Project Development Guide

This document is the master instruction set for AI agents contributing to this repository.

# Working in this repository

## This repository is public

Everything committed here is world-readable and permanent — a public git
history cannot be unpublished, only added to. Write for a stranger who
found this repo, not for the maintainer.

**Never commit personal details.** Concretely, that means no real names in
prose or code comments, no personal email addresses, no machine or
hostnames, no absolute home paths, no employer, client or private project
names, no internal tracker keys or instance URLs, and nothing about the
maintainer's business, billing, tax position or working habits. Verbatim
quotes from a working conversation are the most common way all of this
leaks at once — a design discussion is full of them.

The exceptions are deliberate and narrow: the copyright line in `LICENSE`
and `NOTICE.md`, maintainer attribution in `README.md`, the GitHub handle
and its `users.noreply.github.com` address as vendor in the YouTrack app
manifest, and the published Maven coordinates used as worked examples. Do
not add to that list without being asked, and do not strip what is on it.

Authorship of the tool is fine; identity beyond it is not. A GitHub handle
is the right granularity — it is already public, and it is the name the
project is known by.

**Document the pattern, not the person.** When a design came out of
someone's specific situation — how they work, what tools they pay for, what
their client expects — the reusable content is the *pattern*: this is a
common way developers work, here is why the obvious design fails against
it, here is how this one covers it. Written that way the reasoning survives
intact and nothing traces back to an individual. If a fact only makes sense
as "the maintainer does X", it does not belong here.

This applies hardest to documents an agent generates from a conversation —
RADs, ADRs, design notes, session summaries. Those are written while the
conversation is still in context, which is exactly when quoting feels
natural and is most dangerous.

**Examples use placeholders.** `acme`, `example.com`, `PROJ-123`,
`<instance>.youtrack.cloud`, `owner/repo`. Never a real project, org or
instance, even one that happens to be public — a real name in an example
reads as a live reference and invites someone to go look.

**Check before you commit.** Grep your own additions for names, emails,
hosts, home paths and project names before proposing them. If something is
borderline, leave it out and say so — it is far cheaper to add a detail
later than to remove one from a public history.


## 2. Governance Standards

The documentation layout is described in [docs/README.md](docs/README.md); read it before filing or looking for a document. All agents MUST adhere to these specifications:

- **[Normalization Suite Structure](docs/knowledge/specifications/DOC-0001-normalization-suite.md)** — the module boundaries, the shared contract, and the policy-versioning rules. Binding on anything that can reach a canonical string.
- **[Kotlin Multiplatform Conventions](docs/knowledge/specifications/DOC-0002-kmp-conventions.md)** — the multiplatform constraints that are not obvious from a single-target build.

## 3. Core Development Principles
- **Test-Driven Development (TDD)**: Whenever feasible, write a failing test before implementation.
- **Kotlin Multiplatform**: All code must be multiplatform-first. Be mindful of source set placement (`commonMain`, `androidMain`, etc.).
- **Immutability & Safety**: Maintain data structure immutability and handle serialization (`kotlinx.serialization`) correctly.
- **Consistency**: Adhere to existing patterns; consistency outweighs personal preference.

## 4. AI Interaction Guidelines
- **Verification First**: Acceptance criteria live on the tracker story, not in a file. Read the story's `## Acceptance Criteria` checklist before implementing — it IS the scope. See [WORKFLOW.md](WORKFLOW.md).
- **Mandatory Approval**: ALWAYS present a detailed implementation plan and WAIT for explicit user approval before executing any code changes or tool calls that modify the repository state.
- **No embedded skill files.** Do not add `*.ai-skill.md`,
  `META-INF/ai-skills/` or `META-INF/agents/skills/` to this repo, and do
  not scan dependencies for them.
- **Update Docs**: File knowledge in the right section of `docs/knowledge/` — each section's `README.md` says what belongs there, and [docs/README.md](docs/README.md) explains the model. Never record work status in a document.
