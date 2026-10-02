package ru.alfa.stand.test.eq.backend.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.eq.backend.SeedExecution;
import ru.alfa.stand.test.eq.backend.SeedPlan;
import ru.alfa.stand.test.eq.backend.SeedResult;
import ru.alfa.stand.test.eq.config.EqDefaults;
import ru.alfa.stand.test.eq.config.GatewayBackendConfig;
import ru.alfa.stand.test.eq.ids.DulGenerator;
import ru.alfa.stand.test.eq.ids.InnGenerator;
import ru.alfa.stand.test.eq.report.SeedJournal;
import ru.alfa.stand.test.eq.report.SeedLog;
import ru.alfa.stand.test.http.AuthHeaderResolver;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestResponse;

public final class GatewayBackend {

    private final HttpCaller caller;
    private final AuthHeaderResolver authHeaderResolver;
    private final GatewayQueue queue;
    private final SeedJournal journal;
    private final GatewayContracts contracts;

    public GatewayBackend(HttpCaller caller, AuthHeaderResolver authHeaderResolver, GatewayQueue queue,
                          SeedJournal journal) {
        this(caller, authHeaderResolver, queue, journal, new GatewayResponsePolicy());
    }

    public GatewayBackend(HttpCaller caller, AuthHeaderResolver authHeaderResolver, GatewayQueue queue,
                          SeedJournal journal, GatewayContracts contracts) {
        this.caller = Objects.requireNonNull(caller, "caller must not be null");
        this.authHeaderResolver = Objects.requireNonNull(authHeaderResolver, "authHeaderResolver must not be null");
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.contracts = Objects.requireNonNull(contracts, "contracts must not be null");
    }

    public SeedExecution seed(SeedPlan plan, GatewayBackendConfig config, GatewayContext context) {
        if (!"organisation".equals(plan.clientKind()) && !"individual".equals(plan.clientKind())) {
            throw new StandTestException("EQ gateway seed supports only organisation or individual clients");
        }
        GatewaySettings settings = GatewaySettings.resolve(config, context.lookup());
        validateBeforeIo(plan, settings);
        GatewayClient client = new GatewayClient(caller, settings.baseUrl(), authHeaderResolver, settings.auth(),
                settings.responseTimeout());
        SeedLog.Collector collector = new SeedLog.Collector();
        InnGenerator inns = new InnGenerator(context.testRunId(), settings.region(), settings.taxOffices());
        boolean individual = "individual".equals(plan.clientKind());
        String inn = individual ? inns.individual() : inns.organisation();
        String name = Objects.requireNonNull(plan.name(), "name must be resolved before execution");
        List<String> accounts = new ArrayList<>();
        Map<Integer, String> deals = new LinkedHashMap<>();
        String[] confirmedPin = {null};
        try (GatewayQueue.Lease lease = queue.acquire(settings.baseUrl(), settings.unit(), settings.acquireTimeout())) {
            if (individual) {
                createIndividual(plan, settings, context, client, collector, inn, name, accounts, confirmedPin);
            } else {
                createOrganisation(plan, settings, context, client, collector, inn, name, accounts, deals, confirmedPin);
            }
        } catch (EqSeedException failure) {
            SeedLog snapshot = collector.snapshot(plan.alias(), settings.alias(), confirmedPin[0], accounts, deals.size(),
                    approximations(plan));
            throw failure.withLog(snapshot);
        }
        SeedLog log = collector.snapshot(plan.alias(), settings.alias(), confirmedPin[0], accounts, deals.size(),
                approximations(plan));
        journal.append(new SeedJournal.SeedRecord(context.environment(), context.testRunId(), confirmedPin[0], accounts, inn,
                context.now()));
        return new SeedExecution(new SeedResult(confirmedPin[0], accounts, deals), log);
    }

