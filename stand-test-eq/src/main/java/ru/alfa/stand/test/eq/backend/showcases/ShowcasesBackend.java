package ru.alfa.stand.test.eq.backend.showcases;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.eq.backend.SeedExecution;
import ru.alfa.stand.test.eq.backend.SeedPlan;
import ru.alfa.stand.test.eq.backend.SeedResult;
import ru.alfa.stand.test.eq.config.ShowcasesBackendConfig;
import ru.alfa.stand.test.eq.ids.EqIdGenerator;
import ru.alfa.stand.test.eq.report.SeedJournal;
import ru.alfa.stand.test.eq.report.SeedLog;
import ru.alfa.stand.test.http.AuthHeaderResolver;
import ru.alfa.stand.test.http.BaseUrlResolver;
import ru.alfa.stand.test.http.CorrelationHeader;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestRequest;
import ru.alfa.stand.test.http.RestResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Writes the established organisation records through the shared HTTP transport. */
public final class ShowcasesBackend {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpCaller caller;
    private final BaseUrlResolver baseUrlResolver;
    private final AuthHeaderResolver authHeaderResolver;
    private final SeedJournal journal;
    private final Clock clock;

    public ShowcasesBackend(HttpCaller caller, BaseUrlResolver baseUrlResolver, AuthHeaderResolver authHeaderResolver) {
        this(caller, baseUrlResolver, authHeaderResolver, SeedJournal.shared(), Clock.systemUTC());
    }

    /** Creates a backend with explicit journal and clock seams for tests. */
    public ShowcasesBackend(HttpCaller caller, BaseUrlResolver baseUrlResolver, AuthHeaderResolver authHeaderResolver,
                            SeedJournal journal, Clock clock) {
        this.caller = Objects.requireNonNull(caller, "caller must not be null");
        this.baseUrlResolver = Objects.requireNonNull(baseUrlResolver, "baseUrlResolver must not be null");
        this.authHeaderResolver = Objects.requireNonNull(authHeaderResolver, "authHeaderResolver must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Executes one client write, then each account and its optional package deal. */
    public SeedExecution seed(SeedPlan plan, ShowcasesBackendConfig config, StepExecutionContext context,
                              int stepNumber, LocalDate today) {
        EnvironmentDefinition environment = context.environmentRegistry()
                .environment(context.scenarioContext().environment())
                .orElseThrow(() -> new StandTestException("Unknown environment for eq.seed"));
        ServiceEndpointDefinition endpoint = environment.service(config.service())
                .orElseThrow(() -> new StandTestException("EQ service alias '" + config.service() + "' is not whitelisted"));
        String baseUrl = baseUrlResolver.resolve(endpoint.baseUrlRef());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (endpoint.auth() != null) {
            headers.put("Authorization", authHeaderResolver.resolve(endpoint.auth()));
        }
        CorrelationHeader.inject(endpoint.correlation() != null, endpoint, headers,
                context.scenarioContext().correlationId().value());
        EqIdGenerator ids = new EqIdGenerator(context.scenarioContext().testRunId().value(), stepNumber);
        String pin = ids.pin();
        String name = Objects.requireNonNull(plan.name(), "name must be resolved before execution");
        List<String> accounts = new ArrayList<>();
        Map<Integer, String> deals = new LinkedHashMap<>();
        SeedLog.Collector collector = new SeedLog.Collector();
        boolean clientConfirmed = false;
        try {
            send(config, baseUrl, headers, ShowcasesRecords.organisation(pin, ids.registrationNumber(), name),
                    "seed-client", collector);
            clientConfirmed = true;
            for (int index = 0; index < plan.accounts().size(); index++) {
                SeedPlan.Account account = plan.accounts().get(index);
                String accountNumber = ids.account(index, account.currency());
                LocalDate openedAt = account.openedAt() == null ? today : account.openedAt();
                send(config, baseUrl, headers, List.of(ShowcasesRecords.account(accountNumber, pin, account.type(),
                        account.currency(), openedAt)), "seed-account", collector);
                accounts.add(accountNumber);
                if (account.servicePackage() != null) {
                    String dealId = ids.deal(account.servicePackage());
                    send(config, baseUrl, headers, List.of(ShowcasesRecords.deal(dealId, pin, accountNumber,
                            account.servicePackage(), today)), "seed-deal", collector);
                    deals.put(index, dealId);
                }
            }
        } catch (EqSeedException failure) {
            SeedLog snapshot = collector.snapshot(plan.alias(), config.alias(), clientConfirmed ? pin : null,
                    accounts, deals.size(), approximations(plan));
            throw failure.withLog(snapshot);
        }
        SeedResult result = new SeedResult(pin, accounts, deals);
        SeedLog log = collector.snapshot(plan.alias(), config.alias(), pin, accounts, deals.size(), approximations(plan));
        journal.append(new SeedJournal.SeedRecord(context.scenarioContext().environment(),
                context.scenarioContext().testRunId().value(), pin, accounts, null, clock.instant()));
        return new SeedExecution(result, log);
    }

    private void send(ShowcasesBackendConfig config, String baseUrl, Map<String, String> headers,
                      List<ShowcasesRecords.Record> records, String operation, SeedLog.Collector collector) {
        RestRequest request = new RestRequest("POST", baseUrl, config.path(), Map.of(), headers,
                ShowcasesRecords.toJson(records));
        long started = System.nanoTime();
        RestResponse response;
        try {
            response = caller.execute(request);
        } catch (RuntimeException failure) {
            throw new EqSeedException("TRANSPORT", operation, "EQ showcases transport failed during " + operation);
        }
        long durationMillis = (System.nanoTime() - started) / 1_000_000L;
        if (response.statusCode() != 200) {
            throw classify(collector, operation, durationMillis, "HTTP_UNEXPECTED_STATUS",
                    "EQ showcases " + operation + " returned HTTP " + response.statusCode());
        }
        if (response.body().contains("failed: null")) {
            throw classify(collector, operation, durationMillis, "MOCK_REJECTED",
                    "EQ showcases rejected " + operation + " (known mock failure signature)");
        }
        Object decoded;
        try {
            decoded = JSON.readValue(response.body(), Object.class);
        } catch (JacksonException failure) {
            throw classify(collector, operation, durationMillis, "UNEXPECTED",
                    "EQ showcases returned an unparseable response");
        }
        if (!(decoded instanceof Map<?, ?> body)) {
            throw classify(collector, operation, durationMillis, "UNEXPECTED",
                    "EQ showcases returned an unexpected response shape");
        }
        Object count = body.get("sentCount");
        boolean countMatches = count instanceof Number number
                && new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(records.size())) == 0;
        if (!"OK".equals(body.get("status")) || !countMatches) {
            throw classify(collector, operation, durationMillis, "MOCK_REJECTED",
                    "EQ showcases did not confirm " + operation + " records");
        }
        collector.record(operation, "OK", durationMillis);
    }

    private static EqSeedException classify(SeedLog.Collector collector, String operation, long durationMillis,
                                            String category, String message) {
        collector.record(operation, category, durationMillis);
        return new EqSeedException(category, operation, message);
    }

    private static List<String> approximations(SeedPlan plan) {
        return plan.approximations().stream().map(Enum::name).sorted().toList();
    }
}