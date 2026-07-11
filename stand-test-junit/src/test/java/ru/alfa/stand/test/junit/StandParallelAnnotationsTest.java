package ru.alfa.stand.test.junit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Pins the contract that JUnit honours the parallel-control facades ({@link StandParallelSafe},
 * {@link StandSerial}, {@link StandIsolated}) as meta-annotations. The JUnit platform resolves
 * {@code @Execution} and {@code @Isolated} through {@link AnnotationSupport} (meta-annotation aware), so
 * asserting the same API resolves the facades proves the engine will too — and guards against a silent
 * regression (a JUnit upgrade, or an accidental change to a facade's {@code @Target}/{@code @Retention})
 * turning {@code @StandIsolated} into a no-op that lets a class the docs told users to isolate race a sibling.
 */
class StandParallelAnnotationsTest {

    @Test
    @DisplayName("@StandParallelSafe is meta-resolved to @Execution(CONCURRENT)")
    void parallelSafeResolvesToConcurrent() {
        assertThat(AnnotationSupport.findAnnotation(ParallelSafeFixture.class, Execution.class))
                .map(Execution::value)
                .contains(ExecutionMode.CONCURRENT);
    }

    @Test
    @DisplayName("@StandSerial is meta-resolved to @Execution(SAME_THREAD)")
    void serialResolvesToSameThread() {
        assertThat(AnnotationSupport.findAnnotation(SerialFixture.class, Execution.class))
                .map(Execution::value)
                .contains(ExecutionMode.SAME_THREAD);
    }

    @Test
    @DisplayName("@StandIsolated is meta-resolved to @Isolated, so JUnit runs the class alone")
    void isolatedResolvesToIsolated() {
        assertThat(AnnotationSupport.isAnnotated(IsolatedFixture.class, Isolated.class)).isTrue();
    }

    @StandParallelSafe
    private static final class ParallelSafeFixture {
    }

    @StandSerial
    private static final class SerialFixture {
    }

    @StandIsolated
    private static final class IsolatedFixture {
    }
}
