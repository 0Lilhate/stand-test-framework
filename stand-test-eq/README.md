# stand-test-eq

Stage 2 of [EQ data provisioning](../docs/plans/eq-data-provisioning-implementation-plan.md)
is implemented for the confirmed showcases organisation slice. This module contains the lazy Java DSL
for `eq.seed`, immutable account requests, a deterministic showcases ID generator, fail-closed typed
parsing for the selected `showcases` or `gateway` registry entry, and an executing `EqStepExecutor`
registered both through SPI (`META-INF/services`) and through the Spring Boot starter. The IFT
consumer pilot (task 2.3) remains to be done.

Stage 3 (`gateway`, organisations) is implemented offline: the `ONU → OKC → YFT2 → KP1` chain,
response-contract validation, the write-allowed gate, the cash account per currency, the
`(base-url, unit)` serialization queue, INN/DUL generation, the unit-phase precondition with a TTL
cache, and the per-step journal. Its acceptance (`UlDiscountSchemeCancelTest` green on test) is
**blocked by the external gates G0-EQ, G0-TEST and G0-JT400**: no write to a real gateway, no
unit-phase read against a real AS/400 and no jt400 dependency has been performed. The `YFT2`/`KP1`
response contracts are unconfirmed (OQ-5), so the default response policy ends those operations
`BROKEN` rather than treating an unverified body as success.

```java
GenericStep seed = EqSeed.organisation("client")
        .account(EqAccount.type("CA")
                .currency("RUR")
                .topUp(new BigDecimal("100000"))
                .servicePackage("PU_NWA"))
        .build();
```

`EqSeed.individual(alias)` places `servicePackage` on the client instead of an account.
The step always carries a logical `backend` alias (`eq` by default). It does not contain
the backend URL or credentials. `allowApproximation(EqAttribute...)` records explicit opt-in, which
`prepare` enforces through the capability matrix before any IO.

The builder creates only a `GenericStep`. It performs no network or file IO. The DSL accepts
optional account attributes. The showcases executor fills absent name, account type, currency,
and top-up from the selected backend's `defaults`; name, type and currency must be present after
that merge. A defaulted name uses the registry's `organisation.name-prefix` plus the generated PIN.
PINs and account numbers are intentionally absent from the DSL; `EqIdGenerator` derives them
from `testRunId` and the one-based scenario step ordinal.

For a successful showcases organisation seed, the executor publishes `${alias.pin}`,
`${alias.account}` (the first account), `${alias.account.<i>}` for every account, and
`${alias.deal.<i>}` for accounts with a package. It does not publish `${alias.inn}`.
The showcases v1 record has no confirmed balance field, so `.topUp(...)` needs explicit
`allowApproximation(EqAttribute.TOP_UP)` or `prepare` refuses the step before IO.
On a partially completed chain, `EqSeedException` names the failing operation and carries a TEXT
snapshot of that step's operation journal plus confirmed PIN/account values in its message and
`eq.confirmed.*` diagnostics. The snapshot reaches a failing step because `EqSeedException` implements
`FailureAttachments`; `failureAttachments()` renders only allowlisted fields (option, sequence,
classified status, duration, confirmed identifiers) and never a raw request/response body, header,
params, personal name, INN, DUL or credential. A successful step attaches the same snapshot through
`StepResult.attachments()`. Variables are published only after the entire chain and any configured
visibility probe succeed.

Every successful seed also appends one JSON line per created client to the whole-suite journal
`eq-seeded.jsonl` under the directory named by the JVM system property `stand.test.eq.artifacts.dir`
(default `build/stand-test/eq`). It is append-only and written under a file lock, so parallel classes
never interleave a line. The journal is a maintenance artefact of the suite — it records environment,
`testRunId`, PIN, accounts, INN and time so operatives can find and reap EQ test clients — and is
deliberately **not** an Allure attachment: it keeps changing during a parallel run and lives outside
`RunArtifacts.directory`, so a reporting sink would correctly refuse it. Each `eq.seed` attaches its
own immutable TEXT snapshot instead.

An optional `visibility.probe` on the selected backend polls a whitelisted service with GET.
Its `path`, `query`, and `expect-body.equals` may use `{seed.pin}` and `{seed.account}`.
Both `expect-status` and the JSONPath body value must match. `timeout` and `poll-interval`
are registry values; the default HTTP caller caps each probe request by the remaining wait.
A timeout is an `EqSeedException` with `VISIBILITY_TIMEOUT`, attempt/status diagnostics and
confirmed seed identifiers. Without `visibility`, the showcases seed completes without polling.

