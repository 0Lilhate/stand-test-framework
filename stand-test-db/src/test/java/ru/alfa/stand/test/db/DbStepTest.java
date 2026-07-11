package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

class DbStepTest {

    @Test
    @DisplayName("query builds a db.query GenericStep with captures and bind params")
    void queryBuildsStep() {
        ScenarioStep step = DbStep.query("mainDb")
                .sql("SELECT status FROM test_data.orders WHERE id = :id")
                .param("id", "${entityId}")
                .capture("status", "status")
                .build();

        assertThat(step.type()).isEqualTo("db.query");
        GenericStep generic = (GenericStep) step;
        assertThat(generic.parameters()).containsEntry(DbStepParameters.DATASOURCE, "mainDb");
        assertThat(generic.parameters()).containsEntry(DbStepParameters.SQL, "SELECT status FROM test_data.orders WHERE id = :id");
        assertThat(DbStepParameters.bindValues(generic.parameters())).containsEntry("id", "${entityId}");
        assertThat(DbStepParameters.captures(generic.parameters())).containsExactly(new DbCapture("status", "status"));
    }

    @Test
    @DisplayName("expectEventually builds a db.expectEventually GenericStep carrying the expected value and timeouts")
    void expectBuildsStep() {
        ScenarioStep step = DbStep.expectEventually("mainDb")
                .sql("SELECT status FROM test_data.orders WHERE id = :id")
                .param("id", "x")
                .expectValue("DONE")
                .withinSeconds(5)
                .build();

        assertThat(step.type()).isEqualTo("db.expectEventually");
        GenericStep generic = (GenericStep) step;
        assertThat(generic.parameters()).containsEntry(DbStepParameters.EXPECTED_VALUE, "DONE");
        assertThat(generic.parameters()).containsEntry(DbStepParameters.TIMEOUT_MILLIS, 5_000L);
    }

    @Test
    @DisplayName("cleanup appends nothing itself but stores the whereTestRunId column")
    void cleanupStoresMarkerColumn() {
        ScenarioStep step = DbStep.cleanup("mainDb")
                .sql("DELETE FROM test_data.orders")
                .whereTestRunId("test_run_id")
                .build();

        assertThat(step.type()).isEqualTo("db.cleanup");
        GenericStep generic = (GenericStep) step;
        assertThat(generic.parameters()).containsEntry(DbStepParameters.WHERE_TEST_RUN_ID_COLUMN, "test_run_id");
    }

    @Test
    @DisplayName("an explicit id overrides the derived one")
    void explicitId() {
        ScenarioStep step = DbStep.seed("mainDb").id("seed-order").sql("INSERT INTO test_data.orders(id) VALUES (:id)").param("id", "1").build();

        assertThat(step.id()).isEqualTo("seed-order");
    }

    @Test
    @DisplayName("setting both sql and sqlFromResource is rejected")
    void sqlAndResourceMutuallyExclusive() {
        assertThatThrownBy(() -> DbStep.query("mainDb").sql("SELECT 1").sqlFromResource("x.sql").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not both");
    }

    @Test
    @DisplayName("a step without any SQL is rejected")
    void sqlRequired() {
        assertThatThrownBy(() -> DbStep.query("mainDb").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires sql");
    }

    @Test
    @DisplayName("capture is only valid on db.query")
    void captureOnlyOnQuery() {
        assertThatThrownBy(() -> DbStep.seed("mainDb").sql("INSERT INTO test_data.orders(id) VALUES (:id)").param("id", "1").capture("x", "x").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db.query");
    }

    @Test
    @DisplayName("expectValue is only valid on db.expectEventually and is required there")
    void expectValueRules() {
        assertThatThrownBy(() -> DbStep.query("mainDb").sql("SELECT 1").expectValue("X").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db.expectEventually");
        assertThatThrownBy(() -> DbStep.expectEventually("mainDb").sql("SELECT 1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires expectValue");
    }

    @Test
    @DisplayName("within / pollInterval are only valid on db.expectEventually")
    void timeoutsOnlyOnExpect() {
        assertThatThrownBy(() -> DbStep.query("mainDb").sql("SELECT 1").withinSeconds(1).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db.expectEventually");
    }

    @Test
    @DisplayName("db.cleanup requires a whereTestRunId marker")
    void cleanupRequiresMarker() {
        assertThatThrownBy(() -> DbStep.cleanup("mainDb").sql("DELETE FROM test_data.orders").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereTestRunId");
    }

    @Test
    @DisplayName("a non-identifier whereTestRunId column is rejected at build time (injection guard)")
    void whereTestRunIdMustBeIdentifier() {
        assertThatThrownBy(() -> DbStep.cleanup("mainDb").sql("DELETE FROM test_data.orders").whereTestRunId("1=1; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain identifier");
    }

    @Test
    @DisplayName("a null bind value is rejected")
    void nullParamRejected() {
        assertThatThrownBy(() -> DbStep.query("mainDb").param("id", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("params default to an empty map when none are set")
    void emptyParams() {
        ScenarioStep step = DbStep.query("mainDb").sql("SELECT 1").build();
        GenericStep generic = (GenericStep) step;

        assertThat(generic.parameters()).containsEntry(DbStepParameters.PARAMS, Map.of());
    }

    @Test
    @DisplayName("whereTestRunId is rejected on db.query (a read needs no testRunId predicate)")
    void whereTestRunIdRejectedOnQuery() {
        assertThatThrownBy(() -> DbStep.query("mainDb").sql("SELECT status FROM test_data.orders").whereTestRunId("test_run_id").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db.seed/db.cleanup");
    }

    @Test
    @DisplayName("whereTestRunId is rejected on db.expectEventually")
    void whereTestRunIdRejectedOnExpect() {
        assertThatThrownBy(() -> DbStep.expectEventually("mainDb").sql("SELECT status FROM test_data.orders").expectValue("DONE").whereTestRunId("test_run_id").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db.seed/db.cleanup");
    }

    @Test
    @DisplayName("whereTestRunId is allowed on db.seed (an UPDATE scoped to the run)")
    void whereTestRunIdAllowedOnSeed() {
        ScenarioStep step = DbStep.seed("mainDb").sql("UPDATE test_data.orders SET status = 'X'").whereTestRunId("test_run_id").build();

        GenericStep generic = (GenericStep) step;
        assertThat(generic.parameters()).containsEntry(DbStepParameters.WHERE_TEST_RUN_ID_COLUMN, "test_run_id");
    }

    @Test
    @DisplayName("taggedByTestRunId records the seed tag column and rejects a non-identifier")
    void taggedByTestRunIdOnSeed() {
        ScenarioStep step = DbStep.seed("mainDb")
                .sql("INSERT INTO test_data.orders(id, test_run_id) VALUES (:id, :testRunId)")
                .taggedByTestRunId("test_run_id")
                .param("id", "1")
                .build();

        assertThat(((GenericStep) step).parameters()).containsEntry(DbStepParameters.SEED_TEST_RUN_ID_COLUMN, "test_run_id");
        assertThatThrownBy(() -> DbStep.seed("mainDb").sql("INSERT INTO test_data.orders(id) VALUES (:id)").taggedByTestRunId("a; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain identifier");
    }

    @Test
    @DisplayName("taggedByTestRunId is rejected on a non-seed step")
    void taggedByTestRunIdRejectedOnNonSeed() {
        assertThatThrownBy(() -> DbStep.query("mainDb").sql("SELECT 1 FROM test_data.orders").taggedByTestRunId("test_run_id").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("applies to db.seed");
    }
}
