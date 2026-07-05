package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthConfigTest {

    @Test
    @DisplayName("basic() carries the username/password references and no token reference")
    void basic_carriesUserAndPasswordRefs() {
        AuthConfig auth = AuthConfig.basic("CLIENT_USER", "CLIENT_PASSWORD");

        assertThat(auth.scheme()).isEqualTo(AuthScheme.BASIC);
        assertThat(auth.usernameRef()).isEqualTo("CLIENT_USER");
        assertThat(auth.passwordRef()).isEqualTo("CLIENT_PASSWORD");
        assertThat(auth.tokenRef()).isNull();
    }

    @Test
    @DisplayName("bearer() carries the token reference and no username/password references")
    void bearer_carriesTokenRef() {
        AuthConfig auth = AuthConfig.bearer("CLIENT_TOKEN");

        assertThat(auth.scheme()).isEqualTo(AuthScheme.BEARER);
        assertThat(auth.tokenRef()).isEqualTo("CLIENT_TOKEN");
        assertThat(auth.usernameRef()).isNull();
        assertThat(auth.passwordRef()).isNull();
    }

    @Test
    @DisplayName("a null scheme is rejected")
    void nullScheme_isRejected() {
        assertThatThrownBy(() -> new AuthConfig(null, "U", "P", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    @DisplayName("basic auth requires both the username and the password reference")
    void basic_missingRefs_areRejected() {
        assertThatThrownBy(() -> new AuthConfig(AuthScheme.BASIC, null, "P", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usernameRef");
        assertThatThrownBy(() -> new AuthConfig(AuthScheme.BASIC, "U", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordRef");
    }

    @Test
    @DisplayName("basic auth must not carry a token reference")
    void basic_withTokenRef_isRejected() {
        assertThatThrownBy(() -> new AuthConfig(AuthScheme.BASIC, "U", "P", "T"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenRef");
    }

    @Test
    @DisplayName("bearer auth requires the token reference")
    void bearer_missingTokenRef_isRejected() {
        assertThatThrownBy(() -> new AuthConfig(AuthScheme.BEARER, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenRef");
        assertThatThrownBy(() -> new AuthConfig(AuthScheme.BEARER, null, null, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenRef");
    }

    @Test
    @DisplayName("bearer auth must not carry username or password references")
    void bearer_withBasicRefs_isRejected() {
        assertThatThrownBy(() -> new AuthConfig(AuthScheme.BEARER, "U", null, "T"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usernameRef");
        assertThatThrownBy(() -> new AuthConfig(AuthScheme.BEARER, null, "P", "T"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordRef");
    }
}
