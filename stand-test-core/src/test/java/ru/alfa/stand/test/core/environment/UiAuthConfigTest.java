package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UiAuthConfigTest {

    private static final UiLoginFormConfig LOGIN_FORM = new UiLoginFormConfig(
            "/login", "testId=login-username", "testId=login-password", "role=button:Sign in", "testId=user-menu");

    @Test
    @DisplayName("credentials are references and roles are copied immutably")
    void referencesAndRoles() {
        List<String> roles = new ArrayList<>(List.of("client", "operator"));
        UiAuthConfig auth = new UiAuthConfig(UiAuthScheme.FORM, "PORTAL_TEST_USERS", roles, "PORTAL_DISCOVERY", LOGIN_FORM, UiLoginChallenge.NONE);

        roles.add("admin");

        assertThat(auth.scheme()).isEqualTo(UiAuthScheme.FORM);
        assertThat(auth.credentialsPoolRef()).isEqualTo("PORTAL_TEST_USERS");
        assertThat(auth.discoveryAccountRef()).isEqualTo("PORTAL_DISCOVERY");
        assertThat(auth.roles()).containsExactly("client", "operator");
        assertThatThrownBy(() -> auth.roles().add("admin")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a null scheme is rejected — 'scheme' is the required key, spelled as it is for services")
    void schemeIsRequired() {
        assertThatThrownBy(() -> new UiAuthConfig(null, "POOL", List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    @DisplayName("scheme NONE must not carry credentials — a half-edited section is a configuration error, not a silent ignore")
    void noneCarriesNoCredentials() {
        assertThat(UiAuthConfig.none().roles()).isEmpty();
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.NONE, "POOL", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE");
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.NONE, null, List.of(), "DISCOVERY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE");
    }

    @Test
    @DisplayName("roles are requested from a pool, so declaring roles without a credentials pool is rejected")
    void rolesRequireAPool() {
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.FORM, null, List.of("client"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentialsPoolRef");
    }

    @Test
    @DisplayName("blank and duplicate roles, and blank references, are rejected")
    void malformedValues_areRejected() {
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.FORM, "POOL", List.of("client", " "), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roles");
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.FORM, "POOL", List.of("client", "client"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.FORM, " ", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentialsPoolRef");
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.FORM, "POOL", List.of(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discoveryAccountRef");
    }

    @Test
    @DisplayName("a scheme that signs in through the browser must be given a pool and a login section — a half-written auth section fails when the registry is read, not when the browser is already open")
    void signingInRequiresPoolAndLoginSection() {
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.FORM, null, List.of(), null, LOGIN_FORM, UiLoginChallenge.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentialsPoolRef");
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.FORM, "POOL", List.of(), null, null, UiLoginChallenge.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed-in-locator");
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.STORAGE_STATE, "POOL", List.of(), null, new UiLoginFormConfig("/login", null, null, null, null), UiLoginChallenge.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("login.signed-in-locator");
        assertThatThrownBy(() -> new UiAuthConfig(
                UiAuthScheme.FORM, "POOL", List.of(), null, new UiLoginFormConfig(null, null, null, null, "testId=user-menu"), UiLoginChallenge.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("login.username-locator, login.password-locator and login.submit-locator");
    }

    @Test
    @DisplayName("STORAGE_STATE needs only the signed-in marker: the form elements are optional, and declaring them is what enables the fallback after a session expires")
    void storageStateNeedsOnlyTheSignedInMarker() {
        UiAuthConfig reuseOnly = new UiAuthConfig(
                UiAuthScheme.STORAGE_STATE, "POOL", List.of(), null, new UiLoginFormConfig(null, null, null, null, "testId=user-menu"), UiLoginChallenge.NONE);

        assertThat(reuseOnly.login().fillable()).isFalse();
        assertThat(reuseOnly.challenge()).isEqualTo(UiLoginChallenge.NONE);
        assertThat(new UiAuthConfig(UiAuthScheme.STORAGE_STATE, "POOL", List.of(), null, LOGIN_FORM, null).login().fillable()).isTrue();
    }

    @Test
    @DisplayName("scheme NONE cannot meet a challenge or declare a login form — both are credentials of a kind")
    void noneCarriesNoLoginFormOrChallenge() {
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.NONE, null, List.of(), null, LOGIN_FORM, UiLoginChallenge.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("login form");
        assertThatThrownBy(() -> new UiAuthConfig(UiAuthScheme.NONE, null, List.of(), null, null, UiLoginChallenge.MFA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("challenge");
    }

    @Test
    @DisplayName("roles declared means a scenario must name one; no roles declared means it need not")
    void rolesDeclared() {
        assertThat(new UiAuthConfig(UiAuthScheme.FORM, "POOL", List.of("client"), null, LOGIN_FORM, null).rolesDeclared()).isTrue();
        assertThat(new UiAuthConfig(UiAuthScheme.FORM, "POOL", List.of(), null, LOGIN_FORM, null).rolesDeclared()).isFalse();
        assertThat(UiAuthConfig.none().rolesDeclared()).isFalse();
    }

    @Test
    @DisplayName("trace: an unquoted YAML 'off' arrives as the boolean false and means OFF; 'true' has no meaning and is refused")
    void traceModeFromConfig() {
        assertThat(UiTraceMode.fromConfig(null)).isEqualTo(UiTraceMode.OFF);
        assertThat(UiTraceMode.fromConfig(Boolean.FALSE)).isEqualTo(UiTraceMode.OFF);
        assertThat(UiTraceMode.fromConfig("false")).isEqualTo(UiTraceMode.OFF);
        assertThat(UiTraceMode.fromConfig("off")).isEqualTo(UiTraceMode.OFF);
        assertThat(UiTraceMode.fromConfig("OFF")).isEqualTo(UiTraceMode.OFF);
        assertThat(UiTraceMode.fromConfig("on-failure")).isEqualTo(UiTraceMode.ON_FAILURE);
        assertThat(UiTraceMode.fromConfig("ON_FAILURE")).isEqualTo(UiTraceMode.ON_FAILURE);
        assertThat(UiTraceMode.fromConfig(UiTraceMode.ON_FAILURE)).isEqualTo(UiTraceMode.ON_FAILURE);

        assertThatThrownBy(() -> UiTraceMode.fromConfig(Boolean.TRUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'off' or 'on-failure'");
        assertThatThrownBy(() -> UiTraceMode.fromConfig("on"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'off' or 'on-failure'");
        assertThatThrownBy(() -> UiTraceMode.fromConfig(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'off' or 'on-failure'");
    }
}
