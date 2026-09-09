# Kotlin Multiplatform Conventions

DOC-0002 · 2026-09-07
Keywords: Dispatchers.IO not available in commonMain, iOS test compilation fails, backtick test names native, expect has no actual declaration, dependsOn broke my source sets, KLIB resolver duplicate uniquename, KT-66568

Constraints that bite in a multiplatform library and are not obvious from a single-target build. Each one here has a failure mode this project either hit or would hit; general Kotlin style is not in scope.

## Coroutine dispatchers

**Never use `Dispatchers.IO` in `commonMain`.** It does not exist on every target — it is a JVM-family dispatcher — so referencing it from common code fails to resolve on native and web. Use `Dispatchers.Default`. Where a target genuinely needs IO-specific scheduling, that belongs behind an `expect`/`actual` boundary rather than in shared code.

## Test naming on native targets

**Do not use parentheses or other special characters in backticked test function names** — spaces and underscores only. Certain native targets, iOS and Linux among them, compile test function names into symbol names and fail on the punctuation. The failure surfaces as a compilation error in the native test binary, far from the test that caused it.

This project sidesteps the problem by using plain camelCase test names, which is the simplest way to never hit it.

## Source sets

**Never call `dependsOn(...)` in a build file.** A manual `dependsOn` edge stops the default hierarchy template being applied, and the template's own source sets — `iosMain`, `webMain` and the rest — then silently stop reaching their leaf compilations.

It fails as `Expected <X> has no actual declaration`, pointing at `commonMain` rather than at the wiring that actually broke, with the real cause only in a "Default Kotlin Hierarchy Template Not Applied Correctly" warning that is easy to miss.

Prefer needing no custom source set at all: put the shared implementation in `commonMain` as an ordinary function and let each target's `actual` delegate to it in one line. That costs a few lines of boilerplate and no build configuration whatsoever. Where a genuine intermediate source set is unavoidable, declare it in `applyDefaultHierarchyTemplate { }` with `group(...)` and the `withJvm()` / `withIos()` / `withJs()` matchers rather than wiring it by hand.

## Metadata compilation module names

Both module build files carry a `metadata { compilations.all { ... moduleName = ... } }` block. It is a workaround for [KT-66568](https://youtrack.jetbrains.com/issue/KT-66568), where the KLIB resolver reports the same `uniquename` found in more than one library. Keep it on any new module in this suite, and remove it everywhere at once when the upstream issue is fixed — it is marked `XXX` in the build files for exactly that reason.

## Serialization

Nothing in this suite is `@Serializable` today. If that changes: put an explicit `@SerialName` on every member of a serializable type rather than depending on the implicit Kotlin name as the wire key, and do not write custom serializers. A property rename is a source-level refactor that silently becomes a wire-format break otherwise — which matters here more than most places, because this library's whole purpose is values that must not change shape.

## Enums

Never use an enum's `ordinal` for persistence, serialization, comparison or wire order. Members are PascalCase. Reordering an enum is a routine edit; it must not be a data migration.
