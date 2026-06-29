package ru.alfa.stand.test.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class ResourceScopeTest {

    @Test
    @DisplayName("registers, looks up and reports membership by key")
    void registerGetContains() {
        ResourceScope scope = new ResourceScope();
        TrackingCloseable resource = new TrackingCloseable("a", new ArrayList<>());

        scope.register("topic-a", resource);

        assertThat(scope.contains("topic-a")).isTrue();
        assertThat(scope.contains("topic-b")).isFalse();
        assertThat(scope.get("topic-a")).containsSame(resource);
        assertThat(scope.get("topic-b")).isEmpty();
    }

    @Test
    @DisplayName("a duplicate key registration is rejected")
    void duplicateRegistrationRejected() {
        ResourceScope scope = new ResourceScope();
        scope.register("k", new TrackingCloseable("a", new ArrayList<>()));

        assertThatThrownBy(() -> scope.register("k", new TrackingCloseable("b", new ArrayList<>())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("blank keys and null resources are rejected")
    void invalidArgumentsRejected() {
        ResourceScope scope = new ResourceScope();
        assertThatThrownBy(() -> scope.register(" ", new TrackingCloseable("a", new ArrayList<>())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.register("k", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scope.get(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.contains(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("closeAll closes every resource in registration order and clears the scope")
    void closeAllClosesEverythingAndClears() {
        List<String> closed = new ArrayList<>();
        ResourceScope scope = new ResourceScope();
        scope.register("first", new TrackingCloseable("first", closed));
        scope.register("second", new TrackingCloseable("second", closed));

        scope.closeAll();

        assertThat(closed).containsExactly("first", "second");
        assertThat(scope.contains("first")).isFalse();
        assertThat(scope.contains("second")).isFalse();
    }

    @Test
    @DisplayName("closeAll still closes the rest when one close throws, then rethrows the failure")
    void closeAllContinuesOnFailure() {
        List<String> closed = new ArrayList<>();
        ResourceScope scope = new ResourceScope();
        scope.register("first", new TrackingCloseable("first", closed, new IllegalStateException("boom")));
        scope.register("second", new TrackingCloseable("second", closed));

        assertThatThrownBy(scope::closeAll)
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to close");
        assertThat(closed).containsExactly("first", "second");
    }

    @Test
    @DisplayName("close() is an alias for closeAll() (try-with-resources)")
    void closeIsAlias() {
        List<String> closed = new ArrayList<>();
        try (ResourceScope scope = new ResourceScope()) {
            scope.register("only", new TrackingCloseable("only", closed));
        }
        assertThat(closed).containsExactly("only");
    }

    private static final class TrackingCloseable implements AutoCloseable {

        private final String name;
        private final List<String> closed;
        private final RuntimeException failure;

        TrackingCloseable(String name, List<String> closed) {
            this(name, closed, null);
        }

        TrackingCloseable(String name, List<String> closed, RuntimeException failure) {
            this.name = name;
            this.closed = closed;
            this.failure = failure;
        }

        @Override
        public void close() {
            closed.add(name);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
