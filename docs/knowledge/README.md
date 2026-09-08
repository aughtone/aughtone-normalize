# Aughtone Normalize Knowledge Base

The project's durable knowledge: why the canonical forms are shaped the way they are, what the modules promise, and how to work on them. Anything with a status that will someday be "done" is a tracker issue instead — see [WORKFLOW.md](../../WORKFLOW.md).

Start with [How This Documentation Works](../README.md) if you are new to the layout. Each section below explains what belongs in it.

- **[Architecture Decision Records](decisions/README.md)** — hard-to-reverse choices, and why the alternatives lost.
- **[Specifications](specifications/README.md)** — how the suite is built and the standards it is held to.
- **[Research](research/README.md)** — investigations and designs still being worked out.
- **[Developer Guides](guides/README.md)** — how to build, extend and release this project.

The governing constraint runs through all of it: **the same input must produce the same canonical bytes, on any platform, at any time.** A consumer hashes a canonical form and discards the original, so a one-byte difference is an undetectable, unrecoverable miss. Read [Normalization Suite Structure](specifications/SPEC-0001-normalization-suite.md) before changing anything that can reach a canonical string.
