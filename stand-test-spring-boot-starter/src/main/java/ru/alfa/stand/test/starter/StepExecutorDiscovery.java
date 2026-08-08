package ru.alfa.stand.test.starter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.execution.StepExecutor;

/**
 * The Spring path's executor list: declared beans, plus whatever the classpath registers through
 * {@link ServiceLoader} (ADR-UI-008, variant Б).
 *
 * <p>Without this the two ways of using the SDK disagreed, and only one of them was documented. On
 * plain JUnit the extension has always loaded executors through the SPI, so a module that ships
 * {@code META-INF/services} works with no configuration at all. The starter, by contrast, built the
 * runner from beans it declared by name — {@code rest}, {@code kafka}, {@code db}, {@code grpc} — so
 * {@code stand-test-ui}, which registers through the very same SPI, was invisible to it. The consumer
 * met {@code No step executor registered for step type 'ui.open'} and had nowhere to read why.
 *
 * <p>Discovery is deliberately <strong>not</strong> UI-specific: any adapter registered through the
 * SPI is picked up, including ones built outside this repository. A special case for
 * {@code UiStepExecutor} would have closed one instance of the problem and left the class of it.
 *
 * <p><strong>A declared bean always wins.</strong> Two mechanisms enforce that, because they cover
 * different cases:
 *
 * <ul>
 *   <li><em>Ordering</em> — beans are placed first, and the runner resolves a step type by scanning
 *       the list and taking the first executor that supports it. So even when a consumer's own class
 *       and an SPI provider both claim a type, the consumer's wins.</li>
 *   <li><em>Class de-duplication</em> — an SPI provider whose class is already among the beans is
 *       skipped, so the common case (the starter declares {@code RestStepExecutor} as a bean while
 *       {@code stand-test-rest} also registers it through the SPI) yields one instance, not two.</li>
 * </ul>
 *
 * <p>The risk this closes is worth naming precisely, because the backlog card described a different
 * one. Two executors claiming one step type would <em>not</em> run the step twice — the runner takes
 * the first match and stops. The real failure is quieter: a consumer who configured their own
 * {@code RestStepExecutor} bean could have had it silently replaced by the SPI default. Ordering is
 * what prevents that, and it is what the tests pin.
 *
 * <p>Nothing here creates a compile-time edge to any adapter, so the architecture rule forbidding a
 * dependency on {@code stand-test-ui} is untouched — that is the reason this variant was chosen over
 * an optional {@code starter → ui} dependency.
 */
