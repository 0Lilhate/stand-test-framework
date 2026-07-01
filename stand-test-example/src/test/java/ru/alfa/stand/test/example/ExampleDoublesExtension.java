package ru.alfa.stand.test.example;

import java.net.URI;
import java.util.Objects;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Starts the in-process doubles the {@code @StandTest} example needs, once per test class: the H2 schema
 * (bootstrapped out-of-band via raw JDBC, since DDL is forbidden through the SDK write-guard) and the
 * HTTP double. The double binds the fixed port pinned by the build's {@code CLIENT_SERVICE_URL} env-ref,
 * which the discovered {@link ExampleEnvironmentRegistry} also resolves through {@code System.getenv} —
 * so the registry never needs to know the runtime address. No {@code Thread.sleep}: both doubles start
 * synchronously.
 *
 * <p><strong>Scope assumption.</strong> {@code StandTestExtension} caches the {@code StandClient} at
 * engine-root scope (once per run), while this extension's HTTP double lives at test-class scope. That is
 * safe only while a single {@code @StandTest} class exists in the module: a second such class running
 * after this one would inherit the cached client but find the double already stopped (connection refused).
 * Introduce a shared run-scoped double lifecycle before adding a second {@code @StandTest} example.
 */
final class ExampleDoublesExtension implements BeforeAllCallback, AfterAllCallback {

    private static final String BASE_URL_ENV = "CLIENT_SERVICE_URL";
    private static final int RESPONSE_STATUS = 200;
    private static final String RESPONSE_BODY = "{\"requestId\":\"standtest-1\"}";

    private ExampleHttpServer server;

    @Override
    public void beforeAll(ExtensionContext context) {
        ExampleH2.createOrdersTable();
        String baseUrl = Objects.requireNonNull(
                System.getenv(BASE_URL_ENV), BASE_URL_ENV + " must be set (see stand-test-example/build.gradle.kts)");
        int port = URI.create(baseUrl).getPort();
        if (port < 0) {
            throw new IllegalStateException(BASE_URL_ENV + " must include an explicit port: " + baseUrl);
        }
        this.server = new ExampleHttpServer(RESPONSE_STATUS, RESPONSE_BODY, port);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (this.server != null) {
            this.server.close();
        }
    }
}
