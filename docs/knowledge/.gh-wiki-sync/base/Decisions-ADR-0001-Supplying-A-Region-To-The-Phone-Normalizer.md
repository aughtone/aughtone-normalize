# Supplying a region to the phone normalizer

ADR-0001 · 2026-09-08 · Status: accepted
Keywords: E.164 needs a country code, national format phone number, default region, which country does 555-1234 belong to, why not a third parameter, phone policy identity, guessed country code, normalizePhone signature

## Context

E.164 output requires a region for any number given in national format — `555-1234` has no meaning without one — but the suite's contract is `normalizeX(value, policy)` and has no slot for side input. A consumer of the email normalizer raised the requirement that a **missing region must be refused, never defaulted**: a guessed country code does not fail loudly, it produces a valid-looking token for a *different number*, and once the value is a token the input has been discarded, so there is nothing left to check it against.

The wider constraint is [SPEC-0001](Specifications-SPEC-0001-Normalization-Suite)'s central promise: the policy identity stored beside a derived value must fully determine the canonical bytes. Anything that changes the output and is not part of the identity breaks that.

Feeds from [RAD-0001](Research-RAD-0001-Identifier-And-Text-Normalization), which left this open.

## Decision

**Two policies, and neither ever guesses.**

- **`PhonePolicy.E164`** — id `phone.e164`. For input that already carries its own country code. National-format input is refused with a typed failure, not defaulted.
- **`PhonePolicy.e164For(region)`** — id `phone.e164+region.<REGION>`, e.g. `phone.e164+region.CA`. For national-format input, interpreted against the region the caller named deliberately.

The region is part of the policy `id`, so it travels with every derived token. The two-argument contract `normalizeX(value, policy)` is unchanged.

**Rejected: a third parameter** (`normalizePhone(value, policy, region)`). It reads more naturally and it breaks the thing the suite exists to guarantee — the region would not be recorded in the policy identity, so two tokens carrying the same `policyId` could have come from different regions and different bytes. The identity would no longer explain the output.

**Rejected: accepting E.164-format input only.** Correct but incomplete: most real input is national format, and refusing it wholesale pushes the hardest part of the problem onto every caller. `E164` preserves this behaviour for callers who want it, as one of the two policies rather than as the only one.

## Consequences

**Easier.** The contract stays uniform across every normalizer in the suite, so this sets a usable precedent for any future normalizer needing side input: put it in the policy, not in the call. Each region is self-identifying and reproducible. A caller who has only E.164 input never has to think about regions at all.

**Harder.** Policy identities multiply — one per region actually used. That is a cost in the abstract and close to free in practice, since `id` is a derived string and policies are cheap objects.

**What this gives up, and it needs stating loudly.** `phone.e164` and `phone.e164+region.CA` produce *identical bytes* for input already in E.164 form, but they carry **different policy identities**. Matching is scoped by identity, so two consumers using different policies will not match each other even where the canonical strings agree.

**So consumers who must interoperate have to agree on the same policy constant, not merely on the same output format.** This is the same class of failure as the `email.canonical` / `email.byte-stable` mismatch caught before publication, and it is reachable here without anyone making a mistake — two teams can each choose reasonably and still not match. Any future guidance for phone consumers must name the specific policy, never "use E.164".

A hardening option exists if this bites: refuse to mint `e164For(region)` where the region is unnecessary, or collapse both to one identity. Neither is being adopted now — the first is a runtime check on a compile-time concern, and the second reintroduces the unrecorded-region problem this decision exists to avoid.
