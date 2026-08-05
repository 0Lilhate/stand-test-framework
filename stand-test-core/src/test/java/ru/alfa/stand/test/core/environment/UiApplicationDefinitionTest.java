package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UiApplicationDefinitionTest {

    private static final Map<String, ViewportProfile> PROFILES =
            Map.of("desktop", new ViewportProfile(1440, 900), "mobile", new ViewportProfile(390, 844));

    @Test
    @DisplayName("an application carries its alias, base-url reference, viewport profiles, trace mode and auth")
    void fullDefinition() {
        UiAuthConfig auth = new UiAuthConfig(
                UiAuthScheme.FORM,
                "PORTAL_TEST_USERS",
                List.of("client", "operator"),
                "PORTAL_DISCOVERY",
                new UiLoginFormConfig("/login", "testId=login-username", "testId=login-password", "role=button:Sign in", "testId=user-menu"),
                UiLoginChallenge.NONE);
        UiApplicationDefinition application = new UiApplicationDefinition(
                "client-portal", "CLIENT_PORTAL_URL", "desktop", PROFILES, UiTraceMode.ON_FAILURE, auth);

        assertThat(application.alias()).isEqualTo("client-portal");
        assertThat(application.baseUrlRef()).isEqualTo("CLIENT_PORTAL_URL");
        assertThat(application.viewportProfile("mobile")).contains(new ViewportProfile(390, 844));
        assertThat(application.viewportProfile("tablet")).isEmpty();
        assertThat(application.defaultViewportProfile()).contains(new ViewportProfile(1440, 900));
        assertThat(application.trace()).isEqualTo(UiTraceMode.ON_FAILURE);
        assertThat(application.auth()).isEqualTo(auth);
    }

    @Test
    @DisplayName("the minimal shape needs only an alias and a base-url reference; trace defaults to the safe OFF")
    void minimalDefinition() {
        UiApplicationDefinition application = new UiApplicationDefinition("client-portal", "CLIENT_PORTAL_URL");

        assertThat(application.trace()).isEqualTo(UiTraceMode.OFF);
        assertThat(application.viewportProfiles()).isEmpty();
        assertThat(application.defaultViewportProfile()).isEmpty();
        assertThat(application.auth()).isNull();
        assertThat(new UiApplicationDefinition("a", "REF", null, null, null, null).trace()).isEqualTo(UiTraceMode.OFF);
    }

    @Test
    @DisplayName("a blank alias or base-url reference is rejected")
    void blankFields_areRejected() {
        assertThatThrownBy(() -> new UiApplicationDefinition(" ", "CLIENT_PORTAL_URL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
        assertThatThrownBy(() -> new UiApplicationDefinition("client-portal", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrlRef");
    }

    @Test
    @DisplayName("a default viewport naming an undeclared profile is rejected — the profile whitelist stays closed")
    void defaultViewportOutsideProfiles_isRejected() {
        assertThatThrownBy(() -> new UiApplicationDefinition("client-portal", "REF", "tablet", PROFILES, UiTraceMode.OFF, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tablet")
                .hasMessageContaining("viewport-profiles");
        assertThatThrownBy(() -> new UiApplicationDefinition("client-portal", "REF", " ", PROFILES, UiTraceMode.OFF, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultViewport");
    }

    @Test
    @DisplayName("the viewport-profile map is defensively copied — a later mutation of the source cannot change the definition")
    void viewportProfiles_areCopied() {
        Map<String, ViewportProfile> source = new HashMap<>(Map.of("desktop", new ViewportProfile(1440, 900)));
        UiApplicationDefinition application = new UiApplicationDefinition("client-portal", "REF", "desktop", source, UiTraceMode.OFF, null);

        source.put("mobile", new ViewportProfile(390, 844));

        assertThat(application.viewportProfiles()).containsOnlyKeys("desktop");
        assertThatThrownBy(() -> application.viewportProfiles().put("mobile", new ViewportProfile(390, 844)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a viewport with a non-positive dimension is rejected")
    void viewportDimensions_mustBePositive() {
        assertThatThrownBy(() -> new ViewportProfile(0, 900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> new ViewportProfile(1440, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height");
    }
}
