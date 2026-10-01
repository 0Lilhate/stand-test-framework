package ru.alfa.stand.test.eq;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentSection;
import ru.alfa.stand.test.core.environment.SectionEntry;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;
import ru.alfa.stand.test.eq.backend.CapabilityMatrix;
import ru.alfa.stand.test.eq.backend.SeedExecution;
import ru.alfa.stand.test.eq.backend.SeedPlan;
import ru.alfa.stand.test.eq.backend.SeedResult;
import ru.alfa.stand.test.eq.backend.gateway.GatewayBackend;
import ru.alfa.stand.test.eq.backend.gateway.GatewayQueue;
import ru.alfa.stand.test.eq.backend.gateway.GatewaySettings;
import ru.alfa.stand.test.eq.backend.gateway.UnitPhaseGate;
import ru.alfa.stand.test.eq.backend.showcases.ShowcasesBackend;
import ru.alfa.stand.test.eq.config.EqBackendConfig;
import ru.alfa.stand.test.eq.config.EqBackendConfigParser;
import ru.alfa.stand.test.eq.config.EqDefaults;
import ru.alfa.stand.test.eq.config.GatewayBackendConfig;
import ru.alfa.stand.test.eq.config.ShowcasesBackendConfig;
import ru.alfa.stand.test.eq.ids.EqIdGenerator;
import ru.alfa.stand.test.eq.report.SeedJournal;
import ru.alfa.stand.test.eq.report.SeedLog;
import ru.alfa.stand.test.eq.visibility.VisibilityProbe;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.http.AuthHeaderResolver;
import ru.alfa.stand.test.http.BaseUrlResolver;
import ru.alfa.stand.test.http.EnvironmentAuthHeaderResolver;
import ru.alfa.stand.test.http.EnvironmentBaseUrlResolver;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.WebClientHttpCaller;

