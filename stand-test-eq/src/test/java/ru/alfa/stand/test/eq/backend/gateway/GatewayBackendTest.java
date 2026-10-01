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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.AuthScheme;
import ru.alfa.stand.test.eq.EqAccount;
import ru.alfa.stand.test.eq.EqSeed;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.eq.backend.SeedExecution;
import ru.alfa.stand.test.eq.backend.SeedPlan;
import ru.alfa.stand.test.eq.config.EqDefaults;
import ru.alfa.stand.test.eq.config.GatewayBackendConfig;
import ru.alfa.stand.test.eq.report.SeedJournal;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestRequest;
import ru.alfa.stand.test.http.RestResponse;
import tools.jackson.databind.ObjectMapper;

class GatewayBackendTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void organisationChainRunsInOrderAndReturnsConfirmedIdentifiers() {
        List<String> options = new ArrayList<>();
        HttpCaller caller = request -> {
            Map<?, ?> body = JSON.readValue(request.body(), Map.class);
            String option = body.get("option").toString();
            options.add(option);
            return switch (option) {
                case "ONU" -> new RestResponse(200, Map.of(), "\"TABCDE\"");
                case "OKC" -> new RestResponse(200, Map.of(), "\"40702810123456789012\"");
                default -> new RestResponse(200, Map.of(), "{}");
            };
        };
        GatewayBackend backend = backend(caller, new GatewayQueue());
        SeedExecution execution = backend.seed(plan(1), gatewayConfig(), context());

