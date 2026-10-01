package ru.alfa.stand.test.eq.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.eq.EqAccount;
import ru.alfa.stand.test.eq.EqSeed;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.eq.backend.SeedPlan;
import ru.alfa.stand.test.eq.config.EqDefaults;
import ru.alfa.stand.test.eq.config.GatewayBackendConfig;
import ru.alfa.stand.test.eq.ids.DulGenerator;
import ru.alfa.stand.test.eq.report.SeedJournal;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestRequest;
import ru.alfa.stand.test.http.RestResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * AC-6: every defect of {@code aiagents-taksa-starter:0.2.0} listed in Приложение Г is pinned by a module
 * test, so the SDK cannot silently reproduce it. Each test names its item in {@link DisplayName}.
 *
 * <p>Items that are structural (Г-11, Г-12, Г-13) are pinned at the seam that makes them true rather than
 * by a byte-level scan: no {@code @ComponentScan}, no {@code application.yml} in the jar, only Jackson 3,
 * and a base URL that can only arrive as a registry reference.
 */
class AppendixGAcceptanceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Г-1: ONU.GZCTP carries the organisation type, never the account type")
    void organisationTypeNotAccountType() {
        List<Map<?, ?>> params = new ArrayList<>();
        HttpCaller caller = capturing(params);
        backend(caller).seed(plan("RUR"), gatewayConfig(), context());

        Map<?, ?> onu = operation(params, "ONU");
        assertThat(onu.get("GZCTP")).isEqualTo("OOO");
        assertThat(onu.get("GZCTP")).isNotEqualTo("CA");
    }

    @Test
    @DisplayName("Г-2: the organisation chain never issues SPU; SPU is per client, not per account")
    void organisationChainIssuesNoSpu() {
        List<String> options = new ArrayList<>();
        HttpCaller caller = request -> {
            options.add(option(request));
            return responseFor(option(request));
        };
        backend(caller).seed(plan("RUR"), gatewayConfig(), context());

        assertThat(options).doesNotContain("SPU");
    }

    @Test
    @DisplayName("Г-2: the individual chain issues SPU exactly once per client")
    void individualChainIssuesSpuOnce() {
        List<String> options = new ArrayList<>();
        HttpCaller caller = request -> {
            options.add(option(request));
            return responseFor(option(request));
        };
        backend(caller).seed(individualPlan(), gatewayConfig(), context());

        assertThat(options).containsExactly("ONF", "VAD", "OKC", "YFT2", "SPU");
    }

    @Test
    @DisplayName("Г-3: an unverifiable response ends BROKEN, not SUCCESS")
    void unverifiableResponsesAreBroken() {
        GatewayResponsePolicy policy = new GatewayResponsePolicy();
        // A confirmed empty-object contract still fails closed when the body is not {@code {}}.
        assertThatThrownBy(() -> policy.confirm("YFT2", new RestResponse(200, Map.of(), "unexpected")))
                .isInstanceOf(EqSeedException.class);
        assertThatThrownBy(() -> policy.confirm("KP1", new RestResponse(200, Map.of(), "\"OK\"")))
                .isInstanceOf(EqSeedException.class);
        // The individual chain is confirmed live (G0-FL): VAD/SPU expect an empty object too.
        assertThat(policy.confirm("VAD", new RestResponse(200, Map.of(), "{}"))).isEqualTo("VAD");
        assertThat(policy.confirm("SPU", new RestResponse(200, Map.of(), "{}"))).isEqualTo("SPU");
        assertThatThrownBy(() -> policy.confirm("VAD", new RestResponse(200, Map.of(), "unexpected")))
                .isInstanceOf(EqSeedException.class);
    }

    @Test
    @DisplayName("Г-4: the cash account is chosen per account currency, not one ruble cash for all")
    void cashAccountFollowsCurrency() {
        List<Map<?, ?>> params = new ArrayList<>();
        backend(capturing(params)).seed(plan("RUR"), gatewayConfigWithCash(Map.of("RUR", "rur-cash", "USD", "usd-cash")),
                context());
        assertThat(operation(params, "YFT2").get("GZEND")).isEqualTo("rur-cash");

        List<Map<?, ?>> usdParams = new ArrayList<>();
        backend(capturing(usdParams)).seed(plan("USD"), gatewayConfigWithCash(Map.of("RUR", "rur-cash", "USD", "usd-cash")),
                context());
        assertThat(operation(usdParams, "YFT2").get("GZEND")).isEqualTo("usd-cash");
    }

    @Test
    @DisplayName("Г-5: a partial failure reports the already-confirmed client, never a silent stop")
    void partialFailureReportsConfirmedObjects() {
        AtomicInteger calls = new AtomicInteger();
        HttpCaller caller = request -> {
            int index = calls.incrementAndGet();
            if (index == 3) {
                return new RestResponse(503, Map.of(), "secret-body");
            }
            return responseFor(option(request));
        };
        assertThatThrownBy(() -> backend(caller).seed(plan("RUR"), gatewayConfig(), context()))
                .isInstanceOfSatisfying(EqSeedException.class, failure ->
                        assertThat(failure.failureDiagnostics().get("eq.confirmed.pin")).isEqualTo("TABCDE"));
    }

    @Test
    @DisplayName("Г-6: DUL numbers stay unique within one millisecond, not only to the millisecond")
    void dulNumbersDifferWithinOneMillisecond() {
        DulGenerator generator = new DulGenerator("run-1");
        assertThat(generator.next()).isNotEqualTo(generator.next());
    }

    @Test
    @DisplayName("Г-7 / Г-8: the gateway call uses the configured response timeout, and it is always set")
    void gatewayUsesConfiguredResponseTimeout() {
        List<Duration> timeouts = new ArrayList<>();
        HttpCaller caller = new HttpCaller() {
            @Override
            public RestResponse execute(RestRequest request) {
                return execute(request, Duration.ofMillis(40));
            }

            @Override
            public RestResponse execute(RestRequest request, Duration timeout) {
                timeouts.add(timeout);
                return responseFor(option(request));
            }
        };
        backend(caller).seed(plan("RUR"), gatewayConfigWithTimeouts(Duration.ofSeconds(7), Duration.ofSeconds(42)), context());

        assertThat(timeouts).isNotEmpty().allSatisfy(timeout -> assertThat(timeout).isEqualTo(Duration.ofSeconds(42)));
    }

    @Test
    @DisplayName("Г-9: without jt400 the phase reader fails clearly, never with NoClassDefFoundError")
    void jt400AbsenceIsAClearError() {
        assertThatThrownBy(() -> new Jt400UnitPhaseReader().readPhase("SYS", "K68", "user", "secret"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("jt400");
    }

    @Test
    @DisplayName("Г-9 / SEC-05: a unit or user unsafe for a CL command is refused before jt400 is loaded")
    void unitPhaseCommandInjectionIsRefused() {
        Jt400UnitPhaseReader reader = new Jt400UnitPhaseReader();
        assertThatThrownBy(() -> reader.readPhase("SYS", "K68;RM", "user", "secret"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("unit must be");
        assertThatThrownBy(() -> reader.readPhase("SYS", "K68", "bad'user", "secret"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("unsafe for a CL command");
    }

    @Test
    @DisplayName("Г-10: a transport failure keeps its original cause in the chain")
    void transportFailureKeepsCause() {
        RuntimeException transport = new IllegalStateException("connect reset");
        HttpCaller caller = request -> {
            throw transport;
        };
        assertThatThrownBy(() -> backend(caller).seed(plan("RUR"), gatewayConfig(), context()))
                .isInstanceOfSatisfying(EqSeedException.class, failure -> {
                    assertThat(failure.category()).isEqualTo("TIMEOUT_UNKNOWN");
                    assertThat(failure.getCause()).isSameAs(transport);
                    assertThat(failure.getMessage()).doesNotContain("connect reset");
                });
    }

    @Test
    @DisplayName("Г-11: the executor carries no @ComponentScan and bundles no application.yml")
    void noComponentScanAndNoBundledApplicationYml() {
        boolean componentScan = java.util.Arrays.stream(
                        ru.alfa.stand.test.eq.EqStepExecutor.class.getDeclaredAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getName()
                        .equals("org.springframework.context.annotation.ComponentScan"));
        assertThat(componentScan).isFalse();
        assertThat(ru.alfa.stand.test.eq.EqStepExecutor.class.getResource("/application.yml")).isNull();
    }

    @Test
    @DisplayName("Г-12: the module serialises with Jackson 3, not the Jackson 2 ObjectMapper")
    void usesJackson3Only() {
        assertThat(new ObjectMapper().writeValueAsString(Map.of("k", "v"))).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    @DisplayName("Г-13: the gateway address can only arrive as a registry reference, never a code constant")
    void gatewayAddressIsOnlyARegistryReference() {
        GatewayBackendConfig config = gatewayConfig();
        assertThat(config.baseUrlReference()).isEqualTo("EQ_URL");
        assertThat(GatewaySettings.resolve(config, name -> "http://10.232.128.12:4567/api").baseUrl())
                .isEqualTo("http://10.232.128.12:4567/api");
    }

    private static HttpCaller capturing(List<Map<?, ?>> params) {
        return request -> {
            params.add(JSON.readValue(request.body(), Map.class));
            return responseFor(option(request));
        };
    }

    private static String option(RestRequest request) {
        return JSON.readValue(request.body(), Map.class).get("option").toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> operation(List<Map<?, ?>> params, String name) {
        return params.stream()
                .filter(body -> name.equals(body.get("option")))
                .map(body -> (Map<?, ?>) body.get("params"))
                .findFirst()
                .orElseThrow();
    }

    private static RestResponse responseFor(String option) {
        return switch (option) {
            case "ONU", "ONF" -> new RestResponse(200, Map.of(), "\"TABCDE\"");
            case "OKC" -> new RestResponse(200, Map.of(), "\"40702810123456789012\"");
            case "YFT2", "KP1", "VAD", "SPU" -> new RestResponse(200, Map.of(), "{}");
            default -> throw new IllegalStateException("unexpected option " + option);
        };
    }

    private GatewayBackend backend(HttpCaller caller) {
        return new GatewayBackend(caller, auth -> "unused", new GatewayQueue(), new SeedJournal(tempDir),
                permissiveContracts());
    }

    private static GatewayContracts permissiveContracts() {
        GatewayResponsePolicy confirmed = new GatewayResponsePolicy();
        return (operation, response) -> {
            if (confirmed.issuesIdentifier(operation) || confirmed.issuesAccount(operation)) {
                return confirmed.confirm(operation, response);
            }
            if (response.statusCode() != 200) {
                throw new EqSeedException("HTTP_UNEXPECTED_STATUS", operation,
                        "EQ gateway " + operation + " returned HTTP " + response.statusCode());
            }
            return operation;
        };
    }

    private static GatewayBackend.GatewayContext context() {
        return new GatewayBackend.GatewayContext(name -> switch (name) {
            case "EQ_URL" -> "http://gateway.example";
            case "EQ_UNIT" -> "K68";
            case "EQ_BRANCH" -> "4101";
            case "EQ_CASH_RUR" -> "rur-cash";
            default -> null;
        }, "test", "run-1", CLOCK.instant());
    }

    private static GatewayBackendConfig gatewayConfig() {
        return gatewayConfigWithCash(Map.of("RUR", "rur-cash"));
    }

    private static GatewayBackendConfig gatewayConfigWithTimeouts(Duration connect, Duration response) {
        return new GatewayBackendConfig("eq", true, "EQ_URL", null,
                new ru.alfa.stand.test.eq.config.ConfiguredValue(null, "EQ_UNIT"),
                new ru.alfa.stand.test.eq.config.ConfiguredValue(null, "EQ_BRANCH"),
                new ru.alfa.stand.test.eq.config.ConfiguredValue("77", null),
                new ru.alfa.stand.test.eq.config.ConfiguredValue(List.of("01"), null),
                Map.of("RUR", new ru.alfa.stand.test.eq.config.ConfiguredValue("rur-cash", null)),
                connect, response, Duration.ofMinutes(10), null, null, defaults());
    }

    private static GatewayBackendConfig gatewayConfigWithCash(Map<String, String> cash) {
        return new GatewayBackendConfig("eq", true, "EQ_URL", null,
                new ru.alfa.stand.test.eq.config.ConfiguredValue(null, "EQ_UNIT"),
                new ru.alfa.stand.test.eq.config.ConfiguredValue(null, "EQ_BRANCH"),
                new ru.alfa.stand.test.eq.config.ConfiguredValue("77", null),
                new ru.alfa.stand.test.eq.config.ConfiguredValue(List.of("01"), null),
                cash.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> new ru.alfa.stand.test.eq.config.ConfiguredValue(entry.getValue(), null))),
                Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMinutes(10), null, null, defaults());
    }

    private static EqDefaults defaults() {
        return new EqDefaults("ООО АТ", "OOO", "CA", "EE", "RUR", new BigDecimal("100000"), "REG", "12M",
                new EqDefaults.IndividualDefaults("Иванов", "Иван", "Иванович", "091", "T04"));
    }

    private static SeedPlan plan(String currency) {
        return SeedPlan.from(EqSeed.organisation("client").name("ООО TEST")
                .account(EqAccount.type("CA").currency(currency).topUp(new BigDecimal("100000")).servicePackage("PU_NWA"))
                .build()).withDefaults(defaults(), "TABCDE");
    }

    private static SeedPlan individualPlan() {
        return SeedPlan.from(EqSeed.individual("client").name("Иванов Иван Иванович").servicePackage("T04")
                .account(EqAccount.type("EE").currency("RUR").topUp(new BigDecimal("100000")))
                .build()).withDefaults(defaults(), "TABCDE");
    }
}