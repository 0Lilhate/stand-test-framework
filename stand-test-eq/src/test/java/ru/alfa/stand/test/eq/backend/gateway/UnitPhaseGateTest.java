package ru.alfa.stand.test.eq.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class UnitPhaseGateTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Test
    void cachesTheReadForTheTtlAndRefreshesAfterIt() {
        AtomicInteger reads = new AtomicInteger();
        MutableClock clock = new MutableClock(NOW);
        UnitPhaseGate gate = new UnitPhaseGate((system, unit, user, password) -> {
            reads.incrementAndGet();
            return "ACTIVE";
        }, clock);

        assertThat(gate.currentPhase("SYS", "USER", "secret", "K68", Duration.ofMinutes(5))).isEqualTo("ACTIVE");
        assertThat(gate.currentPhase("SYS", "USER", "secret", "K68", Duration.ofMinutes(5))).isEqualTo("ACTIVE");
        assertThat(reads.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(6));
        gate.currentPhase("SYS", "USER", "secret", "K68", Duration.ofMinutes(5));
        assertThat(reads.get()).isEqualTo(2);
    }

    @Test
    void aPhaseOutsideAllowedIsAnInfrastructureRefusalNamingUnitAndPhaseWithoutCredentials() {
        UnitPhaseGate gate = new UnitPhaseGate((system, unit, user, password) -> "CLOSED", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> gate.requireWorkingPhase("K68", "CLOSED", List.of("ACTIVE", "READY")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("K68").hasMessageContaining("CLOSED").hasMessageContaining("ACTIVE")
                .hasMessageNotContaining("secret");
    }

    @Test
    void anEmptyAllowedListRefusesEverything() {
        UnitPhaseGate gate = new UnitPhaseGate((system, unit, user, password) -> "ACTIVE", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> gate.requireWorkingPhase("K68", "ACTIVE", List.of()))
                .hasMessageContaining("allowed is empty");
    }

    @Test
    void aReaderFailureDoesNotLeakTheCredential() {
        UnitPhaseGate gate = new UnitPhaseGate((system, unit, user, password) -> {
            throw new StandTestException("jt400 rejected user " + user);
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> gate.currentPhase("SYS", "the-user", "the-password", "K68", Duration.ofMinutes(5)))
                .isInstanceOf(StandTestException.class).hasMessageContaining("the-user");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}