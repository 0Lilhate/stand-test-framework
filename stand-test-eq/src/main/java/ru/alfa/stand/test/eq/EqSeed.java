package ru.alfa.stand.test.eq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.scenario.GenericStep;

/**
 * Lazy builder for an {@code eq.seed} step. It creates only an immutable scenario model and performs no IO.
 */
public final class EqSeed {

    /** The registry alias used unless {@link #backend(String)} overrides it. */
    public static final String DEFAULT_BACKEND = "eq";

    private final String alias;
    private final ClientKind clientKind;
    private final List<EqAccount> accounts = new ArrayList<>();
    private final EnumSet<EqAttribute> approximations = EnumSet.noneOf(EqAttribute.class);
    private String id;
    private String backend = DEFAULT_BACKEND;
    private String name;
    private String servicePackage;

    private EqSeed(String alias, ClientKind clientKind) {
        this.alias = requireNonBlank(alias, "alias");
        this.clientKind = clientKind;
    }

    /** Begins a request to seed an organisation. */
    public static EqSeed organisation(String alias) {
        return new EqSeed(alias, ClientKind.ORGANISATION);
    }

    /** Begins a request to seed an individual. */
    public static EqSeed individual(String alias) {
        return new EqSeed(alias, ClientKind.INDIVIDUAL);
    }

    /** Sets an explicit step id. */
    public EqSeed id(String value) {
        this.id = requireNonBlank(value, "id");
        return this;
    }

    /** Selects a logical backend alias from the environment registry. */
    public EqSeed backend(String value) {
        this.backend = requireNonBlank(value, "backend");
        return this;
    }

    /** Sets a client name; when omitted the backend uses its configured default. */
    public EqSeed name(String value) {
        this.name = requireNonBlank(value, "name");
        return this;
    }

    /** Adds an account to the client request. */
    public EqSeed account(EqAccount value) {
        this.accounts.add(Objects.requireNonNull(value, "account must not be null"));
        return this;
    }

    /** Sets the individual client's service package. */
    public EqSeed servicePackage(String value) {
        this.servicePackage = requireNonBlank(value, "servicePackage");
        return this;
    }

    /** Explicitly permits selected backend approximations. */
    public EqSeed allowApproximation(EqAttribute... values) {
        Objects.requireNonNull(values, "attributes must not be null");
        Arrays.stream(values).forEach(value -> approximations.add(Objects.requireNonNull(value, "attribute must not be null")));
        return this;
    }

    /** Materialises the immutable scenario step without executing it. */
    public GenericStep build() {
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("eq.seed requires at least one account");
        }
        if (clientKind == ClientKind.INDIVIDUAL && accounts.stream().anyMatch(account -> account.servicePackage() != null)) {
            throw new IllegalArgumentException("individual service package belongs to the client, not an account");
        }
        if (clientKind == ClientKind.ORGANISATION && servicePackage != null) {
            throw new IllegalArgumentException("organisation service package belongs to an account");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(EqStepParameters.BACKEND, backend);
        parameters.put(EqStepParameters.ALIAS, alias);
        parameters.put(EqStepParameters.CLIENT_KIND, clientKind.wireValue);
        parameters.put(EqStepParameters.ACCOUNTS, accounts.stream().map(EqAccount::toParameters).toList());
        if (name != null) {
            parameters.put(EqStepParameters.NAME, name);
        }
        if (servicePackage != null) {
            parameters.put(EqStepParameters.SERVICE_PACKAGE, servicePackage);
        }
        if (!approximations.isEmpty()) {
            parameters.put(EqStepParameters.ALLOW_APPROXIMATION, approximations.stream().map(Enum::name).toList());
        }
        String stepId = id == null ? EqStepParameters.STEP_TYPE + ':' + alias : id;
        return new GenericStep(stepId, EqStepParameters.STEP_TYPE, "Seed EQ client " + alias, parameters);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private enum ClientKind {
        ORGANISATION("organisation"),
        INDIVIDUAL("individual");

        private final String wireValue;

        ClientKind(String wireValue) {
            this.wireValue = wireValue;
        }
    }
}
