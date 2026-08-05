package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UiLoginFormConfigTest {

    @Test
    @DisplayName("a full form carries its path and four locator expressions, and reports itself fillable")
    void fullForm() {
        UiLoginFormConfig form = new UiLoginFormConfig(
                "/login", "testId=login-username", "testId=login-password", "role=button:Sign in", "testId=user-menu");

        assertThat(form.path()).isEqualTo("/login");
        assertThat(form.usernameLocator()).isEqualTo("testId=login-username");
        assertThat(form.signedInLocator()).isEqualTo("testId=user-menu");
        assertThat(form.fillable()).isTrue();
    }

    @Test
    @DisplayName("a section declaring only the signed-in marker is legal and is not fillable — the shape a prepared session needs")
    void reuseOnlyForm() {
        UiLoginFormConfig form = new UiLoginFormConfig(null, null, null, null, "testId=user-menu");

        assertThat(form.fillable()).isFalse();
        assertThat(form.path()).isNull();
    }

    @Test
    @DisplayName("a credential typed where a locator belongs is refused when the registry is read — the one plausible way this section grows a secret")
    void credentialInALocatorFieldIsRefused() {
        String password = "P@ssw0rd-2026";

        assertThatThrownBy(() -> new UiLoginFormConfig(null, null, password, null, "testId=user-menu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password-locator")
                .hasMessageContaining("<strategy>=<value>")
                .hasMessageContaining("never holds a credential")
                // The whole point: a check that printed the rejected value back would publish the mistake it
                // exists to catch into the build log and the CI artefact.
                .hasMessageNotContaining(password);
    }

    @Test
    @DisplayName("every locator field is checked for the strategy prefix, and a blank one is refused")
    void locatorShapeIsCheckedEverywhere() {
        assertThatThrownBy(() -> new UiLoginFormConfig(null, "login-username", null, null, "testId=user-menu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username-locator");
        assertThatThrownBy(() -> new UiLoginFormConfig(null, null, null, "  ", "testId=user-menu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("submit-locator");
        assertThatThrownBy(() -> new UiLoginFormConfig(null, null, null, null, "user-menu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed-in-locator");
        // The grammar itself belongs to the UI adapter; core only requires the prefix.
        assertThatCode(() -> new UiLoginFormConfig(null, null, null, null, "whatever=user-menu")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the sign-in path is relative, like every other address in the SDK: there is nowhere to put a stand URL")
    void pathMustBeRelative() {
        assertThatThrownBy(() -> new UiLoginFormConfig("https://portal.example.com/login", null, null, null, "testId=user-menu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
        assertThatThrownBy(() -> new UiLoginFormConfig("//portal.example.com/login", null, null, null, "testId=user-menu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
        assertThatThrownBy(() -> new UiLoginFormConfig(" ", null, null, null, "testId=user-menu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    @DisplayName("a login section that declares nothing is a mistake, not an empty default")
    void anEmptySectionIsRefused() {
        assertThatThrownBy(() -> new UiLoginFormConfig(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares nothing");
    }
}
