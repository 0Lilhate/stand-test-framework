package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the P1 guardrail holes closed after the hard review: protocol-relative paths
 * (G2), SQL sleep/side-effect functions (G1), effectively-unbounded timeouts (G3), and fixture path
 * traversal (G5). Each hostile document must be rejected; the matching benign document must pass.
 */
class GuardrailHoleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchema SCHEMA =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(AiSchemaResources.scenarioSchemaJson());

    private static Set<ValidationMessage> validate(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return SCHEMA.validate(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse test document", e);
        }
    }

    private static String restStep(String field, String value) {
        return "{\"id\":\"a\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.get\",\"service\":\"svc\","
                + field + ":" + value + "}]}";
    }

    private static String restPostBody(String fixtureValue) {
        return "{\"id\":\"a\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.post\",\"service\":\"svc\","
                + "\"path\":\"/a\",\"body\":{\"fixture\":\"" + fixtureValue + "\"}}]}";
    }

    private static String dbQuery(String query) {
        return "{\"id\":\"a\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"db.expectEventually\","
                + "\"datasource\":\"d\",\"timeout\":\"5s\",\"query\":\"" + query + "\",\"expect\":{\"rowExists\":true}}]}";
    }

    private static String kafkaExpectTimeout(String timeout) {
        return "{\"id\":\"a\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"kafka.expect\","
                + "\"topic\":\"t\",\"timeout\":\"" + timeout + "\",\"assert\":[{\"path\":\"$.x\",\"equals\":1}]}]}";
    }

    @Test
    @DisplayName("G2: protocol-relative path //host is rejected, real relative paths pass")
    void g2_protocolRelativePath() {
        assertThat(validate(restStep("\"path\"", "\"//evil.com/x\""))).isNotEmpty();
        assertThat(validate(restStep("\"path\"", "\"/api/requests\""))).isEmpty();
        assertThat(validate(restStep("\"path\"", "\"/\""))).isEmpty();
    }

    @Test
    @DisplayName("G1: SQL sleep/side-effect functions are rejected, plain SELECT passes")
    void g1_sqlSleep() {
        assertThat(validate(dbQuery("SELECT pg_sleep(30)"))).isNotEmpty();
        assertThat(validate(dbQuery("SELECT SLEEP(5)"))).isNotEmpty();
        assertThat(validate(dbQuery("SELECT status FROM requests WHERE id = 1"))).isEmpty();
    }

    @Test
    @DisplayName("G3: effectively-unbounded timeout is rejected, sane timeouts pass")
    void g3_unboundedTimeout() {
        assertThat(validate(kafkaExpectTimeout("9999999m"))).isNotEmpty();
        assertThat(validate(kafkaExpectTimeout("0s"))).isNotEmpty();
        // Per-unit caps keep every schema-valid duration within the runtime validator's 1-hour bound.
        assertThat(validate(kafkaExpectTimeout("999m"))).isNotEmpty();
        assertThat(validate(kafkaExpectTimeout("61m"))).isNotEmpty();
        assertThat(validate(kafkaExpectTimeout("1200s"))).isNotEmpty();
        assertThat(validate(kafkaExpectTimeout("30s"))).isEmpty();
        assertThat(validate(kafkaExpectTimeout("999s"))).isEmpty();
        assertThat(validate(kafkaExpectTimeout("100ms"))).isEmpty();
        assertThat(validate(kafkaExpectTimeout("2m"))).isEmpty();
        assertThat(validate(kafkaExpectTimeout("60m"))).isEmpty();
    }

    @Test
    @DisplayName("G5: fixture path traversal is rejected, a relative fixture path passes")
    void g5_fixtureTraversal() {
        assertThat(validate(restPostBody("../../../etc/passwd"))).isNotEmpty();
        assertThat(validate(restPostBody("fixtures/request.json"))).isEmpty();
    }

    private static String restAssertion(String assertionJson) {
        // The full assertion grammar (all five matchers, exactly-one, equals:null rejected) lives on the
        // REST surface; kafka.expect/grpc.unary use the restricted equals-only list (pinned separately in
        // ScenarioSchemaValidationTest#kafkaGrpcAssertionsAreEqualsOnly).
        return "{\"id\":\"a\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.post\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"expect\":{\"status\":200},\"assert\":[" + assertionJson + "]}]}";
    }

    @Test
    @DisplayName("G6: a REST assertion carries exactly one matcher and equals:null is rejected")
    void g6_assertionExactlyOneMatcher() {
        assertThat(validate(restAssertion("{\"path\":\"$.x\"}"))).isNotEmpty();
        assertThat(validate(restAssertion("{\"path\":\"$.x\",\"equals\":1,\"exists\":true}"))).isNotEmpty();
        assertThat(validate(restAssertion("{\"path\":\"$.x\",\"equals\":null}"))).isNotEmpty();
        assertThat(validate(restAssertion("{\"path\":\"$.x\",\"equals\":\"OK\"}"))).isEmpty();
        assertThat(validate(restAssertion("{\"path\":\"$.x\",\"exists\":true}"))).isEmpty();
        assertThat(validate(restAssertion("{\"path\":\"$.x\",\"matches\":\"^A\"}"))).isEmpty();
    }
}
