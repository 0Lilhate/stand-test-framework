// Example: provision-then-trigger — the shape the imitation corpus previously never showed
// (every prior example minted the entity-under-test from ${testRunId} or declared "Preconditions:
// none"). Illustration under .claude/skills/…; in a real consumer project it goes to
// src/test/java/<base package>/ and must compile against THAT project's registry.
//
// The aliases below (crm / accounts / packages / pk / catalog-db) are ILLUSTRATIVE — they are NOT
// real registry aliases and no endpoint here is a real contract. See the ESCAPE HATCH in the javadoc.

package example.qa.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.rest.RestStep;

/**
 * Worked example: a PROVISIONING PRELUDE whose captures feed the trigger body.
 *
 * <p><b>Teaching point (verbatim):</b> preconditions that are business entities (client / account /
 * deal) are MINTED fresh each run through the system's API and referenced by CAPTURE — never
 * hardcoded as pointers to pre-provisioned stand objects, never pushed to an external DBA runbook.
 * The test owns its data lifecycle end-to-end.
 *
 * <p>Step order follows scenario-design rule 5: <b>provision → verify-preconditions → trigger →
 * asserts</b>. Every business reference in the trigger is a {@code ${capture}} or {@code ${testRunId}} —
 * NONE is a literal copied from a case. Reference/dictionary CODES (branch, service, package) are the
 * only literals, and they are constants, not instance-handles.
 *
 * <p><b>ESCAPE HATCH — do NOT invent endpoints.</b> The aliases here ({@code crm}/{@code accounts}/
 * {@code packages}/{@code pk}/{@code catalog-db}) are ILLUSTRATIVE, not real registry aliases. A real
 * provisioning step is authored ONLY against a create-endpoint that is CURATED in the registry+KB and
 * returns a capturable id. If no such create-endpoint exists AND the entity cannot be seeded on a
 * write-allowed whitelisted schema, the precondition is BLOCKING missing-information (case-analysis
 * item 7): you STOP and ask the human — you do NOT invent an endpoint and you do NOT hardcode a pointer.
 *
 * <p><b>Counter-example — the real lgot domain (why it resolves to a BLOCK, not this happy path).</b>
 * In {@code UlDiscountScheme…Test} the case supplies {@code client 1939437}, {@code pinEQ "UBVCMF"},
 * {@code accountId 50287348}, account number {@code "40802810129320000000"}. Classify each:
 * <ul>
 *   <li>client / pin / account / deal id → <b>entity-instance-handles, test-ownable</b>: they WOULD be
 *       provisioned+captured exactly as steps 1–4 below — but no create-endpoint is curated and the
 *       real provisioning transport is AS400 / TM unit macros with no SDK surface ⇒ the correct kit
 *       outcome is a BLOCKING {@code NOT-AUTOMATABLE (transport gap)}, NOT copying the literals into a
 *       fixture. That is the honest result the taxonomy now forces instead of the old hardcode.</li>
 *   <li>{@code service}/{@code ПУ}/{@code branch}/{@code currency} codes ({@code PRICEASAVE},
 *       {@code PU_NWA}, branch {@code 2932}, {@code RUR}) → <b>reference/dictionary CONSTANTS</b>,
 *       used verbatim — they name a catalog TYPE, not one stateful row.</li>
 *   <li>the approved <b>ТУ</b> matching the index versions → a <b>shared stateful catalog row</b>: the
 *       boundary rule FORBIDS the test to seed it; it is provisioned out-of-band and VERIFIED with a
 *       read-probe before the trigger (step 5 below), never hardcoded and never seeded.</li>
 * </ul>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CRM_SERVICE_URL", matches = ".+")
class ProvisionThenTriggerExampleTest {

    @Autowired
    private StandClient stand;

    @Test
    @DisplayName("Provision client+account+package fresh, verify the shared precondition, then trigger")
    void provisionThenTrigger() {
        // Dates computed in plain Java ABOVE the builder (rule 8a) — never a calendar literal.
        String dateBegin = LocalDate.now().minusDays(1).toString();
        String dateEnd = LocalDate.now().plusDays(30).toString();

        Scenario scenario = Scenario.builder("provision-then-trigger-example")
                .environment("ift")
                .tag("integration")
                // -- PROVISION 1: open a legal entity, capture its system-generated pin --
                .step(RestStep.post("crm", "/legal-entities")
                        .id("open-legal-entity")
                        .header("Content-Type", "application/json")
                        .body("{\"name\":\"OOO-${testRunId}\",\"type\":\"CA\"}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .capture("clientPin", "$.pin")
                        .build())
                // -- PROVISION 2: open an account for the captured pin; capture number + id --
                .step(RestStep.post("accounts", "/accounts")
                        .id("open-account")
                        .header("Content-Type", "application/json")
                        // "branch" is a reference/dictionary CODE — a constant, not an instance-handle.
                        .body("{\"pin\":\"${clientPin}\",\"type\":\"CA\",\"branch\":\"0000\"}")
                        .expectStatus(200)
                        .capture("accountId", "$.accountId")
                        .capture("accountNumber", "$.number")
                        .build())
                // -- PROVISION 3: fund the account (reference the captured id in the path) --
                .step(RestStep.post("accounts", "/accounts/${accountId}/deposits")
                        .id("fund-account")
                        .header("Content-Type", "application/json")
                        .body("{\"amount\":5100000,\"currency\":\"RUR\"}")
                        .expectStatus(200)
                        .build())
                // -- PROVISION 4: connect a package/subscription; capture the deal id --
                .step(RestStep.post("packages", "/subscriptions")
                        .id("connect-package")
                        .header("Content-Type", "application/json")
                        .body("{\"accountId\":\"${accountId}\",\"packageCode\":\"PKG-EXAMPLE\",\"term\":\"1M\"}")
                        .expectStatus(200)
                        .capture("dealId", "$.dealId")
                        .build())
                // -- VERIFY-PRECONDITION: the SHARED approved ТУ is present (read-probe, boundary rule).
                // A shared catalog row is provisioned OUT-OF-BAND; the test only READS it and fails fast
                // here with "precondition unmet" instead of an opaque error deep inside the trigger. --
                .step(DbStep.expectEventually("catalog-db")
                        .id("verify-approved-tu-present")
                        .sql("SELECT status FROM catalog.approved_tu WHERE service_code = :svc"
                                + " ORDER BY version DESC LIMIT 1")
                        .param("svc", "SVC-EXAMPLE")
                        .expectValue("Approved")
                        .withinSeconds(30)
                        .build())
                // -- TRIGGER: every business reference is a ${capture}/${testRunId}, NO case literal --
                .step(RestStep.post("pk", "/discount/scheme")
                        .id("send-discount-scheme")
                        .header("Content-Type", "application/json")
                        .body("{\"decisionNumber\":\"${testRunId}\",\"pinEQ\":\"${clientPin}\","
                                + "\"accountId\":\"${accountId}\",\"accountNumber\":\"${accountNumber}\","
                                + "\"dealId\":\"${dealId}\",\"dateBegin\":\"" + dateBegin + "\","
                                + "\"dateEnd\":\"" + dateEnd + "\",\"packageCode\":\"PKG-EXAMPLE\"}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .build())
                // -- ASSERT: the SUT projected the result, keyed by the run-unique decisionNumber --
                .step(DbStep.expectEventually("catalog-db")
                        .id("verify-journal")
                        .sql("SELECT status FROM catalog.journal WHERE decision_label = :dl"
                                + " ORDER BY id DESC LIMIT 1")
                        .param("dl", "${testRunId}")
                        .expectValue("AUTO")
                        .withinSeconds(60)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}
