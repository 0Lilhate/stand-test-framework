package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class UiAccountPoolsTest {

    private static final List<UiAccount> ROSTER = List.of(
            new UiAccount("portal-client-1", "client", "C1_USERNAME", "C1_PASSWORD"),
            new UiAccount("portal-client-2", "client", "C2_USERNAME", "C2_PASSWORD"));

    @Test
    @DisplayName("every run of one application in one environment leases from the same pool — that is what makes exclusivity mean anything")
    void oneApplicationHasOnePool() {
        UiAccountPools pools = new UiAccountPools();

        AccountPool first = pools.forApplication("ift", "client-portal", ROSTER);
        AccountPool second = pools.forApplication("ift", "client-portal", ROSTER);

        assertThat(first).isSameAs(second);
        try (LeasedAccount held = first.lease("client-portal", "client", Duration.ofSeconds(1));
             LeasedAccount other = second.lease("client-portal", "client", Duration.ofSeconds(1))) {
            assertThat(held.accountId()).isNotEqualTo(other.accountId());
        }
    }

    @Test
    @DisplayName("the same application in two environments is two pools: the pool is per stand, and so are its accounts")
    void environmentsDoNotShareAPool() {
        UiAccountPools pools = new UiAccountPools();

        assertThat(pools.forApplication("ift", "client-portal", ROSTER))
                .isNotSameAs(pools.forApplication("dev", "client-portal", ROSTER));
    }

    @Test
    @DisplayName("a second roster for one application is refused, not given a pool of its own — two pools would each lease 'exclusively' while sharing an account")
    void secondRosterForOneApplicationIsRefused() {
        UiAccountPools pools = new UiAccountPools();
        pools.forApplication("ift", "client-portal", ROSTER);
        List<UiAccount> overlapping = List.of(
                new UiAccount("portal-client-1", "client", "C1_USERNAME", "C1_PASSWORD"),
                new UiAccount("portal-manager-1", "manager", "M1_USERNAME", "M1_PASSWORD"));

        assertThatThrownBy(() -> pools.forApplication("ift", "client-portal", overlapping))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("client-portal")
                .hasMessageContaining("changed within one JVM")
                .hasMessageContaining("portal-client-1:client")
                .hasMessageContaining("portal-manager-1:manager");
    }

    @Test
    @DisplayName("the roster fingerprint in that message names accounts and roles only — a roster holds no credential, so the diagnosis is safe to print")
    void theConflictMessageCarriesNoCredential() {
        UiAccountPools pools = new UiAccountPools();
        pools.forApplication("ift", "client-portal", ROSTER);

        assertThatThrownBy(() -> pools.forApplication("ift", "client-portal", List.of(ROSTER.get(0))))
                .isInstanceOf(StandTestException.class)
                .hasMessageNotContaining("C1_PASSWORD")
                .hasMessageNotContaining("C2_PASSWORD");
    }

    @Test
    @DisplayName("the shared registry is the one production uses, and it is one per JVM")
    void theSharedRegistryIsASingleton() {
        assertThat(UiAccountPools.shared()).isSameAs(UiAccountPools.shared());
    }
}
