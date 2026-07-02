package ru.alfa.stand.test.grpc;

import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * A parsed fully-qualified gRPC method name, {@code package.Service/Method}.
 *
 * <p>{@code serviceName} is the fully-qualified service (used to look the service up via Server
 * Reflection) and {@code methodName} is the method within it. {@code fullMethodName} is the
 * {@code service/method} form gRPC's {@code io.grpc.MethodDescriptor} expects.
 *
 * @param serviceName the fully-qualified service name (never blank)
 * @param methodName the method name within the service (never blank)
 */
public record GrpcMethodName(String serviceName, String methodName) {

    public GrpcMethodName {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
    }

    /**
     * Parses a {@code package.Service/Method} string into its service and method parts.
     *
     * @param methodFullName the fully-qualified method name
     * @return the parsed name
     * @throws StandTestException if the name is not of the form {@code package.Service/Method}
     */
    public static GrpcMethodName parse(String methodFullName) {
        if (methodFullName == null || methodFullName.isBlank()) {
            throw new StandTestException("gRPC method name must not be blank");
        }
        int slash = methodFullName.indexOf('/');
        if (slash < 0 || methodFullName.indexOf('/', slash + 1) >= 0) {
            throw new StandTestException("gRPC method name must be 'package.Service/Method' but was: '" + methodFullName + "'");
        }
        String service = methodFullName.substring(0, slash);
        String method = methodFullName.substring(slash + 1);
        if (service.isBlank() || method.isBlank()) {
            throw new StandTestException("gRPC method name must be 'package.Service/Method' but was: '" + methodFullName + "'");
        }
        return new GrpcMethodName(service, method);
    }

    /**
     * Returns the {@code service/method} form used by {@code io.grpc.MethodDescriptor}.
     *
     * @return the {@code service/method} full method name
     */
    public String fullMethodName() {
        return serviceName + "/" + methodName;
    }
}
