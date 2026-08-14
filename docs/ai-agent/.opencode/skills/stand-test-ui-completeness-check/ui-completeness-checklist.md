# UI case completeness — checklist and decision record

> Output of skill `stand-test-ui-completeness-check` (stage 2 of the UI branch). Fill every section.
> The verdict at the bottom decides whether stage 3 runs at all.

## Case under review

| Field | Value |
|---|---|
| Case id | |
| Application alias | |
| Environment | |
| Role | |
| Source document | `UiCase.md` |

## Mechanical checks (fail ⇒ blocking, no judgement involved)

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | The environment named by the case exists in the registry and is **not** production | PASS/FAIL | registry key |
| 2 | The application alias is declared in that environment's `ui-applications` | PASS/FAIL | `ui-applications.<alias>` |
| 3 | The case contains no URL, host, port or JDBC string | PASS/FAIL | grep |
| 4 | The case contains no credential, token or personal data | PASS/FAIL | grep |
| 5 | If the flow is behind a sign-in: `auth.scheme` is `FORM` or `STORAGE_STATE` (not `NONE`, not `SSO`) | PASS/FAIL/n-a | `auth.scheme` |
| 6 | The role named by the case is in `auth.roles`, or the application declares no roles | PASS/FAIL/n-a | `auth.roles` |
| 7 | `auth.challenge` is `NONE`, or a `UiLoginChallengeHandler` is on the test classpath, or `STORAGE_STATE` sessions are prepared | PASS/FAIL/n-a | classpath / registry |
| 8 | Anything behind the sign-in that must be explored has a `discovery-account-ref` | PASS/FAIL/n-a | `auth.discovery-account-ref` |
| 9 | Every backend expectation resolves to a knowledge-base entry | PASS/FAIL/n-a | KB entry ids |
| 10 | Every wait in the case has a number of seconds (stated or assumed) | PASS/FAIL | |
| 11 | Every irreversible step is marked, or its reversibility is a blocking question | PASS/FAIL | |
| 12 | Every expectation names an element **and** an exact expected value | PASS/FAIL | |
| 13 | No `data-testid`, CSS selector or XPath appears in the case (locators are observed, not ordered) | PASS/FAIL | grep |
| 14 | Every entity-instance handle is classified: provisioned in-run / read-probed / blocking — none copied as a literal | PASS/FAIL | |

## A. Deferred to discovery (stage 3 answers these — do NOT ask a human)

| # | Unknown | Which screen answers it |
|---|---|---|
| 1 | the `data-testid` (if any) of the status field | «Новая заявка» after submit |

## B. Resolved from the registry / knowledge base

| # | Fact | Source |
|---|---|---|
| 1 | `auth.roles` = [...] | `ui-applications.<alias>.auth.roles` |

## C. Safe assumptions (recorded, run proceeds)

| # | Assumption | Why it is safe | Where it will be visible |
|---|---|---|---|
| 1 | | fails loudly and immediately if wrong | generation report §Assumptions |

## D. Blocking questions (a human must answer)

| # | Question | What it blocks | What the possible answers change |
|---|---|---|---|
| 1 | | | |

## Verdict

**READY | READY-WITH-ASSUMPTIONS | BLOCKED**

- `BLOCKED` ⇒ stop here. Do not open a browser, do not draft a design, do not write a test.
- Re-run this checklist after every human answer: an answer can create a new gap.

## Notes for the human

<what was deferred to discovery and why, and anything the answers above will change downstream>