The module now depends on the stage-1 shared HTTP transport and await engine. Remaining work is the
IFT consumer pilot (`UlDiscountSchemeCancelTest`, gate-free) and the `gateway`/individual acceptance
on the test stand (gated on G0-EQ/G0-TEST/G0-JT400/G0-FL).

## gateway backend (stage 3, offline-complete)

`eq-backends.<alias>.kind: gateway` selects the TAKSA gateway. `prepare` enforces, before any IO:
`write-allowed: true` (otherwise `EQ_WRITE_NOT_ALLOWED:…`), a resolvable unit/branch/cash account per
account currency, and — when `unit-phase` is declared — that the unit is in an allowed phase. The
phase is read through jt400 (loaded reflectively; absent jt400 is a clear error, never a
`NoClassDefFoundError`) with a TTL cache. The reader reproduces the reference library's sequence —
`LIBL <unit>` and `CALL PGM(UAA37R)`, then a `ProgramCall` of `ALFAINSTAL/MONUNTSTS` reading the 4-char
phase from the program's output parameter — and, unlike the library, validates the unit and the user
name before any CL interpolation so a crafted value cannot inject CL; the password is never
interpolated (SEC-05, Г-9). The exact call composition is provisional pending phase-0 item 0.6.

**Ref fields accept a reference OR a value.** `base-url-ref`, `unit-phase.system-ref`, `.username-ref`
and `.password-ref` hold either a bare env-var NAME (resolved lazily) or a value. On the Spring surface
Spring collapses `${VAR:default}` before the SDK sees the section, so `base-url-ref: ${EQ_GATEWAY_URL:http://…}`
arrives holding the URL and `system-ref: ${EQ_AS400_SYSTEM:alfamosu}` holding the system name — both are
used verbatim. A bare NAME that is not set also falls back to the text as a value (`unit: {ref: K68}`
yields `K68`), which is why the twin spelling is safe on both surfaces. The SDK-internal `literal://`
marker is still refused fail-closed. The trade-off is the consumer's: a value written here lives in the
configuration, so a secret should stay a bare `*-ref` name.

The organisation chain is `ONU → OKC → YFT2 → KP1`. Each response is validated; the two chains were
confirmed live on 2026-09-30 (G0-EQ/G0-FL), so `ONU`/`ONF` issue a PIN, `OKC` an account, and
`YFT2`/`KP1`/`VAD`/`SPU` an empty object `{}`.
A write operation is never retried: a timeout is `TIMEOUT_UNKNOWN` with the `testRunId` marker and the
confirmed identifiers. Calls to one `(base-url, unit)` pair are serialized in-JVM by `GatewayQueue`;
the visibility probe runs after the slot is released. `individual(...)` is refused before IO until the
`ONF`/`VAD`/`SPU` contract is confirmed (G0-FL, stage 4).

### Appendix Г (AC-6) and the transport cause

`AppendixGAcceptanceTest` pins every defect of the reference library `aiagents-taksa-starter:0.2.0`
(Г-1…Г-13) that the gateway backend must not reproduce: `GZCTP` carries the organisation type, not the
account type (Г-1); the organisation chain never issues `SPU` (Г-2); `YFT2`/`VAD` are validated, not
assumed successful (Г-3); the cash account follows the currency (Г-4); a partial failure reports the
confirmed client (Г-5); DUL numbers stay unique within one millisecond (Г-6); the configured response
timeout always reaches the transport and is never `null` (Г-7/Г-8); jt400 absence is a clear error and
never a `NoClassDefFoundError` (Г-9); the transport cause is preserved (Г-10); there is no
`@ComponentScan` and no bundled `application.yml` (Г-11); only Jackson 3 serialises (Г-12); and the
gateway address can only arrive as a registry reference (Г-13).

A transport failure keeps its original cause in the chain (Г-10, NFR-04): `GatewayClient` wraps the
`RestClientException` as `EqSeedException("TIMEOUT_UNKNOWN", …, cause)`. The cause is stored but never
rendered into the message, diagnostics or attachments, so a raw transport detail cannot leak through
the `BROKEN` report. jt400 is declared `compileOnly` here and constrained by the BOM (`net.sf.jt400:jt400`),
so a consumer adds it as `testRuntimeOnly` and a build without it still compiles and publishes (NFR-03).
