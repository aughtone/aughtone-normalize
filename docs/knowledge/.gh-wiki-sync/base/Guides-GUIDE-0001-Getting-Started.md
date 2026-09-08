# Getting Started

GUIDE-0001 · 2026-09-07
Keywords: clone and build, what JDK, run the tests, gradle check, project layout, first day, where is the code

What you need to build this project and where things are.

## Prerequisites

A JDK is the only hard requirement — the Gradle wrapper brings its own Gradle (9.4.1) and the build declares a toolchain, so no manual Gradle or Kotlin install is needed. The daemon toolchain is pinned to **JDK 21** in `gradle/gradle-daemon-jvm.properties`, and the compilation toolchain is **17** (`jvmToolchain(17)` in each module). CI runs on JDK 21.

Building the iOS targets needs Xcode and a macOS host. Everything else — jvm, js, wasmJs, linuxX64 — builds anywhere.

## Build and test

```bash
./gradlew check
```

That runs the full multiplatform test suite. On a non-macOS host the iOS targets are skipped; on macOS, `iosX64Test` is skipped on Apple silicon because it is an Intel simulator binary. Neither is a failure.

To iterate quickly on one target:

```bash
./gradlew :email:jvmTest
```

Memory settings are deliberately **not** committed: `gradle.properties` leaves `org.gradle.jvmargs` commented out so each developer can set their own in `~/.gradle/gradle.properties`. The file carries a worked example for a large machine. CI passes its own settings on the command line.

## Layout

```
common/   the shared contract every normalizer reports (io.github.aughtone.normalize:common)
email/    the byte-stable email normalizer                (io.github.aughtone.normalize:email)
docs/     documentation - see docs/README.md
```

Each module is a Kotlin Multiplatform library targeting jvm, android, iosX64, iosArm64, iosSimulatorArm64, js, wasmJs and linuxX64. All the source lives in `commonMain` — there is no platform-specific code in this suite today, and keeping it that way is a design goal rather than an accident.

## Before you change anything

The whole project exists to make one property true: **the same input produces the same canonical bytes, on any platform, at any time.** A consumer hashes the canonical output and throws the original away, so an innocuous-looking improvement to a published policy silently breaks every token already derived from it.

Read [Normalization Suite Structure](Specifications-SPEC-0001-Normalization-Suite) before touching anything that can reach a canonical string. [Kotlin Multiplatform Conventions](Specifications-SPEC-0002-Kmp-Conventions) covers the multiplatform traps that are not obvious from a single-target build.

Work is tracked as issues, not files — [WORKFLOW.md](https://github.com/aughtone/aughtone-normalize/blob/HEAD/WORKFLOW.md) explains how it moves.