public final class StepExecutorDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(StepExecutorDiscovery.class);

    /** Upper bound on service entries examined; a backstop against an entry that cannot be stepped over. */
    private static final int MAX_PROVIDERS = 64;

    private StepExecutorDiscovery() {
    }

    /**
     * Merges the declared executor beans with the ones the given class loader can discover.
     *
     * @param beans the executors declared as beans, in context order (never null)
     * @param classLoader the loader to search; the context's own, so that a consumer who filtered an
     *     adapter off the classpath does not get it back through the SPI
     * @return beans first, then the discovered executors that add something
     */
    public static List<StepExecutor> merge(List<StepExecutor> beans, ClassLoader classLoader) {
        Objects.requireNonNull(beans, "beans");
        return merge(beans, loadable(effectiveLoader(classLoader)));
    }

    /**
     * The loader to search, never {@code null}.
     *
     * <p>{@code ResourceLoader.getClassLoader()} is documented as nullable, and
     * {@code ServiceLoader.load(service, null)} does not fail on that — it searches the <em>bootstrap</em>
     * loader, where no adapter of this SDK can possibly live. Discovery would then find nothing and say
     * nothing, which is the one outcome worse than not having it: the consumer sees "no executor
     * registered" and every explanation in the documentation says it should have worked.
     *
     * @param supplied the loader handed in, possibly null
     * @return the supplied loader, or this class's own when it is null
     */
    private static ClassLoader effectiveLoader(ClassLoader supplied) {
        if (supplied != null) {
            return supplied;
        }
        LOG.debug("No class loader supplied for step-executor discovery; falling back to the one that loaded the starter");
        return StepExecutorDiscovery.class.getClassLoader();
    }

    /**
     * Every executor the loader can actually instantiate, with the ones it cannot skipped rather than
     * fatal.
     *
     * <p>A {@code META-INF/services} entry naming a class that will not load makes {@link ServiceLoader}
     * throw {@link ServiceConfigurationError} while merely <em>iterating</em>. Left alone, that error
     * comes out of a {@code @Bean} method and the application context does not start — so one stale
     * entry in some unrelated jar on the classpath would take down a consumer whose own adapters are
     * all declared as beans and who never asked for discovery at all. Convenience must not be able to
     * do that.
     *
     * <p>So an unloadable entry is a {@code WARN} and nothing more. Skipping it is not hiding the
     * problem: whatever step type that adapter would have served now has no executor, and the runner
     * says exactly that — {@code No step executor registered for step type '…'} — which is the same
     * loud failure a consumer got before this discovery existed.
     *
     * <p>Iteration continues past the entry it could not load, and that is measured rather than assumed:
     * the JDK consumes the provider name before attempting to load it, so the next {@code hasNext()}
     * moves on — {@code StarterDiscoversUiExecutorTest} asserts that the remaining adapters still arrive.
     * The bound on the loop is therefore a backstop, not the normal path: it exists so that a future
     * iterator which could <em>not</em> be stepped over would stop the search instead of hanging the
     * context, which would be the very outcome this method avoids.
     */
    private static List<StepExecutor> loadable(ClassLoader classLoader) {
        List<StepExecutor> found = new ArrayList<>();
        Iterator<StepExecutor> iterator = ServiceLoader.load(StepExecutor.class, classLoader).iterator();
        for (int guard = 0; guard < MAX_PROVIDERS; guard++) {
            try {
                if (!iterator.hasNext()) {
                    return found;
                }
                found.add(iterator.next());
            } catch (ServiceConfigurationError unloadable) {
                LOG.warn("A step-executor service entry on the classpath could not be loaded and is ignored;"
                        + " steps of its type will report that no executor is registered. Cause: {}", unloadable.getMessage());
            }
        }
        LOG.warn("Stopped looking for step executors after {} service entries — the classpath declares an implausible number of them,"
                + " or an entry cannot be stepped over. Executors declared as beans are unaffected.", MAX_PROVIDERS);
        return found;
    }

    /**
     * The merge itself, with the discovered executors supplied rather than loaded — the seam the unit
     * tests use, so the rules can be exercised without a {@code META-INF/services} file that would
     * then apply to every other test in this module.
     *
     * @param beans the executors declared as beans, in context order (never null)
     * @param discovered the executors offered by the service loader (never null)
     * @return beans first, then the discovered executors that add something
     */
    static List<StepExecutor> merge(List<StepExecutor> beans, Iterable<StepExecutor> discovered) {
        Objects.requireNonNull(beans, "beans");
        Objects.requireNonNull(discovered, "discovered");
        Set<Class<?>> declared = new LinkedHashSet<>();
        for (StepExecutor bean : beans) {
            declared.add(bean.getClass());
        }
        List<StepExecutor> merged = new ArrayList<>(beans);
        List<String> added = new ArrayList<>();
        for (StepExecutor candidate : discovered) {
            if (declared.contains(candidate.getClass())) {
                LOG.debug("Step executor {} is declared as a bean; the service-loader copy is ignored", candidate.getClass().getName());
                continue;
            }
            merged.add(candidate);
            added.add(candidate.getClass().getName());
        }
        if (!added.isEmpty()) {
            LOG.info("Step executors discovered on the classpath (no bean declared): {}."
                    + " A declared bean always takes precedence over a discovered executor.", added);
        }
        return List.copyOf(merged);
    }
}
