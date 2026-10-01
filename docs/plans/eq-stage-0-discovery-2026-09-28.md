# EQ stage 0 discovery log

Date: 2026-09-28 (G0-EQ trial attempt appended later the same day). Status: **G0-EQ attempted; the canonical gateway endpoint is unreachable — the connection is closed by the server with no bytes, so the chain never ran and no record was created in EQ.** Evidence recorded; discovery closed with activation gates. **Update 2026-09-30:** the code-reachable part of stage 0 is closed — see [`eq-stage-0-discovery-completion-2026-09-30.md`](eq-stage-0-discovery-completion-2026-09-30.md) (Q-0.1 `failed: null` refuted from mocks, Q-0.2 positions mapped, Q-0.5/EQ response shapes from the library, Q-0.9 deal-id, Q-0.10/OQ-17 closed). This document separates repository evidence from
observations on a real stand. No record was submitted to EQ. One unique `ACLM` record was sent to
the user's IFT mock service during this review.
The normalized responses to five read-only IFT format requests are preserved in
[`evidence/eq-ift-formats-2026-09-28.json`](evidence/eq-ift-formats-2026-09-28.json). They contain
only format names, versions, field order, entity mapping and types; no stand address or credentials.
The responses to three bounded IFT load-list probes are preserved in
[`evidence/eq-ift-load-list-2026-09-28.json`](evidence/eq-ift-load-list-2026-09-28.json): an empty
list, which sends zero messages; an unknown business code, which fails validation before Kafka; and
one successful `ACLM` message. The generated test identifiers for the successful message are stored
outside Git in `build/stand-test/eq-stage0-2026-09-28.txt` with owner-only permissions. The POST
was issued once, without automatic retries.

## Evidence obtained from local repositories