    private void createOrganisation(SeedPlan plan, GatewaySettings settings, GatewayContext context, GatewayClient client,
                                    SeedLog.Collector collector, String inn, String name, List<String> accounts,
                                    Map<Integer, String> deals, String[] confirmedPin) {
        String organisationType = required(settings.defaults() == null ? null : settings.defaults().organisationType(),
                "defaults.organisation.type (GZCTP)");
        RestResponse onu = client.call(settings.unit(), "ONU", Map.of("GZCUN", name, "GZCTP", organisationType,
                "GZINN", inn, "GZFNM1", name));
        String pin = contracts.confirm("ONU", onu);
        confirmedPin[0] = pin;
        collector.record("ONU", "OK", 0);
        for (int index = 0; index < plan.accounts().size(); index++) {
            SeedPlan.Account account = plan.accounts().get(index);
            String currency = required(account.currency() == null && settings.defaults() != null
                    ? settings.defaults().currency() : account.currency(), "account currency");
            String accountType = required(account.type() == null && settings.defaults() != null
                    ? settings.defaults().organisationAccountType() : account.type(), "account type");
            RestResponse okc = client.call(settings.unit(), "OKC", Map.of("GZACT", accountType, "GZAB", settings.branch(),
                    "GZCCY", currency, "GZCUS", pin));
            String accountNumber = contracts.confirm("OKC", okc);
            collector.record("OKC", "OK", 0);
            accounts.add(accountNumber);
            topUp(client, settings, account, accountNumber, currency, collector);
            connectPackage(client, settings, account, accountNumber, collector);
        }
        if (accounts.isEmpty()) {
            throw new StandTestException("EQ gateway organisation seed produced no account");
        }
    }

    private void createIndividual(SeedPlan plan, GatewaySettings settings, GatewayContext context, GatewayClient client,
                                  SeedLog.Collector collector, String inn, String name, List<String> accounts,
                                  String[] confirmedPin) {
        IndividualInputs inputs = individualInputs(settings, name);
        String document = new DulGenerator(context.testRunId()).next();
        RestResponse onf = client.call(settings.unit(), "ONF", Map.of(
                "GZCUN", inputs.fullName(), "GZFNM1", inputs.surName(), "GZFNM2", inputs.givenName(),
                "GZFNM3", inputs.middleName(), "GZINN", inn, "GZNOM", document, "GZDUL", inputs.documentType()));
        String pin = contracts.confirm("ONF", onf);
        confirmedPin[0] = pin;
        collector.record("ONF", "OK", 0);
        contracts.confirm("VAD", client.call(settings.unit(), "VAD", Map.of("GZCUS", pin)));
        collector.record("VAD", "OK", 0);
        for (SeedPlan.Account account : plan.accounts()) {
            String currency = required(account.currency() == null && settings.defaults() != null
                    ? settings.defaults().currency() : account.currency(), "account currency");
            String accountType = required(account.type() == null && settings.defaults() != null
                    ? settings.defaults().individualAccountType() : account.type(), "account type");
            RestResponse okc = client.call(settings.unit(), "OKC", Map.of("GZACT", accountType, "GZAB", settings.branch(),
                    "GZCCY", currency, "GZCUS", pin));
            String accountNumber = contracts.confirm("OKC", okc);
            collector.record("OKC", "OK", 0);
            accounts.add(accountNumber);
            topUp(client, settings, account, accountNumber, currency, collector);
        }
        if (accounts.isEmpty()) {
            throw new StandTestException("EQ gateway individual seed produced no account");
        }
        if (inputs.servicePackage() != null) {
            contracts.confirm("SPU", client.call(settings.unit(), "SPU",
                    Map.of("GZCUS", pin, "GZP3R", inputs.servicePackage())));
            collector.record("SPU", "OK", 0);
        }
    }

    private static IndividualInputs individualInputs(GatewaySettings settings, String name) {
        EqDefaults.IndividualDefaults defaults = settings.defaults() == null ? null : settings.defaults().individual();
        String[] parts = splitName(name);
        String surName = required(defaults == null ? null : defaults.surName(), "defaults.individual.last-name");
        String givenName = required(defaults == null ? null : defaults.givenName(), "defaults.individual.first-name");
        String documentType = required(defaults == null ? null : defaults.documentType(), "defaults.individual.document-type");
        return new IndividualInputs(surName, givenName, parts[2], documentType,
                defaults == null ? null : defaults.servicePackage());
    }

