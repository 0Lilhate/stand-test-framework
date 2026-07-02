package ru.alfa.stand.test.grpc;

import ru.alfa.stand.test.core.environment.EnvironmentRegistry;

/**
 * Tiny fixture selecting a whitelisted-environment registry with a gRPC target that either has a METADATA
 * correlation carrier or none, keeping the executor test readable.
 */
final class GrpcTargetDefinitionFixture {

    private final EnvironmentRegistry registry;

    private GrpcTargetDefinitionFixture(EnvironmentRegistry registry) {
        this.registry = registry;
    }

    static GrpcTargetDefinitionFixture withCorrelation() {
        return new GrpcTargetDefinitionFixture(GrpcTestSupport.registry(GrpcTestSupport.metadataTarget()));
    }

    static GrpcTargetDefinitionFixture withoutCorrelation() {
        return new GrpcTargetDefinitionFixture(GrpcTestSupport.registry(GrpcTestSupport.targetWithoutCorrelation()));
    }

    EnvironmentRegistry registry() {
        return this.registry;
    }
}
