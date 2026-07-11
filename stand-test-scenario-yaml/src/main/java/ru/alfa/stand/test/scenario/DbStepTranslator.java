package ru.alfa.stand.test.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Translates a {@code db.query}/{@code db.expectEventually}/{@code db.seed}/{@code db.cleanup} surface node
 * into the {@code GenericStep} parameters the DB executor reads. Mirrors {@code DbStep.build()}:
 * {@code datasource}/{@code sql}(or {@code sqlResource})/{@code params} are common; {@code query} adds
 * {@code captures} (by column); {@code expectEventually} adds a required {@code equals} (expected value) and
 * optional {@code timeout}/{@code pollInterval}; {@code cleanup} requires a {@code whereTestRunId} column; a
 * {@code seed} may declare {@code taggedByTestRunId} (the reap column an INSERT tags, required at runtime by
 * the write-guard for parallel isolation) and/or {@code whereTestRunId} (for a write-scoped UPDATE/DELETE seed).
 */
final class DbStepTranslator {

    private static final Set<String> QUERY_KNOWN = Set.of("id", "datasource", "sql", "sqlResource", "params", "capture");
    private static final Set<String> EXPECT_KNOWN = Set.of("id", "datasource", "sql", "sqlResource", "params", "equals", "timeout", "pollInterval");
    private static final Set<String> SEED_KNOWN = Set.of("id", "datasource", "sql", "sqlResource", "params", "whereTestRunId", "taggedByTestRunId");
    private static final Set<String> CLEANUP_KNOWN = Set.of("id", "datasource", "sql", "sqlResource", "params", "whereTestRunId");

    private DbStepTranslator() {
    }

    static Map<String, Object> params(String type, Map<String, Object> fields, String location) {
        Set<String> known = switch (type) {
            case "db.query" -> QUERY_KNOWN;
            case "db.expectEventually" -> EXPECT_KNOWN;
            case "db.seed" -> SEED_KNOWN;
            case "db.cleanup" -> CLEANUP_KNOWN;
            default -> throw new StandTestException("Unknown DB step type '" + type + "' at " + location + " (db.query/expectEventually/seed/cleanup)");
        };
        SurfaceValues.checkKnownKeys(fields, known, location);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(YamlStepKeys.DATASOURCE, SurfaceValues.requireString(fields, "datasource", location));
        SurfaceValues.putInlineOrResource(params, fields, "sql", "sqlResource",
                YamlStepKeys.SQL, YamlStepKeys.SQL_RESOURCE, true, location);
        params.put(YamlStepKeys.PARAMS, SurfaceValues.objectMap(fields.get("params"), location + ".params"));
        switch (type) {
            case "db.query" -> params.put(YamlStepKeys.CAPTURES, fields.containsKey("capture")
                    ? SurfaceValues.captures(fields.get("capture"), YamlStepKeys.COLUMN, location + ".capture") : List.of());
            case "db.expectEventually" -> {
                Object expected = fields.get("equals");
                if (expected == null) {
                    throw new StandTestException("Field 'equals' at " + location + " is required for db.expectEventually and must not be null");
                }
                params.put(YamlStepKeys.EXPECTED_VALUE, expected);
                if (fields.containsKey("timeout")) {
                    params.put(YamlStepKeys.TIMEOUT_MILLIS, SurfaceValues.durationMillis(fields.get("timeout"), location + ".timeout"));
                }
                if (fields.containsKey("pollInterval")) {
                    params.put(YamlStepKeys.POLL_INTERVAL_MILLIS, SurfaceValues.durationMillis(fields.get("pollInterval"), location + ".pollInterval"));
                }
            }
            case "db.cleanup" -> params.put(YamlStepKeys.WHERE_TEST_RUN_ID_COLUMN, SurfaceValues.requireString(fields, "whereTestRunId", location));
            case "db.seed" -> {
                // taggedByTestRunId names the reap column an INSERT tags with :testRunId (the same column the
                // paired cleanup filters); optional here but enforced at runtime by the write-guard for an
                // INSERT (parallel isolation, plan §15) — mirrors DbStep.taggedByTestRunId(...).
                String tagColumn = SurfaceValues.optionalString(fields, "taggedByTestRunId", location);
                if (tagColumn != null) {
                    params.put(YamlStepKeys.SEED_TEST_RUN_ID_COLUMN, tagColumn);
                }
                // whereTestRunId is OPTIONAL for a seed (an INSERT needs no predicate); a write-scoped
                // UPDATE/DELETE seed may declare it, mirroring DbStep which allows it on seed and cleanup.
                String whereColumn = SurfaceValues.optionalString(fields, "whereTestRunId", location);
                if (whereColumn != null) {
                    params.put(YamlStepKeys.WHERE_TEST_RUN_ID_COLUMN, whereColumn);
                }
            }
            default -> {
                // unreachable: the known-set switch above already rejected any other type.
            }
        }
        return params;
    }
}
