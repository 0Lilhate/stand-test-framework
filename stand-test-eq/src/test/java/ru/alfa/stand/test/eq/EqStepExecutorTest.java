package ru.alfa.stand.test.eq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.compensation.UndoLog;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentSection;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.SectionEntry;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.event.StepPhase;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.core.variable.VariableStore;
import ru.alfa.stand.test.eq.ids.EqIdGenerator;
import ru.alfa.stand.test.eq.backend.gateway.GatewayQueue;
import ru.alfa.stand.test.eq.backend.gateway.UnitPhaseGate;
import ru.alfa.stand.test.eq.report.SeedJournal;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestRequest;
import ru.alfa.stand.test.http.RestResponse;
import tools.jackson.databind.ObjectMapper;

class EqStepExecutorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void preparesThenWritesThreeOperationsAndPublishesOnlyConfirmedVariables() {
        List<RestRequest> requests = new ArrayList<>();
        HttpCaller caller = request -> {
            requests.add(request);
            List<?> records = new ObjectMapper().readValue(request.body(), List.class);
            return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":" + records.size() + "}");
        };
        EqStepExecutor executor = executor(caller);
        StepExecutionContext context = context("showcases");
        GenericStep step = seed();

        executor.prepare(step, context);
        assertThat(requests).isEmpty();
        var result = executor.execute(step, context);
        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(requests).hasSize(3);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/showcases/load/list");
            assertThat(request.headers()).containsEntry("Content-Type", "application/json");
        });
        String pin = context.variableStore().getRequired("client.pin").toString();
        assertThat(pin).matches("T[A-Z0-9]{5}");
        assertThat(context.variableStore().getRequired("client.account")).isEqualTo(
                context.variableStore().getRequired("client.account.0"));
        assertThat(context.variableStore().getRequired("client.account.0").toString()).matches("40702810[0-9]{12}");
        assertThat(context.variableStore().getRequired("client.deal.0")).isEqualTo(pin + "_PU_NWA");
        assertThat(context.variableStore().contains("client.inn")).isFalse();
        assertThat(result.attachments()).hasSize(1);
        assertThat(result.attachments().get(0).content()).contains("backend=eq", "seed-client", "confirmed PIN",
                "confirmed accounts").doesNotContain("ООО TEST", "\"fields\"", "Authorization");
    }

    @Test
    void capabilityAndAliasCollisionFailBeforeIo() {
        List<RestRequest> requests = new ArrayList<>();
        EqStepExecutor executor = executor(request -> {
            requests.add(request);
            return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":1}");
        });
        StepExecutionContext context = context("showcases");
        GenericStep unsupported = EqSeed.organisation("client").name("Test")
                .account(EqAccount.type("CA").currency("RUR").topUp(new BigDecimal("1"))).build();
        assertThatThrownBy(() -> executor.prepare(unsupported, context))
                .hasMessageContaining("TOP_UP").hasMessageContaining("eq");
        assertThat(requests).isEmpty();

        executor.prepare(seed(), context);
        assertThatThrownBy(() -> executor.prepare(seed(), context)).hasMessageContaining("eq.alias:client");
        assertThat(requests).isEmpty();
    }

    @Test
    void mockRejectionIsBrokenAndDoesNotPublishResponseOrVariables() {
        String secretBody = "{\"status\":\"BAD_REQUEST\",\"detail\":\"secret-private-value\"}";
        EqStepExecutor executor = executor(request -> new RestResponse(200, Map.of(), secretBody));
        StepExecutionContext context = context("showcases");
        GenericStep step = seed();
        executor.prepare(step, context);

        assertThatThrownBy(() -> executor.execute(step, context)).isInstanceOf(EqSeedException.class)
                .hasMessageContaining("seed-client").hasMessageNotContaining("secret-private-value");
        assertThat(context.variableStore().asMap()).isEmpty();
    }

    @Test
    void failureCarriesAnAllowlistedJournalAttachmentWithoutRawBodies() {
        String secretBody = "{\"status\":\"BAD_REQUEST\",\"detail\":\"secret-private-value\"}";
        EqStepExecutor executor = executor(request -> new RestResponse(200, Map.of(), secretBody));
        StepExecutionContext context = context("showcases");
        GenericStep step = seed();
        executor.prepare(step, context);

        EqSeedException failure = (EqSeedException) org.assertj.core.api.Assertions.catchThrowable(
                () -> executor.execute(step, context));
        assertThat(failure.failureAttachments()).hasSize(1);
        String text = failure.failureAttachments().get(0).content();
        assertThat(text).contains("backend=eq", "seed-client", "MOCK_REJECTED")
                .doesNotContain("secret-private-value", "\"fields\"", "Authorization", "Content-Type");
    }

    @Test
    void unexpectedHttpStatusAndTransportFailureHaveStableSafeCategories() {
        EqStepExecutor statusExecutor = executor(request -> new RestResponse(503, Map.of(), "secret-response"));
        StepExecutionContext statusContext = context("showcases");
        statusExecutor.prepare(seed(), statusContext);
        assertThatThrownBy(() -> statusExecutor.execute(seed(), statusContext))
                .isInstanceOfSatisfying(EqSeedException.class, failure -> {
                    assertThat(failure.failureDiagnostics()).containsEntry("eq.failure.category", "HTTP_UNEXPECTED_STATUS")
                            .containsEntry("eq.operation", "seed-client");
                    assertThat(failure.getMessage()).doesNotContain("secret-response");
                });

        EqStepExecutor transportExecutor = executor(request -> {
            throw new IllegalStateException("secret-transport-detail");
        });
        StepExecutionContext transportContext = context("showcases");
        transportExecutor.prepare(seed(), transportContext);
        assertThatThrownBy(() -> transportExecutor.execute(seed(), transportContext))
                .isInstanceOfSatisfying(EqSeedException.class, failure -> {
                    assertThat(failure.failureDiagnostics()).containsEntry("eq.failure.category", "TRANSPORT");
                    assertThat(failure.getMessage()).doesNotContain("secret-transport-detail");
                });
    }

    @Test
    void gatewayWithoutWriteAllowedIsRefusedBeforeIo() {
        EqStepExecutor executor = executor(request -> {
            throw new AssertionError("IO must not occur");
        });
        Map<String, Object> fields = new java.util.LinkedHashMap<>(gatewayFields());
        fields.put("write-allowed", false);
        StepExecutionContext context = contextWithFields(fields, 1);
        assertThatThrownBy(() -> executor.prepare(seed(), context))
                .hasMessageContaining("EQ_WRITE_NOT_ALLOWED").hasMessageContaining("SEC-01");
    }