    private static String[] splitName(String name) {
        String[] parts = name.trim().split("\\s+");
        String middle = parts.length >= 3 ? parts[2] : "";
        return new String[] {parts[0], parts.length >= 2 ? parts[1] : parts[0], middle};
    }

    private void topUp(GatewayClient client, GatewaySettings settings, SeedPlan.Account account, String accountNumber,
                       String currency, SeedLog.Collector collector) {
        Object amount = account.topUp() == null && settings.defaults() != null ? settings.defaults().topUp() : account.topUp();
        if (amount == null) {
            throw new StandTestException("EQ gateway account top-up needs a DSL value or defaults.account.top-up");
        }
        String cash = settings.cashAccounts().get(currency);
        if (cash == null) {
            throw new StandTestException("EQ gateway has no cash account for currency '" + currency
                    + "' (BR-24); declare eq-backends." + settings.alias() + ".cash-accounts." + currency);
        }
        RestResponse response = client.call(settings.unit(), "YFT2", Map.of("GZNAR", "Пополнение счета",
                "GZENC", accountNumber, "GZEND", cash, "GZAMA", amount.toString(), "GZCCY", currency));
        contracts.confirm("YFT2", response);
        collector.record("YFT2", "OK", 0);
    }

    private void connectPackage(GatewayClient client, GatewaySettings settings, SeedPlan.Account account,
                                String accountNumber, SeedLog.Collector collector) {
        if (account.servicePackage() == null) {
            return;
        }
        String registration = required(settings.defaults() == null ? null : settings.defaults().packageRegistration(),
                "defaults.account.package-registration (GZREG)");
        String duration = required(settings.defaults() == null ? null : settings.defaults().packageDuration(),
                "defaults.account.package-duration (GZSROK)");
        RestResponse response = client.call(settings.unit(), "KP1", Map.of("GZPID", account.servicePackage(),
                "GZREG", registration, "GZSROK", duration, "GZEAN", accountNumber));
        contracts.confirm("KP1", response);
        collector.record("KP1", "OK", 0);
    }

    private static void validateBeforeIo(SeedPlan plan, GatewaySettings settings) {
        for (SeedPlan.Account account : plan.accounts()) {
            String currency = account.currency() == null && settings.defaults() != null
                    ? settings.defaults().currency() : account.currency();
            if (currency == null) {
                throw new StandTestException("EQ gateway account currency is required");
            }
            if (!settings.cashAccounts().containsKey(currency)) {
                throw new StandTestException("EQ gateway has no cash account for currency '" + currency
                        + "' (BR-24); declare eq-backends." + settings.alias() + ".cash-accounts." + currency);
            }
            Object amount = account.topUp() == null && settings.defaults() != null
                    ? settings.defaults().topUp() : account.topUp();
            if (amount == null) {
                throw new StandTestException("EQ gateway account top-up needs a DSL value or defaults.account.top-up");
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new StandTestException("EQ gateway requires '" + field + "': set it on the step or in eq-backends defaults");
        }
        return value;
    }

    private static List<String> approximations(SeedPlan plan) {
        return plan.approximations().stream().map(Enum::name).sorted().toList();
    }

    /** Per-call context the executor supplies: resolution lookup, run identifiers and clock. */
    public record GatewayContext(UnaryOperator<String> lookup, String environment, String testRunId, Instant now) {

        public GatewayContext {
            Objects.requireNonNull(lookup, "lookup must not be null");
            Objects.requireNonNull(environment, "environment must not be null");
            Objects.requireNonNull(testRunId, "testRunId must not be null");
            Objects.requireNonNull(now, "now must not be null");
        }
    }

    /** Resolved physical-client inputs for the {@code ONF}/{@code VAD}/{@code SPU} chain. */
    private record IndividualInputs(String surName, String givenName, String middleName, String documentType,
                                    String servicePackage) {

        String fullName() {
            return (surName + ' ' + givenName + (middleName.isBlank() ? "" : ' ' + middleName)).trim();
        }
    }
}