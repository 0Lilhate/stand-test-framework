package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class InProcessAccountPoolTest {

    private static final UiAccount CLIENT_1 = new UiAccount("portal-client-1", "client", "C1_USERNAME", "C1_PASSWORD");

    private static final UiAccount CLIENT_2 = new UiAccount("portal-client-2", "client", "C2_USERNAME", "C2_PASSWORD");

    private static final UiAccount MANAGER = new UiAccount("portal-manager-1", "manager", "M1_USERNAME", "M1_PASSWORD");

    @Test
    @DisplayName("a request for a role is answered with an account of that role — the whole point of asking by role")
    void leaseReturnsAccountOfRequestedRole() {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(CLIENT_1, CLIENT_2, MANAGER));

        try (LeasedAccount manager = pool.lease("client-portal", "manager", Duration.ofSeconds(1))) {
            assertThat(manager.role()).isEqualTo("manager");
            assertThat(manager.accountId()).isEqualTo("portal-manager-1");
            assertThat(manager.usernameRef()).isEqualTo("M1_USERNAME");
            assertThat(manager.passwordRef()).isEqualTo("M1_PASSWORD");
            assertThat(manager.waited()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
        try (LeasedAccount client = pool.lease("client-portal", "client", Duration.ofSeconds(1))) {
            assertThat(client.role()).isEqualTo("client");
        }
    }

    @Test
    @DisplayName("a closed lease returns the account, so the very next request gets it again")
    void closingALeaseReturnsTheAccount() {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(MANAGER));

        String first;
        try (LeasedAccount lease = pool.lease("client-portal", "manager", Duration.ofSeconds(1))) {
            first = lease.accountId();
        }
        try (LeasedAccount second = pool.lease("client-portal", "manager", Duration.ofMillis(200))) {
            assertThat(second.accountId()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("closing a lease twice returns the account once — the resource scope closes it, and defensive code may too")
    void closingTwiceReturnsTheAccountOnce() {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(MANAGER));
        LeasedAccount lease = pool.lease("client-portal", "manager", Duration.ofSeconds(1));

        lease.close();
        lease.close();

        try (LeasedAccount held = pool.lease("client-portal", "manager", Duration.ofMillis(200))) {
            assertThat(held.accountId()).isEqualTo("portal-manager-1");
            // If the double close had returned the account twice, the pool would now hand out a second copy.
            assertThatThrownBy(() -> pool.lease("client-portal", "manager", Duration.ofMillis(50)))
                    .isInstanceOf(StandTestException.class)
                    .hasMessageContaining("became free");
        }
    }

    @Test
    @DisplayName("an exhausted pool fails within the timeout, as broken, naming application, role, size and timeout — it never hangs")
    void exhaustedPoolFailsWithinBoundedTimeout() {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(MANAGER));

        try (LeasedAccount held = pool.lease("client-portal", "manager", Duration.ofSeconds(1))) {
            assertThat(held.accountId()).isNotBlank();
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> pool.lease("client-portal", "manager", Duration.ofMillis(150)))
                    .isInstanceOf(StandTestException.class)
                    .hasMessageContaining("client-portal")
                    .hasMessageContaining("manager")
                    .hasMessageContaining("PT0.15S")
                    .hasMessageContaining("1 account(s)");

            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(waited).as("the wait must be bounded by the declared timeout, not by the JVM's patience").isLessThan(Duration.ofSeconds(5));
            assertThat(waited).as("it must actually have waited rather than failed immediately").isGreaterThanOrEqualTo(Duration.ofMillis(100));
        }
    }

    @Test
    @DisplayName("a role the pool does not hold fails at once rather than burning the timeout — that is a configuration error, not contention")
    void unknownRoleFailsImmediately() {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(CLIENT_1));
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> pool.lease("client-portal", "auditor", Duration.ofSeconds(30)))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("no account of role 'auditor'")
                .hasMessageContaining("client");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("two runs never hold the same account: eight threads racing for two client accounts each get a distinct one")
    void twoConcurrentRunsNeverShareAnAccount() throws InterruptedException {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(CLIENT_1, CLIENT_2));
        int runs = 8;
        Set<String> heldNow = ConcurrentHashMap.newKeySet();
        List<String> collisions = new ArrayList<>();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(runs);
        ExecutorService threads = Executors.newFixedThreadPool(runs);

        try {
            for (int index = 0; index < runs; index++) {
                threads.execute(() -> {
                    try {
                        start.await();
                        try (LeasedAccount lease = pool.lease("client-portal", "client", Duration.ofSeconds(10))) {
                            if (!heldNow.add(lease.accountId())) {
                                synchronized (collisions) {
                                    collisions.add(lease.accountId());
                                }
                            }
                            Thread.sleep(5);
                            heldNow.remove(lease.accountId());
                            completed.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("every run must finish: waiting is bounded and accounts come back").isTrue();
        } finally {
            threads.shutdownNow();
        }

        assertThat(collisions).as("two runs held the same account at the same time — the pool's only promise is broken").isEmpty();
        assertThat(completed).hasValue(runs);
        assertThat(pool.size("client")).isEqualTo(2);
    }

    @Test
    @DisplayName("a pool must hold at least one account, and account ids must be unique — ids key the saved session")
    void malformedRostersAreRejected() {
        assertThatThrownBy(() -> new InProcessAccountPool(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one account");
        assertThatThrownBy(() -> new InProcessAccountPool(List.of(CLIENT_1, new UiAccount("portal-client-1", "manager", "X_USERNAME", "X_PASSWORD"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears more than once");
        assertThatCode(() -> new InProcessAccountPool(List.of(CLIENT_1))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ids that differ only in case are a collision: they become one file name, and one account would restore another's session")
    void accountIdsCollideIgnoringCase() {
        UiAccount upper = new UiAccount("Portal-Client-1", "manager", "U_USERNAME", "U_PASSWORD");

        assertThatThrownBy(() -> new InProcessAccountPool(List.of(CLIENT_1, upper)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ignoring case")
                .hasMessageContaining("session state");
    }

    @Test
    @DisplayName("a null role accepts any account — meaningful only for an application that declares no roles")
    void anyRoleLease() {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(MANAGER));

        try (LeasedAccount lease = pool.lease("client-portal", null, Duration.ofSeconds(1))) {
            assertThat(lease.accountId()).isEqualTo("portal-manager-1");
        }
        assertThat(pool.size(null)).isEqualTo(1);
    }

    @Test
    @DisplayName("a non-positive lease timeout is refused: there is no spelling of 'wait forever' and none of 'do not wait'")
    void timeoutMustBePositive() {
        InProcessAccountPool pool = new InProcessAccountPool(List.of(MANAGER));

        assertThatThrownBy(() -> pool.lease("client-portal", "manager", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
        assertThatThrownBy(() -> pool.lease("client-portal", "manager", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }
}