@Test
    void gatewayIndividualWithoutIndividualDefaultsIsRefusedBeforeIo() {
        List<RestRequest> requests = new ArrayList<>();
        EqStepExecutor executor = new EqStepExecutor(request -> {
            requests.add(request);
            return new RestResponse(200, Map.of(), "TABCDE");
        }, reference -> "http://example.invalid", auth -> "unused", CLOCK,
                new SeedJournal(Paths.get("build/tmp/test-eq-journal")), new GatewayQueue(),
                new UnitPhaseGate((system, unit, user, password) -> "ACTIVE", CLOCK), name -> "value");
        GenericStep individual = EqSeed.individual("client").name("Иванов Иван")
                .account(EqAccount.type("EE").currency("RUR")).build();
        assertThatThrownBy(() -> executor.prepare(individual, contextWithFields(gatewayFields(), 1)))
                .hasMessageContaining("defaults.individual");
        assertThat(requests).isEmpty();
    }

    @Test
    void gatewayIndividualWithDefaultsPreparesWithoutIo() {
        List<RestRequest> requests = new ArrayList<>();
        EqStepExecutor executor = new EqStepExecutor(request -> {
            requests.add(request);
            return new RestResponse(200, Map.of(), "TABCDE");
        }, reference -> "http://example.invalid", auth -> "unused", CLOCK,
                new SeedJournal(Paths.get("build/tmp/test-eq-journal")), new GatewayQueue(),
                new UnitPhaseGate((system, unit, user, password) -> "ACTIVE", CLOCK), name -> "value");
        GenericStep individual = EqSeed.individual("client").name("Иванов Иван Иванович").servicePackage("T04")
                .account(EqAccount.type("EE").currency("RUR")).build();
        StepExecutionContext context = contextWithFields(gatewayFieldsWithIndividual(), 1);

        executor.prepare(individual, context);

        assertThat(requests).isEmpty();
    }

    @Test
    void unitPhaseOutsideAllowedFailsAtPrepareWithOneReadAndNoGatewayCall() {
        int[] reads = {0};
        UnitPhaseGate gate = new UnitPhaseGate((system, unit, user, password) -> {
            reads[0]++;
            return "CLOSED";
        }, CLOCK);
        EqStepExecutor executor = new EqStepExecutor(request -> {
            throw new AssertionError("IO must not occur");
        }, reference -> "http://example.invalid", auth -> "unused", CLOCK,
                new SeedJournal(Paths.get("build/tmp/test-eq-journal")), new GatewayQueue(), gate, name -> "value");
        Map<String, Object> fields = new java.util.LinkedHashMap<>(gatewayFields());
        fields.put("unit-phase", Map.of("system-ref", "EQ_AS400_SYSTEM", "username-ref", "EQ_AS400_USER",
                "password-ref", "EQ_AS400_PASSWORD", "allowed", List.of("ACTIVE", "READY"), "cache-ttl", "5m"));

        assertThatThrownBy(() -> executor.prepare(seed(), contextWithFields(fields, 1)))
                .hasMessageContaining("is in phase <CLOSED>").hasMessageNotContaining("password");
        assertThat(reads[0]).isEqualTo(1);
    }

    @Test
    void registryDefaultsFillOnlyAbsentAttributesAndUseScenarioOrdinalForTheRunMarker() {
        List<RestRequest> requests = new ArrayList<>();
        EqStepExecutor executor = executor(request -> {
            requests.add(request);
            List<?> records = new ObjectMapper().readValue(request.body(), List.class);
            return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":" + records.size() + "}");
        });
        Map<String, Object> fields = Map.of("kind", "showcases", "service", "showcases",
                "path", "/showcases/load/list", "defaults", Map.of(
                        "organisation", Map.of("name-prefix", "ООО TEST"),
                        "account", Map.of("type-organisation", "CA", "currency", "RUR")));
        StepExecutionContext context = contextWithFields(fields, 3);
        GenericStep step = EqSeed.organisation("client").account(EqAccount.create()).build();

        executor.prepare(step, context);
        executor.execute(step, context);

        String pin = context.variableStore().getRequired("client.pin").toString();
        assertThat(pin).matches("T[A-Z0-9]{5}");
        assertThat(pin).isEqualTo(new EqIdGenerator(context.scenarioContext().testRunId().value(), 3).pin());
        List<?> clientRecords = new ObjectMapper().readValue(requests.get(0).body(), List.class);
        Map<?, ?> nameRecord = (Map<?, ?>) clientRecords.get(1);
        List<?> nameFields = (List<?>) nameRecord.get("fields");
        assertThat(((Map<?, ?>) nameFields.get(2)).get("value")).isEqualTo("ООО TEST " + pin);
        List<?> accountRecords = new ObjectMapper().readValue(requests.get(1).body(), List.class);
        Map<?, ?> accountRecord = (Map<?, ?>) accountRecords.get(0);
        List<?> accountFields = (List<?>) accountRecord.get("fields");
        assertThat(((Map<?, ?>) accountFields.get(4)).get("value")).isEqualTo("CA");
        assertThat(((Map<?, ?>) accountFields.get(6)).get("value")).isEqualTo("RUR");
    }

    @Test
    void partialDealFailureReportsConfirmedPinAndAccountButPublishesNoVariables() {
        List<RestRequest> requests = new ArrayList<>();
        EqStepExecutor executor = executor(request -> {
            requests.add(request);
            if (requests.size() == 3) {
                return new RestResponse(503, Map.of(), "secret-response-body");
            }
            int count = requests.size() == 1 ? 2 : 1;
            return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":" + count + "}");
        });
        StepExecutionContext context = context("showcases");
        GenericStep step = seed();
        executor.prepare(step, context);

        assertThatThrownBy(() -> executor.execute(step, context))
                .isInstanceOfSatisfying(EqSeedException.class, failure -> {
                    assertThat(failure.failureDiagnostics()).containsEntry("eq.operation", "seed-deal")
                            .containsEntry("eq.failure.category", "HTTP_UNEXPECTED_STATUS")
                            .containsEntry("eq.confirmed.deals", 0);
                    assertThat(failure.failureDiagnostics().get("eq.confirmed.pin").toString()).matches("T[A-Z0-9]{5}");
                    assertThat((List<?>) failure.failureDiagnostics().get("eq.confirmed.accounts")).hasSize(1);
                    assertThat(failure.getMessage()).contains("confirmed PIN", "confirmed accounts")
                            .doesNotContain("secret-response-body");
                });
        assertThat(context.variableStore().asMap()).isEmpty();
    }

    @Test
    void partialFailureReachesFinishedStepEventAsBroken() {
        List<StepEvent> events = new ArrayList<>();
        ReportingEventPublisher publisher = new ReportingEventPublisher() {
            @Override
            public void publish(ScenarioEvent event) {
            }

            @Override
            public void publish(StepEvent event) {
                events.add(event);
            }
        };
        int[] calls = {0};
        EqStepExecutor executor = executor(request -> {
            calls[0]++;
            if (calls[0] == 1) {
                return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":2}");
            }
            return new RestResponse(503, Map.of(), "secret-private-response");
        });
        DefaultScenarioRunner runner = new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(),
                context("showcases").environmentRegistry(), publisher, CLOCK);
        Scenario scenario = Scenario.builder("eq-partial").environment("ift").step(seed()).build();

        assertThatThrownBy(() -> runner.run(scenario)).hasMessageContaining("seed-account")
                .hasMessageNotContaining("secret-private-response");
        StepEvent finished = events.stream().filter(event -> event.phase() == StepPhase.FINISHED).findFirst().orElseThrow();
        assertThat(finished.status()).isEqualTo(StepStatus.BROKEN);
        assertThat(finished.diagnostics()).containsEntry("eq.failure.category", "HTTP_UNEXPECTED_STATUS")
                .containsEntry("eq.operation", "seed-account");
        assertThat(finished.diagnostics().get("eq.confirmed.pin").toString()).matches("T[A-Z0-9]{5}");
        assertThat(finished.diagnostics().get("eq.confirmed.accounts")).isEqualTo(List.of());
        assertThat(finished.message()).doesNotContain("secret-private-response");
        assertThat(finished.attachments()).hasSize(1);
        assertThat(finished.attachments().get(0).content()).contains("seed-account")
                .doesNotContain("secret-private-response", "\"fields\"");
    }

    @Test
    void eachStepAttachmentCarriesOnlyItsOwnJournalAcrossTwoScenarios() {
        List<StepEvent> events = new ArrayList<>();
        ReportingEventPublisher publisher = new ReportingEventPublisher() {
            @Override
            public void publish(ScenarioEvent event) {
            }

            @Override
            public void publish(StepEvent event) {
                events.add(event);
            }
        };
        EqStepExecutor executor = executor(request -> {
            List<?> records = new ObjectMapper().readValue(request.body(), List.class);
            return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":" + records.size() + "}");
        });
        DefaultScenarioRunner runner = new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(),
                context("showcases").environmentRegistry(), publisher, CLOCK);
        GenericStep first = EqSeed.organisation("alpha").name("ООО A")
                .account(EqAccount.type("CA").currency("RUR")).build();
        GenericStep second = EqSeed.organisation("beta").name("ООО B")
                .account(EqAccount.type("CA").currency("RUR")).build();

        runner.run(Scenario.builder("eq-two-steps").environment("ift").step(first).step(second).build());
        runner.run(Scenario.builder("eq-other-run").environment("ift").step(first).build());

        List<StepEvent> finished = events.stream().filter(event -> event.phase() == StepPhase.FINISHED).toList();
        assertThat(finished).hasSize(3);
        assertThat(finished.get(0).attachments().get(0).content()).contains("eq.seed 'alpha'").doesNotContain("beta");
        assertThat(finished.get(1).attachments().get(0).content()).contains("eq.seed 'beta'").doesNotContain("alpha");
        assertThat(finished.get(2).stepId()).isEqualTo("eq.seed:alpha");
    }

    @Test
    void visibilityPollChecksBodyAndUsesSeedPlaceholdersBeforePublishingVariables() {
        List<RestRequest> probes = new ArrayList<>();
        EqStepExecutor executor = executor(request -> {
            if ("POST".equals(request.method())) {
                List<?> records = new ObjectMapper().readValue(request.body(), List.class);
                return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":" + records.size() + "}");
            }
            probes.add(request);
            String value = probes.size() == 1 ? "not-visible" : request.query().get("clientCode");
            return new RestResponse(200, Map.of(), "{\"clientCode\":\"" + value + "\"}");
        });
        StepExecutionContext context = contextWithFields(showcasesWithVisibility("tks", "100ms"), 1);
        GenericStep step = seed();

        executor.prepare(step, context);
        assertThat(context.variableStore().asMap()).isEmpty();
        executor.execute(step, context);

        assertThat(probes).hasSize(2);
        assertThat(probes.get(0).method()).isEqualTo("GET");
        assertThat(probes.get(0).path()).startsWith("/clients/").doesNotContain("{seed.");
        assertThat(probes.get(0).query()).containsEntry("clientCode",
                context.variableStore().getRequired("client.pin").toString());
        assertThat(probes.get(0).headers()).containsEntry("X-Correlation-Id",
                context.scenarioContext().correlationId().value());
    }

    @Test
    void visibilityTimeoutIsBrokenWithConfirmedObjectsAndNoPublishedVariables() {
        List<RestRequest> probes = new ArrayList<>();
        EqStepExecutor executor = executor(request -> {
            if ("GET".equals(request.method())) {
                probes.add(request);
                return new RestResponse(200, Map.of(), "{\"clientCode\":\"absent\",\"secret\":\"private\"}");
            }
            List<?> records = new ObjectMapper().readValue(request.body(), List.class);
            return new RestResponse(200, Map.of(), "{\"status\":\"OK\",\"sentCount\":" + records.size() + "}");
        });
        StepExecutionContext context = contextWithFields(showcasesWithVisibility("tks", "40ms"), 1);
        GenericStep step = seed();
        executor.prepare(step, context);

        assertThatThrownBy(() -> executor.execute(step, context))
                .isInstanceOfSatisfying(EqSeedException.class, failure -> {
                    assertThat(failure.failureDiagnostics())
                            .containsEntry("eq.failure.category", "VISIBILITY_TIMEOUT")
                            .containsEntry("eq.operation", "visibility-probe")
                            .containsEntry("eq.visibility.last.status", 200)
                            .containsEntry("eq.visibility.timeout.ms", 40L);
                    assertThat(failure.failureDiagnostics().get("eq.confirmed.pin").toString())
                            .matches("T[A-Z0-9]{5}");
                    assertThat(failure.failureDiagnostics().get("eq.confirmed.accounts")).isInstanceOf(List.class);
                    assertThat(failure.getMessage()).doesNotContain("private", "absent");
                });
        assertThat(probes).isNotEmpty();
        assertThat(context.variableStore().asMap()).isEmpty();

        List<StepEvent> events = new ArrayList<>();
        ReportingEventPublisher publisher = new ReportingEventPublisher() {
            @Override
            public void publish(ScenarioEvent event) {
            }

            @Override
            public void publish(StepEvent event) {
                events.add(event);
            }
        };
        DefaultScenarioRunner runner = new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(),
                context.environmentRegistry(), publisher, CLOCK);
        assertThatThrownBy(() -> runner.run(Scenario.builder("eq-visibility-timeout")
                .environment("ift").step(seed()).build()))
                .isInstanceOf(ru.alfa.stand.test.core.exception.StandTestException.class)
                .hasCauseInstanceOf(EqSeedException.class);
        StepEvent finished = events.stream().filter(event -> event.phase() == StepPhase.FINISHED).findFirst().orElseThrow();
        assertThat(finished.status()).isEqualTo(StepStatus.BROKEN);
        assertThat(finished.diagnostics()).containsEntry("eq.failure.category", "VISIBILITY_TIMEOUT")
                .containsKey("eq.confirmed.pin");
    }

    @Test
    void nonWhitelistedVisibilityServiceFailsBeforeWrites() {
        EqStepExecutor executor = executor(request -> {
            throw new AssertionError("IO must not occur");
        });
        assertThatThrownBy(() -> executor.prepare(seed(),
                contextWithFields(showcasesWithVisibility("unknown", "40ms"), 1)))
                .hasMessageContaining("visibility service alias 'unknown'");
    }

    @Test
    void forgedGenericStepCannotBypassAccountSchema() {
        EqStepExecutor executor = executor(request -> {
            throw new AssertionError("IO must not occur");
        });
        GenericStep forged = new GenericStep("seed", "eq.seed", "", Map.of(
                "backend", "eq", "alias", "client", "clientKind", "organisation",
                "name", "Test", "accounts", List.of(Map.of("type", "CA", "currency", "RUR",
                        "accountNumber", "40702810123456789012"))));
        assertThatThrownBy(() -> executor.prepare(forged, context("showcases")))
                .hasMessageContaining("accountNumber");
    }

    private static EqStepExecutor executor(HttpCaller caller) {
        return new EqStepExecutor(caller, reference -> "http://example.invalid", auth -> "unused", CLOCK,
                new SeedJournal(Paths.get("build/tmp/test-eq-journal")), new GatewayQueue(),
                new UnitPhaseGate((system, unit, user, password) -> "ACTIVE", CLOCK), name -> null);
    }

    private static GenericStep seed() {
        return EqSeed.organisation("client").name("ООО TEST")
                .account(EqAccount.type("CA").currency("RUR").servicePackage("PU_NWA"))
                .build();
    }

    private static StepExecutionContext context(String kind) {
        Map<String, Object> fields = "showcases".equals(kind)
                ? Map.of("kind", "showcases", "service", "showcases", "path", "/showcases/load/list")
                : gatewayFields();
        return contextWithFields(fields, 1);
    }

    private static Map<String, Object> gatewayFields() {
        return Map.ofEntries(
                Map.entry("kind", "gateway"),
                Map.entry("write-allowed", true),
                Map.entry("base-url-ref", "EQ_URL"),
                Map.entry("unit", Map.of("ref", "EQ_UNIT")),
                Map.entry("branch", Map.of("ref", "EQ_BRANCH")),
                Map.entry("inn-region-code", "77"),
                Map.entry("inn-tax-offices", List.of("01")),
                Map.entry("cash-accounts", Map.of("RUR", Map.of("ref", "EQ_CASH_RUR"))),
                Map.entry("defaults", Map.of(
                        "organisation", Map.of("type", "OOO"),
                        "account", Map.of("type-organisation", "CA", "currency", "RUR", "top-up", 100000,
                                "package-registration", "REG", "package-duration", "12M"))));
    }

    private static Map<String, Object> gatewayFieldsWithIndividual() {
        return Map.ofEntries(
                Map.entry("kind", "gateway"),
                Map.entry("write-allowed", true),
                Map.entry("base-url-ref", "EQ_URL"),
                Map.entry("unit", Map.of("ref", "EQ_UNIT")),
                Map.entry("branch", Map.of("ref", "EQ_BRANCH")),
                Map.entry("inn-region-code", "77"),
                Map.entry("inn-tax-offices", List.of("01")),
                Map.entry("cash-accounts", Map.of("RUR", Map.of("ref", "EQ_CASH_RUR"))),
                Map.entry("defaults", Map.of(
                        "organisation", Map.of("type", "OOO"),
                        "account", Map.of("type-organisation", "CA", "type-individual", "EE", "currency", "RUR",
                                "top-up", 100000, "package-registration", "REG", "package-duration", "12M"),
                        "individual", Map.of("last-name", "Иванов", "first-name", "Иван", "middle-name", "Иванович",
                                "document-type", "091", "service-package", "T04"))));
    }

    private static StepExecutionContext contextWithFields(Map<String, Object> fields, int ordinal) {
        EnvironmentSection section = new EnvironmentSection("eq-backends", Map.of("eq", new SectionEntry("eq", fields)));
        EnvironmentDefinition environment = new EnvironmentDefinition("ift",
                Map.of("showcases", new ServiceEndpointDefinition("showcases", "SHOWCASES_URL", null),
                        "tks", new ServiceEndpointDefinition("tks", "TKS_URL",
                                new CorrelationConfig(CorrelationSource.HEADER, "X-Correlation-Id"))),
                Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(), Map.of("eq-backends", section));
        ScenarioContext scenario = ScenarioContext.start(ScenarioId.of("eq-test"), "ift");
        return new StepExecutionContext(scenario, new VariableStore(),
                new InMemoryEnvironmentRegistry(Map.of("ift", environment)), NoOpReportingEventPublisher.INSTANCE,
                new ResourceScope(), new UndoLog(), Map.of("eq.seed:client", ordinal));
    }

    private static Map<String, Object> showcasesWithVisibility(String service, String timeout) {
        return Map.of("kind", "showcases", "service", "showcases", "path", "/showcases/load/list",
                "visibility", Map.of("probe", Map.of("service", service, "path", "/clients/{seed.account}",
                        "query", Map.of("clientCode", "{seed.pin}"), "expect-status", 200,
                        "expect-body", Map.of("path", "$.clientCode", "equals", "{seed.pin}")),
                        "timeout", timeout, "poll-interval", "5ms"));
    }
}
