# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.0.1] - 2026-09-07

### Added
- **Byte-stable email normalizer (`:email`)**: `normalizeEmail(value, policy)` produces a deterministic, byte-level canonical string for hashing and blind tokenization. The canonical form applies only ASCII-level, Unicode-version-independent operations (trim ASCII whitespace, ASCII-lowercase, RFC 5233 `+`-subaddress handling), so its output is byte-identical on every platform and every build and never drifts when Unicode ships a new version.
- **Named, frozen policies (`EmailPolicy`)**: `EmailPolicy.ByteStableV1` is the shared canonical form for blind tokenization (strips the `+`-subaddress); `EmailPolicy.Lenient` is a loose trim + ASCII-lowercase key for display and dedupe. Each policy carries an `id` + `version` epoch that travels with any derived token, and a published policy's output is never edited in place — a rules change mints a new version.
- **`Outcome`-based result with typed, value-free errors**: normalization returns an aughtone-types `Outcome<NormalizedEmail>`; malformed input yields `Outcome.Error` carrying a typed `EmailNormalizationError` (`MissingAtSign`, `EmptyLocalPart`, `EmptyDomain`, `UnpairedSurrogate`) whose messages never echo the input, so a rejected address cannot leak into a log. A `String.normalizeEmailOrNull(policy)` convenience is also provided.
- **Shared `Normalized` contract (`:common`)**: the `Normalized` interface (`canonical`, `policyId`, `policyVersion`) is the common result shape every normalizer in the suite reports, so a derived hash can always be stored beside the policy identity that produced it.
