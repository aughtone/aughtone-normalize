# How This Documentation Works

<!-- Git-native front door. It describes the documentation system rather than living inside it, so it is never published as a knowledge-base article. -->

Everything about this project lives in one of three kinds of place.

**The issue tracker owns work** — stories, bugs, ideas, anything with a status that will someday be "done". If it tracks progress it is an issue, never a document. Acceptance criteria live on the story that carries them, not in a file. [WORKFLOW.md](../WORKFLOW.md) describes how work moves; `docs/stories/` is a generated snapshot of the tracker for agents to read, is gitignored, and is never edited by hand.

**`docs/knowledge/` owns knowledge** — decisions, specifications, research, guides: anything a person would look up. Each section directory's `README.md` says what belongs in it and is the section's own front page, so read it before filing something new.

| Section | Holds |
|---|---|
| [decisions/](knowledge/decisions/) | One hard-to-reverse choice per record, and why the alternatives lost. Append-only. |
| [specifications/](knowledge/specifications/) | How things ARE — the suite's structure, the contracts, the standards the code is held to. Updated in place. |
| [research/](knowledge/research/) | Investigations: the question, the trail, what was found. Where a design is still being worked out. |
| [guides/](knowledge/guides/) | How-to — onboarding, adding a normalizer, publishing a release. |

**Plain git owns the machinery** — [AGENTS.md](../AGENTS.md) at the repo root, this file at the `docs/` root, and the generated `docs/stories/` snapshot. None of it is knowledge and none of it is published.

## This knowledge base is git-native

The documentation system supports mirroring `docs/knowledge/` two-way into a tracker knowledge base. **This repository has no wiki enabled**, so there is no mirror and no sync state: the files here are the knowledge base, and git is the whole story — diffs, blame and merges work on knowledge exactly as they do on code.

If a wiki is ever enabled on the repository, the `project-docs` skill's `gh-wiki-sync.sh` adopts this tree as-is and mirrors it. Nothing here needs to change first. Under that model the local tree still owns the layout — reorganize by moving files here, and the wiki follows.

## Adding a document

Create a `.md` file in the right section directory, opening with a `# Title` heading — the title alone, with no type prefix or identifier. Directly beneath it go two lines: an identifier line (`RAD-0001 · 2026-09-07`) and a `Keywords:` line.

Write keywords as the words someone would *search* for before they knew the answer, and include the options that were rejected — the most common lookup is for the thing the project did not choose. Keywords that merely restate the title help nobody.

Frontmatter stays minimal: `title` and `date` at most. Never `status:`, `id:` or `type:` — lifecycle state belongs to the tracker, and duplicating it here is what makes documentation drift.

## Writing one

Each document type has its own authoring skill: `to-adr` for decisions, `to-prd` for requirements, `to-rad` for investigations and proofs of concept, `to-wiring` for feature wiring rules. The `project-docs` skill decides *where* something goes and this file explains the layout; neither writes the document for you.

## Two directories that do not exist yet

`docs/design/` is reserved for UX and visual design records — mockups, exports, an iteration per directory — and is git-native because it is mostly not prose. It is a companion to `docs/knowledge/`, not a section inside it, and the `to-ux` skill owns it. This is a headless library with no UI, so it is currently empty.

`poc/` is where a spike or proof of concept goes: code written to answer a question, not to ship. The project's standards are deliberately suspended inside it, and nothing leaves it by being copied — findings become a RAD in `research/`, and the code gets rewritten as a story.