        assertThat(options).containsExactly("ONU", "OKC", "YFT2", "KP1");
        assertThat(execution.result().pin()).isEqualTo("TABCDE");
        assertThat(execution.result().accounts()).containsExactly("40702810123456789012");
        assertThat(execution.log().operations()).extracting("option")
                .containsExactly("ONU", "OKC", "YFT2", "KP1");
        assertThat(execution.log().toText()).doesNotContain("SEPTEMBER-SECRET-BODY", "GZINN");
    }

    @Test
    void unverifiableResponseEndsBrokenWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        HttpCaller caller = request -> {
            calls.incrementAndGet();
            Map<?, ?> body = JSON.readValue(request.body(), Map.class);
            if ("ONU".equals(body.get("option"))) {
                return new RestResponse(200, Map.of(), "NOT-A-PIN");
            }
            return new RestResponse(200, Map.of(), "{}");
        };
        GatewayBackend backend = backend(caller, new GatewayQueue());
        assertThatThrownBy(() -> backend.seed(plan(1), gatewayConfig(), context()))
                .isInstanceOfSatisfying(EqSeedException.class, failure ->
                        assertThat(failure.category()).isEqualTo("UNVERIFIABLE_RESPONSE"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void timeoutIsStateUnknownWithMarkerAndNoRetry() {
        AtomicInteger calls = new AtomicInteger();
        HttpCaller caller = request -> {
            calls.incrementAndGet();
            throw new IllegalStateException("connect reset");
        };
        GatewayBackend backend = backend(caller, new GatewayQueue());
        assertThatThrownBy(() -> backend.seed(plan(1), gatewayConfig(), context()))
                .isInstanceOfSatisfying(EqSeedException.class, failure -> {
                    assertThat(failure.category()).isEqualTo("TIMEOUT_UNKNOWN");
                    assertThat(failure.getMessage()).contains("NOT retried").doesNotContain("connect reset");
                    assertThat(failure.failureDiagnostics()).containsEntry("eq.confirmed.deals", 0);
                });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void partialFailureCarriesConfirmedAccountInJournal() {
        HttpCaller caller = request -> {
            Map<?, ?> body = JSON.readValue(request.body(), Map.class);
            String option = body.get("option").toString();
            return switch (option) {
                case "ONU" -> new RestResponse(200, Map.of(), "TABCDE");
                case "OKC" -> new RestResponse(200, Map.of(), "40702810123456789012");
                default -> new RestResponse(503, Map.of(), "SECRET-GATEWAY-BODY");
            };
        };
        GatewayBackend backend = backend(caller, new GatewayQueue());
        assertThatThrownBy(() -> backend.seed(plan(1), gatewayConfig(), context()))
                .isInstanceOfSatisfying(EqSeedException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("YFT2");
                    assertThat(failure.failureDiagnostics().get("eq.confirmed.pin")).isEqualTo("TABCDE");
                    assertThat(failure.failureDiagnostics().get("eq.confirmed.accounts").toString())
                            .contains("40702810123456789012");
                    assertThat(failure.getMessage()).doesNotContain("SECRET-GATEWAY-BODY");
                });
    }

    @Test
    void defaultPolicyConfirmsCapturedContracts() {
        GatewayResponsePolicy policy = new GatewayResponsePolicy();
        assertThat(policy.confirm("ONU", new RestResponse(200, Map.of(), "\"TABCDE\""))).isEqualTo("TABCDE");
        assertThat(policy.confirm("OKC", new RestResponse(200, Map.of(), "\"40702810123456789012\"")))
                .isEqualTo("40702810123456789012");
        // YFT2/KP1 contracts captured live on 2026-09-30 (G0-EQ): success is an empty JSON object.
        assertThat(policy.confirm("YFT2", new RestResponse(200, Map.of(), "{}"))).isEqualTo("YFT2");
        assertThat(policy.confirm("KP1", new RestResponse(200, Map.of(), "{}"))).isEqualTo("KP1");
        assertThatThrownBy(() -> policy.confirm("YFT2", new RestResponse(200, Map.of(), "unexpected")))
                .isInstanceOfSatisfying(EqSeedException.class, failure ->
                        assertThat(failure.category()).isEqualTo("UNVERIFIABLE_RESPONSE"));
        // VAD/SPU contracts captured live on 2026-09-30 (G0-FL): also an empty JSON object.
        assertThat(policy.confirm("VAD", new RestResponse(200, Map.of(), "{}"))).isEqualTo("VAD");
        assertThat(policy.confirm("SPU", new RestResponse(200, Map.of(), "{}"))).isEqualTo("SPU");
    }

    @Test
    void missingCashAccountForCurrencyIsRefusedBeforeIo() {
        AtomicInteger calls = new AtomicInteger();
        GatewayBackend backend = backend(request -> {
            calls.incrementAndGet();
            return new RestResponse(200, Map.of(), "\"TABCDE\"");
        }, new GatewayQueue());
        GatewayBackendConfig usd = gatewayConfigWithCash(Map.of("RUR", "rur-cash"));
        assertThatThrownBy(() -> backend.seed(planUsd(), usd, context()))
                .hasMessageContaining("no cash account for currency 'USD'");
        assertThat(calls.get()).isZero();
    }

    @Test
    void queueSerializesTwoChainsOnTheSamePair() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        HttpCaller caller = request -> {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            started.countDown();
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            Map<?, ?> body = JSON.readValue(request.body(), Map.class);
            return switch (body.get("option").toString()) {
                case "ONU" -> new RestResponse(200, Map.of(), "TABCDE");
                case "OKC" -> new RestResponse(200, Map.of(), "40702810123456789012");
                default -> new RestResponse(200, Map.of(), "{}");
            };
        };
        GatewayBackend backend = backend(caller, new GatewayQueue());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> backend.seed(plan(1), gatewayConfig(), context()));
        pool.submit(() -> backend.seed(plan(2), gatewayConfig(), context()));
        started.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    void gatewayAuthInjectsAuthorizationHeaderWithoutLoggingTheCredential() {
        List<RestRequest> requests = new ArrayList<>();
        HttpCaller caller = request -> {
            requests.add(request);
            Map<?, ?> body = JSON.readValue(request.body(), Map.class);
            return switch (body.get("option").toString()) {
                case "ONU" -> new RestResponse(200, Map.of(), "TABCDE");
                case "OKC" -> new RestResponse(200, Map.of(), "40702810123456789012");
                default -> new RestResponse(200, Map.of(), "{}");
            };
        };
        GatewayBackend backend = backend(caller, new GatewayQueue());
        backend.seed(plan(1), gatewayConfigWithAuth(), context());
        assertThat(requests).allSatisfy(request ->
                assertThat(request.headers()).containsEntry("Authorization", "Bearer resolved-token"));
    }

    private GatewayBackend backend(HttpCaller caller, GatewayQueue queue) {
        return new GatewayBackend(caller, auth -> "Bearer resolved-token", queue, new SeedJournal(tempDir),
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
            case "EQ_CASH_RUR" -> "cash-rur";
            default -> null;
        }, "test", "run-1", CLOCK.instant());
    }

    private static GatewayBackendConfig gatewayConfig() {
        return gatewayConfigWithCash(Map.of("RUR", "rur-cash", "USD", "usd-cash"), null);
    }

    private static GatewayBackendConfig gatewayConfigWithCash(Map<String, String> cash) {
        return gatewayConfigWithCash(cash, null);
    }

    private static GatewayBackendConfig gatewayConfigWithAuth() {
        return gatewayConfigWithCash(Map.of("RUR", "rur-cash"), AuthConfig.bearer("EQ_GATEWAY_TOKEN"));
    }

    private static GatewayBackendConfig gatewayConfigWithCash(Map<String, String> cash, AuthConfig auth) {
        return new GatewayBackendConfig("eq", true, "EQ_URL", auth,
                new ru.alfa.stand.test.eq.config.ConfiguredValue(null, "EQ_UNIT"),
                new ru.alfa.stand.test.eq.config.ConfiguredValue(null, "EQ_BRANCH"),
                new ru.alfa.stand.test.eq.config.ConfiguredValue("77", null),
                new ru.alfa.stand.test.eq.config.ConfiguredValue(List.of("01"), null),
                cash.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> new ru.alfa.stand.test.eq.config.ConfiguredValue(entry.getValue(), null))),
                Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMinutes(10), null, null, defaults());
    }

    private static EqDefaults defaults() {
        return new EqDefaults("ООО АТ", "OOO", "CA", "EE", "RUR", new BigDecimal("100000"), "REG", "12M");
    }

    private static SeedPlan plan(int ordinal) {
        return SeedPlan.from(EqSeed.organisation("client").name("ООО TEST")
                .account(EqAccount.type("CA").currency("RUR").topUp(new BigDecimal("100000")).servicePackage("PU_NWA"))
                .build()).withDefaults(defaults(), "TABCDE");
    }

    private static SeedPlan planUsd() {
        return SeedPlan.from(EqSeed.organisation("client").name("ООО TEST")
                .account(EqAccount.type("CA").currency("USD")).build()).withDefaults(defaults(), "TABCDE");
    }
}