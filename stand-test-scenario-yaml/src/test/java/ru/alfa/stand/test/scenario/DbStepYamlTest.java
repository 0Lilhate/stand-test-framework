package ru.alfa.stand.test.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;

class DbStepYamlTest {

    private final YamlScenarioParser parser = new YamlScenarioParser();

    private static Map<String, Object> params(Scenario scenario, int index) {
        return ((GenericStep) scenario.steps().get(index)).parameters();
    }

    @Test
    @DisplayName("db.query maps sql/params and captures by column")
    void query_capturesByColumn() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - db.query:
                      datasource: mainDb
                      sql: select status from orders where id = :id
                      params:
                        id: "1"
                      capture:
                        status: status_col
                """);

        Map<String, Object> params = params(scenario, 0);
        assertThat(params).containsEntry("datasource", "mainDb").containsEntry("sql", "select status from orders where id = :id");
        assertThat(params.get("params")).isEqualTo(Map.of("id", "1"));
        assertThat(params.get("captures")).isEqualTo(List.of(Map.of("variableName", "status", "column", "status_col")));
    }

    @Test
    @DisplayName("db.expectEventually maps equals to expectedValue and durations to millis")
    void expectEventually_mapsEqualsAndDurations() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                then:
                  - db.expectEventually:
                      datasource: mainDb
                      sql: select status from orders
                      equals: SUCCESS
                      timeout: 20s
                      pollInterval: 250ms
                """);

        Map<String, Object> params = params(scenario, 0);
        assertThat(params).containsEntry("expectedValue", "SUCCESS");
        assertThat(params.get("timeoutMillis")).isEqualTo(20000L).isInstanceOf(Long.class);
        assertThat(params.get("pollIntervalMillis")).isEqualTo(250L).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("db.seed maps inline sql and params; sqlResource is the resource variant")
    void seed_mapsSqlAndParams() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - db.seed:
                      datasource: mainDb
                      sqlResource: sql/seed.sql
                      params:
                        id: "${requestId}"
                """);

        Map<String, Object> params = params(scenario, 0);
        assertThat(params).containsEntry("sqlResource", "sql/seed.sql").doesNotContainKey("sql");
        assertThat(params.get("params")).isEqualTo(Map.of("id", "${requestId}"));
    }

    @Test
    @DisplayName("db.seed may declare an optional whereTestRunId (an UPDATE/DELETE seed needs the write-guard predicate)")
    void seed_allowsOptionalWhereTestRunId() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - db.seed:
                      datasource: mainDb
                      sql: update orders set status = 'NEW' where test_run_id = :testRunId
                      whereTestRunId: test_run_id
                """);

        assertThat(params(scenario, 0)).containsEntry("whereTestRunIdColumn", "test_run_id");
    }

    @Test
    @DisplayName("db.seed maps taggedByTestRunId to the internal seed tag-column key (parallel isolation, plan §15)")
    void seed_mapsTaggedByTestRunId() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - db.seed:
                      datasource: mainDb
                      sql: INSERT INTO test_data.orders(id, test_run_id) VALUES (:id, :testRunId)
                      params:
                        id: order-1
                      taggedByTestRunId: test_run_id
                """);

        assertThat(params(scenario, 0)).containsEntry("seedTestRunIdColumn", "test_run_id");
    }

    @Test
    @DisplayName("a floating-point duration is rejected rather than silently truncated")
    void floatDuration_isRejected() {
        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                then:
                  - db.expectEventually:
                      datasource: mainDb
                      sql: select 1
                      equals: 1
                      timeout: 30.5
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("floating-point");
    }

    @Test
    @DisplayName("db.cleanup maps whereTestRunId to the internal column key")
    void cleanup_mapsWhereTestRunId() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                then:
                  - db.cleanup:
                      datasource: mainDb
                      sql: delete from orders
                      whereTestRunId: test_run_id
                """);

        assertThat(params(scenario, 0)).containsEntry("whereTestRunIdColumn", "test_run_id");
    }

    @Test
    @DisplayName("db.expectEventually requires equals; db.cleanup requires whereTestRunId")
    void db_requiredFields() {
        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                then:
                  - db.expectEventually:
                      datasource: mainDb
                      sql: select 1
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("equals");

        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                then:
                  - db.cleanup:
                      datasource: mainDb
                      sql: delete from orders
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("whereTestRunId");
    }
}
