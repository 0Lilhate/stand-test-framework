package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.environment.UiTraceMode;

/** Fixtures for the sign-in tests: an application that signs in, its account roster, and their variables. */
final class UiLoginTestSupport {

    static final String POOL_REF = "CLIENT_PORTAL_ACCOUNTS";

    static final String DISCOVERY_REF = "CLIENT_PORTAL_DISCOVERY";

    /** Two client accounts and one manager — enough to prove exclusivity without making a test slow. */
    static final String ROSTER = "portal-client-1:client;portal-client-2:client;portal-manager-1:manager";

    static final UiLocator USERNAME = UiLocator.testId("login-username");

    static final UiLocator PASSWORD = UiLocator.testId("login-password");

    static final UiLocator SUBMIT = UiLocator.role("button", "Sign in");

    static final UiLocator SIGNED_IN = UiLocator.testId("user-menu");

    static final UiLoginFormConfig FORM = new UiLoginFormConfig(
            "/login", "testId=login-username", "testId=login-password", "role=button:Sign in", "testId=user-menu");

    /** A storage-state application with no form to fall back to: the MFA case ADR-UI-006 leaves to a prepared session. */
    static final UiLoginFormConfig REUSE_ONLY_FORM = new UiLoginFormConfig(null, null, null, null, "testId=user-menu");

    private UiLoginTestSupport() {
    }

    static UiApplicationDefinition application(UiAuthScheme scheme) {
        return application(scheme, FORM, UiLoginChallenge.NONE, List.of("client", "manager"));
    }

    static UiApplicationDefinition application(UiAuthScheme scheme, UiLoginFormConfig form, UiLoginChallenge challenge, List<String> roles) {
        UiAuthConfig auth = (scheme == UiAuthScheme.NONE)
                ? UiAuthConfig.none()
                : new UiAuthConfig(scheme, POOL_REF, roles, DISCOVERY_REF, form, challenge);
        return new UiApplicationDefinition(UiTestSupport.APPLICATION, UiTestSupport.BASE_URL_REF, null, Map.of(), UiTraceMode.OFF, auth);
    }

    /**
     * The environment variables a sign-in resolves: the base URL, the account roster and one pair of
     * credentials per rostered account. Nothing here reaches configuration — that is the point of the two
     * levels of indirection this fixture reproduces.
     */
    static UnaryOperator<String> variables() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(UiTestSupport.BASE_URL_REF, "http://localhost:8080");
        values.put(POOL_REF, ROSTER);
        values.put("PORTAL_CLIENT_1_USERNAME", "portal.client.one");
        values.put("PORTAL_CLIENT_1_PASSWORD", "s3cret-one-!");
        values.put("PORTAL_CLIENT_2_USERNAME", "portal.client.two");
        values.put("PORTAL_CLIENT_2_PASSWORD", "s3cret-two-!");
        values.put("PORTAL_MANAGER_1_USERNAME", "portal.manager.one");
        values.put("PORTAL_MANAGER_1_PASSWORD", "s3cret-manager-!");
        return values::get;
    }

    static UiRunSettings settings(Path artifacts) {
        return UiRunSettings.fromProperties(property -> UiRunSettings.ARTIFACTS_DIRECTORY_PROPERTY.equals(property) ? artifacts.toString() : null);
    }
}