/** Executor for {@code eq.seed}, dispatching to the selected environment backend. */
public final class EqStepExecutor implements StepExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EqStepExecutor.class);

    private final ShowcasesBackend showcases;
    private final GatewayBackend gateway;
    private final VisibilityProbe visibilityProbe;
    private final UnitPhaseGate unitPhaseGate;
    private final UnaryOperator<String> lookup;
    private final Clock clock;

    public EqStepExecutor() {
        this(new WebClientHttpCaller(), new EnvironmentBaseUrlResolver(), new EnvironmentAuthHeaderResolver(),
                Clock.systemUTC(), SeedJournal.shared(), GatewayQueue.Shared.instance(), UnitPhaseGate.Shared.instance(), System::getenv);
    }

    /** Creates an executor with transport seams for offline tests. */
    public EqStepExecutor(HttpCaller caller, BaseUrlResolver baseUrlResolver, AuthHeaderResolver authHeaderResolver,
                          Clock clock) {
        this(caller, baseUrlResolver, authHeaderResolver, clock, new SeedJournal(java.nio.file.Paths.get(SeedJournal.DEFAULT_DIRECTORY)),
                new GatewayQueue(), new UnitPhaseGate((system, unit, user, password) -> "ACTIVE", clock), System::getenv);
    }

    /**
     * Creates an executor with every seam supplied: transport, journal, gateway queue, unit-phase gate and
     * the reference lookup (production passes {@code System::getenv}).
     */
    public EqStepExecutor(HttpCaller caller, BaseUrlResolver baseUrlResolver, AuthHeaderResolver authHeaderResolver,
                          Clock clock, SeedJournal journal, GatewayQueue queue, UnitPhaseGate unitPhaseGate,
                          UnaryOperator<String> lookup) {
        this.showcases = new ShowcasesBackend(caller, baseUrlResolver, authHeaderResolver, journal, clock);
        this.gateway = new GatewayBackend(caller, authHeaderResolver, queue, journal);
        this.visibilityProbe = new VisibilityProbe(caller, baseUrlResolver, authHeaderResolver, Awaiter.create());
        this.unitPhaseGate = Objects.requireNonNull(unitPhaseGate, "unitPhaseGate must not be null");
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean supports(String stepType) {
        return EqStepParameters.STEP_TYPE.equals(stepType);
    }

    @Override
    public void prepare(ScenarioStep step, StepExecutionContext context) {
        SeedPlan rawPlan = SeedPlan.from(step);
        EnvironmentDefinition environment = context.environmentRegistry()
                .environment(context.scenarioContext().environment())
                .orElseThrow(() -> new StandTestException("Unknown EQ environment"));
        EnvironmentSection section = environment.section(StepParameterKeys.EQ_BACKENDS_SECTION)
                .orElseThrow(() -> new StandTestException("Missing EQ backend section in environment '"
                        + environment.name() + "'"));
        SectionEntry entry = section.entries().get(rawPlan.backend());
        if (entry == null) {
            throw new StandTestException("EQ backend alias '" + rawPlan.backend() + "' is not whitelisted");
        }
        EqBackendConfig backend = EqBackendConfigParser.parse(environment.name(), entry);
        LocalDate today = LocalDate.now(clock);
        int ordinal = context.stepOrdinal(step.id());
        String marker = new EqIdGenerator(context.scenarioContext().testRunId().value(), ordinal).pin();
        SeedPlan plan = rawPlan.withDefaults(defaultsOf(backend), marker);
        Set<EqAttribute> approximated = CapabilityMatrix.validate(plan, backend, today);
        VisibilityProbe.Prepared probe = null;
        GatewaySettings gatewaySettings = null;
        if (backend instanceof ShowcasesBackendConfig showcasesConfig) {
            requireShowcasesInputs(plan);
            if (environment.service(showcasesConfig.service()).isEmpty()) {
                throw new StandTestException("EQ service alias '" + showcasesConfig.service() + "' is not whitelisted");
            }
            probe = showcasesConfig.visibility() == null ? null
                    : visibilityProbe.prepare(showcasesConfig.visibility(), environment, context);
        } else if (backend instanceof GatewayBackendConfig gatewayConfig) {
            if (!gatewayConfig.writeAllowed()) {
                throw new StandTestException("EQ_WRITE_NOT_ALLOWED: gateway backend '" + gatewayConfig.alias()
                        + "' requires write-allowed: true in environment '" + environment.name() + "' (SEC-01)");
            }
            gatewaySettings = GatewaySettings.resolve(gatewayConfig, lookup);
            requireGatewayInputs(plan, gatewaySettings);
            prepareUnitPhase(gatewayConfig, gatewaySettings.unit());
            probe = gatewayConfig.visibility() == null ? null
                    : visibilityProbe.prepare(gatewayConfig.visibility(), environment, context);
        }
        context.resourceScope().register("eq.alias:" + plan.alias(), () -> { });
        context.resourceScope().register(preparedKey(step),
                new Prepared(plan, backend, gatewaySettings, ordinal, approximated, probe));
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        final Instant startedAt = clock.instant();
        Prepared prepared = (Prepared) context.resourceScope().get(preparedKey(step))
                .orElseThrow(() -> new StandTestException("eq.seed was not prepared: " + step.id()));
        SeedExecution execution = prepared.gatewaySettings() == null
                ? showcases.seed(prepared.plan(), (ShowcasesBackendConfig) prepared.backend(), context,
                        prepared.stepNumber(), LocalDate.now(clock))
                : gateway.seed(prepared.plan(), (GatewayBackendConfig) prepared.backend(),
                        new GatewayBackend.GatewayContext(lookup, context.scenarioContext().environment(),
                                context.scenarioContext().testRunId().value(), clock.instant()));
        SeedResult result = execution.result();
        // The PIN/account are safe maintenance metadata (they also go to eq-seeded.jsonl) — logging them
        // lets an operator find the EQ client created by a run without opening the journal file.
        LOGGER.info("EQ seed '{}' (env={}, backend={}) created client: pin={}, accounts={}",
                prepared.plan().alias(), context.scenarioContext().environment(), prepared.backend().alias(),
                result.pin(), result.accounts());
        if (prepared.probe() != null) {
            try {
                visibilityProbe.await(prepared.probe(), result);
            } catch (EqSeedException failure) {
                throw failure.withLog(execution.log());
            }
        }
        String alias = prepared.plan().alias();
        context.variableStore().put(alias + ".pin", result.pin());
        context.variableStore().put(alias + ".account", result.accounts().get(0));
        for (int index = 0; index < result.accounts().size(); index++) {
            context.variableStore().put(alias + ".account." + index, result.accounts().get(index));
        }
        result.deals().forEach((index, deal) -> context.variableStore().put(alias + ".deal." + index, deal));
        SeedLog log = execution.log();
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, clock.instant(), null,
                Map.of("eq.backend", prepared.backend().alias(), "eq.accounts", result.accounts().size(),
                        "eq.approximated", prepared.approximated().size()),
                List.of(Attachment.of("eq-seed", "text/plain", log.toText())));
    }

    private void prepareUnitPhase(GatewayBackendConfig gatewayConfig, String unit) {
        GatewayBackendConfig.UnitPhase phase = gatewayConfig.unitPhase();
        if (phase == null) {
            return;
        }
        String system = resolveReference(phase.systemReference(), "unit-phase.system-ref");
        String username = resolveReference(phase.usernameReference(), "unit-phase.username-ref");
        String password = resolveReference(phase.passwordReference(), "unit-phase.password-ref");
        String current = unitPhaseGate.currentPhase(system, username, password, unit, phase.cacheTtl());
        unitPhaseGate.requireWorkingPhase(unit, current, phase.allowed());
    }

    private String resolveReference(String reference, String field) {
        String value = ru.alfa.stand.test.core.environment.SecretReferences.resolveOrLiteral(reference, lookup);
        if (value == null || value.isBlank()) {
            throw new StandTestException("EQ gateway field '" + field + "' is not set");
        }
        return value;
    }

    private static ru.alfa.stand.test.eq.config.EqDefaults defaultsOf(EqBackendConfig backend) {
        if (backend instanceof ShowcasesBackendConfig showcasesConfig) {
            return showcasesConfig.defaults();
        }
        return ((GatewayBackendConfig) backend).defaults();
    }

    private static void requireShowcasesInputs(SeedPlan plan) {
        if (plan.name() == null) {
            throw new StandTestException("eq.seed name needs a DSL value or registry defaults.organisation.name-prefix");
        }
        for (SeedPlan.Account account : plan.accounts()) {
            if (account.type() == null || account.currency() == null) {
                throw new StandTestException("eq.seed account type and currency are required for showcases");
            }
            if (!EqIdGenerator.supportsCurrency(account.currency())) {
                throw new StandTestException("eq.seed showcases does not support currency '" + account.currency() + "'");
            }
        }
    }

    private static void requireGatewayInputs(SeedPlan plan, GatewaySettings settings) {
        boolean individual = "individual".equals(plan.clientKind());
        if (individual) {
            if (settings.defaults() == null || settings.defaults().individual() == null) {
                throw new StandTestException("EQ gateway individual seed requires defaults.individual"
                        + " (last-name, first-name, document-type)");
            }
            EqDefaults.IndividualDefaults individualDefaults = settings.defaults().individual();
            if (plan.name() == null
                    && (individualDefaults.surName() == null || individualDefaults.givenName() == null)) {
                throw new StandTestException("eq.seed name needs a DSL value or defaults.individual last-name/first-name");
            }
            if (individualDefaults.documentType() == null) {
                throw new StandTestException("EQ gateway individual seed requires defaults.individual.document-type (GZDUL)");
            }
        } else {
            if (plan.name() == null) {
                throw new StandTestException("eq.seed name needs a DSL value or registry defaults.organisation.name-prefix");
            }
            if (settings.defaults() == null || settings.defaults().organisationType() == null) {
                throw new StandTestException("EQ gateway requires defaults.organisation.type (GZCTP)");
            }
        }
        for (SeedPlan.Account account : plan.accounts()) {
            String currency = account.currency() == null ? settings.defaults().currency() : account.currency();
            if (currency == null) {
                throw new StandTestException("EQ gateway account currency is required");
            }
            if (!settings.cashAccounts().containsKey(currency)) {
                throw new StandTestException("EQ gateway has no cash account for currency '" + currency
                        + "' (BR-24); declare eq-backends." + settings.alias() + ".cash-accounts." + currency);
            }
        }
    }

    private static String preparedKey(ScenarioStep step) {
        return "eq.prepared:" + step.id();
    }

    private record Prepared(SeedPlan plan, EqBackendConfig backend, GatewaySettings gatewaySettings, int stepNumber,
                            Set<EqAttribute> approximated, VisibilityProbe.Prepared probe) implements AutoCloseable {
        @Override
        public void close() {
        }
    }

}