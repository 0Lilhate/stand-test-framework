package ru.alfa.stand.test.grpc;

import com.jayway.jsonpath.DocumentContext;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.GrpcTargetDefinition;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableResolver;

/**
 * gRPC {@link StepExecutor}: the single point of real gRPC IO to a stand (step type {@code grpc.unary}).
 *
 * <p>Discovered via {@link java.util.ServiceLoader} (registered in {@code META-INF/services}); the class
 * is public with a public no-arg constructor for that reason. It is stateless and thread-safe — all
 * per-run state arrives through the {@link StepExecutionContext} (the variable store and the run-scoped
 * {@link ResourceScope}), so one instance is safely shared across concurrent runs.
 *
 * <p><strong>Flow.</strong> The target alias is resolved to a {@link GrpcTargetDefinition} via the
 * {@code EnvironmentRegistry} (fail-closed whitelist); its {@code targetRef} is resolved to a
 * {@code host:port} via a {@link ReferenceResolver} (addresses stay out of source, plan §9); a
 * {@link ManagedChannel} is created once per alias and kept in the {@code ResourceScope} (closed by the
 * runner). Custom metadata plus the SDK-owned correlation id (METADATA carrier) are injected, the request
 * JSON is resolved ({@code ${...}}), the unary call runs under the mandatory deadline, and the response
 * JSON is asserted/captured with the shared JSONPath model.
 *
 * <p><strong>Failure semantics (plan §8.3).</strong> A failed JSONPath assertion or a non-JSON/empty
 * response is raised as a {@link StandTestAssertionError}; a configuration/infrastructure problem (unknown
 * alias, unresolved reference, correlation carrier other than METADATA, descriptor/reflection failure,
 * unresolved {@code ${...}}) or a gRPC {@link StatusRuntimeException} (deadline exceeded, unavailable, …)
 * is raised as a {@link StandTestException} that preserves the gRPC status code.
 */
public final class GrpcStepExecutor implements StepExecutor {

    /**
     * Namespace prefix for this adapter's {@link ResourceScope} keys, so a gRPC target alias can never
     * collide with another adapter's resource registered under the same logical alias in one run
     * (mirrors the DB adapter's {@code db.datasource:} convention).
     */
    private static final String CHANNEL_KEY_PREFIX = "grpc.channel:";

    private final GrpcChannelFactory channelFactory;
    private final ReferenceResolver referenceResolver;
    private final GrpcCallInvoker invoker;

    /**
     * Creates an executor with the default channel factory, environment reference resolver and the
     * reflection-based call invoker.
     */
    public GrpcStepExecutor() {
        this(new DefaultGrpcChannelFactory(), new EnvironmentReferenceResolver(), new DefaultGrpcCallInvoker());
    }

    /**
     * Creates an executor with explicit collaborators (for tests).
     *
     * @param channelFactory the channel factory
     * @param referenceResolver the target reference resolver
     * @param invoker the unary call invoker
     */
    GrpcStepExecutor(GrpcChannelFactory channelFactory, ReferenceResolver referenceResolver, GrpcCallInvoker invoker) {
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory must not be null");
        this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver must not be null");
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
    }

    @Override
    public boolean supports(String stepType) {
        return stepType != null && stepType.startsWith(GrpcStepParameters.TYPE_PREFIX);
    }