| Plan item | Finding | Evidence and limit |
|---|---|---|
| 0.1 / OQ-1 | IFT returned HTTP 200 with `status: OK`, `sentCount: 0`, `timestamp` for an empty list and HTTP 200 with `status: OK`, `sentCount: 1`, `timestamp` for one unique `ACLM` message. An unknown code rejected before Kafka returned HTTP 400 with `error: BAD_REQUEST`, `status`, `message`, `path`, `details: null`. The live `/v3/api-docs` documents `ShowcaseBatchResponse` with `status`, `sentCount`, `timestamp`; its 500 response schema does not describe the observed 400. The checked-in service maps Kafka send failure to 503 `KAFKA_UNAVAILABLE`. | Captured responses in the evidence JSON; `showcase-loader/showcase-loader-app/src/main/java/ru/alfabank/app/service/showcase/ShowcaseService.java:65`, `.../controller/controlleradvice/DefaultAdvice.java:20`, `.../src/test/java/ru/alfabank/app/controller/ShowcaseLoaderApiImplTest.java:176,206`. The intermittent `failed: null` failure was not observed. The adjacent AsciiDoc uses an older `/showcase/loadList` spelling and conflicts with the current implementation; it must not override the live contract. |
| 0.1 delivery limit | `ShowcaseService` increments `sentCount` after calling `KafkaSender.send`, but that sender calls `KafkaTemplate.send(record)` without awaiting its returned future. Therefore `sentCount: 1` confirms an accepted call in this process, not broker acknowledgment or downstream visibility. | `showcase-loader/showcase-loader-app/src/main/java/ru/alfabank/app/kafka/KafkaSender.java:14–24`. This is an inference from source, not a captured Kafka failure. It is relevant when classifying `failed: null`. |
| 0.2 / OQ-3 | Read-only `GET /showcases/formats/{code}` on the IFT `showcases` alias returned HTTP 200 for `ACLM` v1, `ACLN` v2, and `UACM` v1 on 2026-09-28. Their field order matches the checked-in cache: zero-based `ACLM[1] = Location`, `[3] = ClientTypeId`, `[4] = INN`, `[5] = ResidentCountryId`; `ACLN[3] = SurName`, `[4] = GivenName`, `[5] = MiddleName`; `UACM[3] = Suffix`, `[5] = CountryId`, `[8] = ClosingDate`, `[9] = IsEnableFeeSU1`, `[10] = IsEnableFeeSU2`, `[11] = AccountGroupId`, `[12] = IsHoldList`, `[13] = BalAcc2`. `UACM` v1 has no balance field. | IFT GET responses and `showcase-loader/showcase-loader-app/src/test/resources/file/cache/eq_taksa_{client,account}.json`. `ACLM[4]` is an INN field in the active format, but the repeated 12-digit value in tests still needs a product decision before publishing `${alias.inn}` from showcases. This does not prove that no other showcase code carries balance. |
| OQ-2 candidate | The IFT format endpoint also returned HTTP 200 for `FCLM` v1 (`BusinessKey`, `Location`, `CommonTypeId`, `ClientTypeId`, `INN`, `ResidentCountryId`, `ConnectBankDate`) and `FCLN` v1 (`BusinessKey`, `Location`, `SurName`, `GivenName`, `MiddleName`, `BirthDate`). | These active formats suggest physical-client records, but do not prove the complete provisioning sequence. Keep OQ-2 open pending owner confirmation or a safe stand observation. |
| 0.5 canonical configuration | The user confirmed on 2026-09-28 that `taxa_api_ai/src/main/resources/application.yml` is the canonical configuration for the EQ trial, including its gateway address and `K68`. A read-only GET to that configuration's `/api` returned HTTP 200 with an empty body. Its `TAKSA_USER_ID` and `TAKSA_USER_PASSWORD` placeholders have no values in this process. | The user's explicit correction supersedes the earlier assumption that these settings point to the wrong contour. Network reachability is confirmed, but credentials must be supplied through the existing environment references before a write trial. Do not replace the address or unit with guessed values. |
| 0.5 trial scope | `TestClass.testGenerateTestUser2()` creates **two** organisations and is unsuitable. `AiAgentsTaksaStarterTests.testGenerateTestOrganisation()` calls `generateTestOrganisation()` **once** and is the existing single-chain method to select with Gradle `--tests`. | `taxa_api_ai/src/test/java/ru/alfabank/DRB_JT400_EQ/tests/AiAgentsTaksaStarterTests.java:41`. It uses the canonical application config without editing the consumer repository. |
| 0.3 / R-12 / AS-6 | An isolated copy of `taksa-service-autotests` changed only the account opening date and deal start date in `UlDiscountSchemeCancelTest` from the old offsets to `LocalDate.now().atStartOfDay()`. `./gradlew test --tests 'ru.alfa.taksa.lgot.UlDiscountSchemeCancelTest' --console=plain` passed on IFT: one test, 3.9 s, `BUILD SUCCESSFUL in 13s`. | Full run log: `/private/tmp/eq-stage0-ift-cancel.log` (owner-only permissions). Source repo was not edited. This confirms today's dates for **this** CANCEL case, not every migrated case. |
| Consent for showcase probe | The user identified `showcase-loader` as their mock service and authorized the IFT showcase probe in the conversation on 2026-09-28. One positive `ACLM` request was sent. | The user subsequently confirmed the `taxa_api_ai` gateway address and unit as canonical for the EQ trial. Runtime credentials and the remaining test-contour parameters were not supplied. |
| 0.12 availability | The user has designated the demo `application.yml` as canonical for the EQ trial. This process has no `TAKSA_USER_ID` or `TAKSA_USER_PASSWORD` environment variable; the demo has no local run configuration providing them. The consumer KB currently has `knowledge-base/environments/ift.yml` and no `test.yml`. | Read-only file listing and variable-presence checks (values were not printed). The gateway/unit and default branch/cash/INN settings for this trial are supplied by the confirmed YAML; the runtime credential source remains missing. Other test-contour service aliases for the later consumer pilot are still unverified. |
| OQ-10, OQ-14 | A conservative phase-2 working assumption is Java DSL only and one immutable TEXT attachment per `eq.seed` step. | The implementation plan's 2.2 and 2.2.9 use precisely these surfaces. Product acceptance is still needed before closing the BRD questions; no YAML or nested Allure work is implied by this assumption. |
| 0.13 research | IBM's JTOpen project identifies `net.sf.jt400:jt400` as its Maven artifact and states that JTOpen is governed by IBM Public License 1.0. | [IBM/JTOpen](https://github.com/IBM/JTOpen), [distribution POM](https://github.com/IBM/JTOpen/blob/main/pom-dist.xml). This establishes the published license identification, **not** internal legal acceptance. |

## G0-EQ trial attempt (canonical gateway unreachable)

On the same date, after the user recorded EQ-owner consent and supplied the runtime credential
variable names (`TAKSA_USER_ID`, `TAKSA_USER_PASSWORD`), the single reviewed organisation chain was
attempted through the canonical demo project `taxa_api_ai`, targeting the canonical gateway
configuration (`application.yml`: `http://10.232.128.12:4567/api`, unit `K68`). **The chain did not
run.** The gateway address accepted the TCP connection (port 4567 open) but closed it without
sending a single byte, for every HTTP request:

```
TCP connect:  10.232.128.12:4567  → connected in ~10 ms
GET  /api      → empty reply (curl 52), 0 bytes
POST /api      → "Connection reset" (library: I/O error on POST request)
raw socket     → connect OK, then recv returns 0 bytes (server closed, no HTTP response)
HTTPS, /, /api/, /actuator/health, /swagger-ui.html → all closed with 0 bytes
```

Because no HTTP response was returned, there is **no** `ONU`/`OKC`/`YFT2`/`KP1` response to capture,
**no** PIN was issued, and **no** record was created in EQ — the failure is at the transport level,
below HTTP. The request body the run would have sent is recorded here for completeness and is
consistent with the library contract: `{"unit":"K68","option":"ONU","params":{"GZFNM1":"OOO
'Предприятие TEST'","GZINN":"<generated 10-digit>","GZCUN":"OOO 'Предприятие TEST'","GZCTP":"CA"}}`
— note `GZCTP` carries the ACCOUNT type `CA` (defect Г-1), which this SDK deliberately does not
reproduce. Credentials never appeared in the log and are not recorded here.

This is an environment/stand condition, not an SDK defect and not a configuration error: the network
route is fine (other DEV/IFT hosts such as `tksdev3mock1` resolve and accept connections), but this
gateway endpoint returns nothing. G0-EQ therefore remains **attempted, not passed**: the EQ-owner
consent and credentials side is satisfied; the "one controlled chain + captured response shapes"
side is blocked on the gateway not answering.

## Work still requiring stand evidence or an owner decision

| Plan item | Required evidence or decision | Dependency |
|---|---|---|
| 0.1 | The live response shapes for zero-send success, one positive-count send, and input-validation failure are captured. Still capture the actual intermittent `failed: null` failure; identify whether that failure is HTTP, response body, Kafka delivery, or a consumer wrapper. | An actual failure occurrence or existing sanitized log. Blocks precise `ShowcasesBackend` error classification. |
| 0.2 | Field meanings and versions for `ACLM`/`ACLN`/`UACM` are confirmed by IFT read-only format responses. Still determine whether a separate showcase record carries balance and whether the repeated 12-digit INN in tests is semantically valid for organisations. | Format/EQ owner; do not infer balance support from `UACM` alone. |
| 0.3 | Completed for `UlDiscountSchemeCancelTest` in an isolated copy: today's account/deal dates passed on IFT. | Keep this result as the phase-2 pilot date baseline; other cases remain untested. |
| 0.4 | The user authorized probing their `showcase-loader` mock, confirmed the canonical EQ demo configuration, and recorded EQ-owner consent for one organisation chain with runtime credentials. | Consent prerequisite for 0.5 is satisfied. |
| 0.5 | Attempted via the canonical demo and the single-call `AiAgentsTaksaStarterTests.testGenerateTestOrganisation()`. **Blocked:** the gateway at the canonical address closes every connection without an HTTP response, so no `ONU`/`OKC`/`YFT2`/`KP1` shape and no PIN could be captured and no EQ record was created. Re-run once the gateway answers; do not select the two-call `TestClass.testGenerateTestUser2()` method. A duplicate-INN probe remains a separate write attempt. | A responding gateway at the canonical address. |
| 0.6–0.9 | Read unit phase and working values; measure visibility latency and choose probe; determine permitted `testRunId` marking field; determine deal-id retrieval. | test stand and owning teams. |
| 0.10–0.12 | Determine TKS LTR-ID matching; verify catalog codes and `CA → 40702`; obtain verified test gateway, unit, branch, currency cash accounts, INN region/tax offices, and non-secret service aliases plus credential-reference names. | TKS/EQ/catalog owners and test stand. Do not copy IFT endpoints or inline credentials. |
| 0.13 | Record legal/security acceptance of the jt400 license for this repository and consumer. | License owner; the repository cannot grant this acceptance. |
| 0.14 | Confirm Deploy/Cache rights to a local Artifactory repository for `ru/alfa/stand/test/**` with the publishing account. | Artifactory owner; prior 403 is not evidence of current rights. |

## Exit check

Discovery is administratively closed by the explicit assumptions and activation gates in
[`eq-stage-0-closure-2026-09-28.md`](eq-stage-0-closure-2026-09-28.md). This is not evidence that the
EQ trial or the remaining stand checks succeeded. G0-EQ was attempted with consent and credentials
and is blocked only by the canonical gateway closing every connection without a response; the gateway
pilot remains gated.
