package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class AccountRosterTest {

    @Test
    @DisplayName("the two-field form derives the credential variables from the account id, which is what makes a roster of ten writable")
    void twoFieldEntriesDeriveVariableNames() {
        List<UiAccount> accounts = AccountRoster.parse("portal-client-1:client;portal-manager-1:manager", "client-portal", "POOL", null);

        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0)).isEqualTo(new UiAccount("portal-client-1", "client", "PORTAL_CLIENT_1_USERNAME", "PORTAL_CLIENT_1_PASSWORD"));
        assertThat(accounts.get(1)).isEqualTo(new UiAccount("portal-manager-1", "manager", "PORTAL_MANAGER_1_USERNAME", "PORTAL_MANAGER_1_PASSWORD"));
    }

    @Test
    @DisplayName("the four-field form spells the variables out, for accounts somebody else named")
    void fourFieldEntriesUseExplicitVariableNames() {
        List<UiAccount> accounts = AccountRoster.parse("acc-1:client:LEGACY_LOGIN:LEGACY_SECRET", "client-portal", "POOL", null);

        assertThat(accounts).containsExactly(new UiAccount("acc-1", "client", "LEGACY_LOGIN", "LEGACY_SECRET"));
    }

    @Test
    @DisplayName("entries may be separated by newlines as well as semicolons, and blank ones are ignored")
    void separatorsAndWhitespace() {
        List<UiAccount> accounts = AccountRoster.parse("  a-1:client \n\n b-2:manager ;; ", "client-portal", "POOL", null);

        assertThat(accounts).extracting(UiAccount::accountId).containsExactly("a-1", "b-2");
    }

    @Test
    @DisplayName("an unset or blank roster variable says which variable is missing and what it should contain")
    void emptyRosterIsRejected() {
        assertThatThrownBy(() -> AccountRoster.parse(null, "client-portal", "CLIENT_PORTAL_ACCOUNTS", null))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("CLIENT_PORTAL_ACCOUNTS")
                .hasMessageContaining("<accountId>:<role>");
        assertThatThrownBy(() -> AccountRoster.parse("   ", "client-portal", "CLIENT_PORTAL_ACCOUNTS", null))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("CLIENT_PORTAL_ACCOUNTS");
    }

    @Test
    @DisplayName("an entry with three fields is refused: two or four, and nothing in between, so the grammar stays unambiguous")
    void wrongFieldCountIsRejected() {
        assertThatThrownBy(() -> AccountRoster.parse("a-1:client:ONLY_LOGIN", "client-portal", "POOL", null))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Entry #1")
                .hasMessageContaining("has 3");
    }

    @Test
    @DisplayName("a rejected field value is never echoed — the one way a credential reaches this parser is being typed where a variable name belongs")
    void rejectedValuesAreNeverEchoed() {
        String password = "P@ssw0rd-that-must-not-be-printed";

        assertThatThrownBy(() -> AccountRoster.parse("a-1:client:LOGIN:" + password, "client-portal", "POOL", null))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("passwordVariable")
                .hasMessageContaining("Entry #1")
                .hasMessageNotContaining(password);
        assertThatThrownBy(() -> AccountRoster.parse("a-1:client;b 2 with spaces:client", "client-portal", "POOL", null))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Entry #2")
                .hasMessageNotContaining("b 2 with spaces");
    }

    @Test
    @DisplayName("a ${VAR} placeholder is refused in a roster field: ':' separates the fields, so a placeholder default would be ambiguous")
    void placeholdersAreRejected() {
        assertThatThrownBy(() -> AccountRoster.parse("a-1:client:${LOGIN}:${SECRET}", "client-portal", "POOL", null))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("bare environment-variable NAME");
    }

    @Test
    @DisplayName("SEC-10: the exploration account must not also be in the working pool, and a configuration that puts it there is refused")
    void discoveryAccountIsNotDrawnFromPool() {
        assertThatThrownBy(() -> AccountRoster.parse("a-1:client:PORTAL_DISCOVERY:A1_PASSWORD", "client-portal", "POOL", "PORTAL_DISCOVERY"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("SEC-10")
                .hasMessageContaining("PORTAL_DISCOVERY");
        assertThatThrownBy(() -> AccountRoster.parse("portal-discovery:client", "client-portal", "POOL", "portal-discovery"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("outside the pool");
    }

    @Test
    @DisplayName("a roster holding no credential value can be described in full, so an account inventory is safe to print")
    void describeNamesReferencesOnly() {
        UiAccount account = AccountRoster.parse("a-1:client", "client-portal", "POOL", null).get(0);

        assertThat(account.describe()).contains("a-1", "client", "A_1_USERNAME", "A_1_PASSWORD");
    }
}