    @Override
    public void prepare(ScenarioStep step, StepExecutionContext context) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!GrpcOperation.UNARY.stepType().equals(step.type())) {
            return;
        }
        Map<String, Object> parameters = parameters(step);
        String targetAlias = GrpcStepParameters.requireString(parameters, GrpcStepParameters.TARGET);
        GrpcTargetDefinition target = resolveTarget(context, targetAlias);
        ensureChannel(context, targetAlias, target);
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!GrpcOperation.UNARY.stepType().equals(step.type())) {
            throw new StandTestException("GrpcStepExecutor cannot handle step type '" + step.type() + "'");
        }
        final Instant startedAt = Instant.now();
        Map<String, Object> parameters = parameters(step);
        String targetAlias = GrpcStepParameters.requireString(parameters, GrpcStepParameters.TARGET);
        String methodFullName = GrpcStepParameters.requireString(parameters, GrpcStepParameters.METHOD_FULL_NAME);
        long deadlineMillis = GrpcStepParameters.requirePositiveMillis(parameters, GrpcStepParameters.DEADLINE_MILLIS);
        GrpcTargetDefinition target = resolveTarget(context, targetAlias);
        ManagedChannel channel = ensureChannel(context, targetAlias, target);
        VariableResolver resolver = context.resolver();
        Map<String, String> customMetadata = resolveValues(GrpcStepParameters.stringMap(parameters, GrpcStepParameters.METADATA), resolver);
        String correlationId = correlationId(parameters, target, targetAlias, context);
        Metadata metadata = buildMetadata(customMetadata, target, correlationId);
        String requestJson = resolveRequest(parameters, resolver);
        String responseJson = invoke(channel, methodFullName, requestJson, metadata, deadlineMillis, targetAlias);
        List<GrpcAssertion> assertions = GrpcStepParameters.assertions(parameters);
        List<GrpcCapture> captures = GrpcStepParameters.captures(parameters);
        if (!assertions.isEmpty() || !captures.isEmpty()) {
            DocumentContext document = ResponseAssertions.parse(responseJson);
            ResponseAssertions.verify(assertions, document);
            ResponseAssertions.applyCaptures(captures, document, context.variableStore());
        }
        return success(step, startedAt, targetAlias, methodFullName, deadlineMillis, correlationId, customMetadata, requestJson, responseJson);
    }

    private String invoke(ManagedChannel channel, String methodFullName, String requestJson, Metadata metadata, long deadlineMillis, String targetAlias) {
        try {
            return this.invoker.invokeUnary(channel, methodFullName, requestJson, metadata, deadlineMillis);
        } catch (StatusRuntimeException status) {
            String description = (status.getStatus().getDescription() == null) ? "" : ": " + status.getStatus().getDescription();
            throw new StandTestException("gRPC call to '" + targetAlias + "' method '" + methodFullName + "' failed with status " + status.getStatus().getCode() + description, status);
        }
    }

    private GrpcTargetDefinition resolveTarget(StepExecutionContext context, String targetAlias) {
        String environment = context.scenarioContext().environment();
        EnvironmentDefinition definition = context.environmentRegistry()
                .environment(environment)
                .orElseThrow(() -> new StandTestException("Environment '" + environment + "' is not whitelisted"));
        return definition.grpcTarget(targetAlias)
                .orElseThrow(() -> new StandTestException("gRPC target '" + targetAlias + "' is not whitelisted in environment '" + environment + "'"));
    }

    private ResolvedGrpcTarget resolve(GrpcTargetDefinition target) {
        return new ResolvedGrpcTarget(this.referenceResolver.resolve(target.targetRef()));
    }

    private ManagedChannel ensureChannel(StepExecutionContext context, String targetAlias, GrpcTargetDefinition target) {
        ResourceScope scope = context.resourceScope();
        String key = CHANNEL_KEY_PREFIX + targetAlias;
        Optional<AutoCloseable> existing = scope.get(key);
        if (existing.isPresent()) {
            if (existing.get() instanceof ManagedChannelResource resource) {
                return resource.channel();
            }
            throw new StandTestException("Run-scoped resource under key '" + key + "' is not a gRPC channel");
        }
        // Resolve the target reference (an env-var lookup) only when a channel must actually be created,
        // so a cache hit does not re-read the environment on every step.
        ManagedChannel channel = this.channelFactory.create(resolve(target));
        scope.register(key, new ManagedChannelResource(channel));
        return channel;
    }

    private static String correlationId(Map<String, Object> parameters, GrpcTargetDefinition target, String targetAlias, StepExecutionContext context) {
        if (!GrpcStepParameters.flag(parameters, GrpcStepParameters.INJECT_CORRELATION_ID)) {
            return null;
        }
        CorrelationConfig correlation = target.correlation();
        if (correlation == null || correlation.source() != CorrelationSource.METADATA) {
            throw new StandTestException("Correlation id injection was requested for gRPC target '" + targetAlias + "', but it has no METADATA correlation config");
        }
        return context.scenarioContext().correlationId().value();
    }

    private static Metadata buildMetadata(Map<String, String> customMetadata, GrpcTargetDefinition target, String correlationId) {
        Metadata metadata = new Metadata();
        customMetadata.forEach((name, value) -> metadata.put(metadataKey(name), value));
        if (correlationId != null) {
            metadata.put(metadataKey(target.correlation().name()), correlationId);
        }
        return metadata;
    }

    private static Metadata.Key<String> metadataKey(String name) {
        try {
            return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
        } catch (IllegalArgumentException invalid) {
            throw new StandTestException("Invalid gRPC metadata key name '" + name + "': " + invalid.getMessage(), invalid);
        }
    }

    private static Map<String, String> resolveValues(Map<String, String> source, VariableResolver resolver) {
        Map<String, String> resolved = new LinkedHashMap<>();
        source.forEach((name, value) -> resolved.put(name, resolver.resolve(value)));
        return resolved;
    }

    private static String resolveRequest(Map<String, Object> parameters, VariableResolver resolver) {
        Optional<String> resource = GrpcStepParameters.optionalString(parameters, GrpcStepParameters.REQUEST_RESOURCE);
        Optional<String> inline = GrpcStepParameters.optionalString(parameters, GrpcStepParameters.REQUEST);
        if (resource.isPresent() && inline.isPresent()) {
            throw new StandTestException("A gRPC step must set either '" + GrpcStepParameters.REQUEST + "' or '" + GrpcStepParameters.REQUEST_RESOURCE + "', not both");
        }
        if (resource.isPresent()) {
            return resolver.resolve(readResource(resource.get()));
        }
        return inline.map(resolver::resolve).orElse(null);
    }

    private static String readResource(String resourcePath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = GrpcStepExecutor.class.getClassLoader();
        }
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new StandTestException("Request payload resource not found on classpath: '" + resourcePath + "'");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StandTestException("Failed to read request payload resource '" + resourcePath + "'", failure);
        }
    }

    private static Map<String, Object> parameters(ScenarioStep step) {
        if (step instanceof GenericStep generic) {
            return generic.parameters();
        }
        throw new StandTestException("GrpcStepExecutor requires a GenericStep produced by GrpcStep, but got: " + step.getClass().getName());
    }

    private static StepResult success(ScenarioStep step, Instant startedAt, String targetAlias, String methodFullName, long deadlineMillis, String correlationId, Map<String, String> metadata, String requestJson, String responseJson) {
        final Instant finishedAt = Instant.now();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("grpc.operation", "unary");
        diagnostics.put("grpc.target", targetAlias);
        diagnostics.put("grpc.method", methodFullName);
        diagnostics.put("grpc.deadlineMillis", deadlineMillis);
        if (correlationId != null) {
            diagnostics.put("grpc.correlationId", correlationId);
        }
        if (!metadata.isEmpty()) {
            diagnostics.put("grpc.metadata", maskedMetadata(metadata));
        }
        diagnostics.put("grpc.elapsedMillis", Duration.between(startedAt, finishedAt).toMillis());
        List<Attachment> attachments = new ArrayList<>();
        if (requestJson != null) {
            attachments.add(Attachment.of("grpc-request", "application/json", requestJson));
        }
        attachments.add(Attachment.of("grpc-response", "application/json", responseJson));
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, finishedAt, null, diagnostics, attachments);
    }

    private static Map<String, String> maskedMetadata(Map<String, String> metadata) {
        Map<String, String> masked = new LinkedHashMap<>();
        metadata.forEach((name, value) -> masked.put(name, SecretMetadata.maskIfSecret(name, value)));
        return masked;
    }
}
