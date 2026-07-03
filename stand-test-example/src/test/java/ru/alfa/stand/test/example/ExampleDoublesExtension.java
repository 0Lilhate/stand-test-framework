package ru.alfa.stand.test.example;

import java.net.URI;
import java.util.Objects;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Starts the in-process doubles the {@code @StandTest} examples need: the H2 schema (bootstrapped
 * out-of-band via raw JDBC, since DDL is forbidden through the SDK write-guard) and the HTTP double. The
 * double binds the fixed port pinned by the build's {@code CLIENT_SERVICE_URL} env-ref, which the
 * registry loaded from {@code application.yml} ({@code stand.test.environments} section,
 * stand-test-config's {@code FileEnvironmentRegistry} SPI provider) also resolves through
 * {@code System.getenv} — so the registry never needs to know the runtime address. No
 * {@code Thread.sleep}: both doubles start synchronously.
 *
 * <p><strong>Engine-root lifecycle.</strong> {@code StandTestExtension} caches the {@code StandClient}
 * at engine-root scope (once per run), so the HTTP double lives in the SAME scope: it is created at most
 * once per run in the root {@link ExtensionContext.Store} and closed by JUnit when the whole run ends —
 * any number of {@code @StandTest} example classes can share the cached client without racing the
 * double's lifecycle. The double's canned response ({@code standtest-1}) is therefore shared by every
 * {@code @StandTest} class too.
 */
final class ExampleDoublesExtension implements BeforeAllCallback {

    private static final Namespace NAMESPACE = Namespace.create(ExampleDoublesExtension.class);

    private static final String BASE_URL_ENV = "CLIENT_SERVICE_URL";

    private static final int RESPONSE_STATUS = 200;

    private static final String RESPONSE_BODY = "{\"requestId\":\"standtest-1\"}";

    @Override
    public void beforeAll(ExtensionContext context) {
        ExampleH2.createOrdersTable();
        context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(SharedHttpDouble.class, key -> new SharedHttpDouble(), SharedHttpDouble.class);
    }

    /**
     * The run-scoped HTTP double: constructed lazily in the root store and closed by JUnit at the end of
     * the whole run — exactly when the engine-scoped {@code StandClient} cache dies.
     */
    private static final class SharedHttpDouble implements ExtensionContext.Store.CloseableResource {

        private final ExampleHttpServer server;

        private SharedHttpDouble() {
            String baseUrl = Objects.requireNonNull(
                    System.getenv(BASE_URL_ENV), BASE_URL_ENV + " must be set (see stand-test-example/build.gradle.kts)");
            int port = URI.create(baseUrl).getPort();
            if (port < 0) {
                throw new IllegalStateException(BASE_URL_ENV + " must include an explicit port: " + baseUrl);
            }
            this.server = new ExampleHttpServer(RESPONSE_STATUS, RESPONSE_BODY, port);
        }

        @Override
        public void close() {
            this.server.close();
        }
    }
}
