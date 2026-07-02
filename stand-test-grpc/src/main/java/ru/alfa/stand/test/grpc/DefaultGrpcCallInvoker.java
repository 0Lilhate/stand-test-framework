package ru.alfa.stand.test.grpc;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link GrpcCallInvoker} implementing decision A of the plan: resolve the method descriptor over
 * gRPC <strong>Server Reflection</strong>, build the request from JSON into a
 * {@link DynamicMessage}, invoke the unary method generically (a protobuf-marshalled
 * {@link MethodDescriptor} + {@link ClientCalls#blockingUnaryCall}), and render the response back to JSON.
 * No generated client stubs are needed, so a fully declarative scenario can call any reflection-enabled
 * service and assert over the response with the same JSONPath model as REST/Kafka.
 *
 * <p>The whole call — reflection lookup plus the unary RPC — is bounded by a single {@link Deadline}
 * derived once from {@code deadlineMillis}, so the step never waits longer than its declared deadline
 * (reflection does not get a separate, additive budget).
 *
 * <p>A descriptor/reflection failure (reflection disabled on the server, unknown service/method,
 * malformed descriptor) is a {@link StandTestException}; a transport/status failure surfaces as the gRPC
 * {@link StatusRuntimeException} thrown by {@link ClientCalls#blockingUnaryCall} and is left to the
 * executor to map (preserving the status code). The reflection client speaks {@code grpc.reflection.v1};
 * a server that exposes only {@code v1alpha} is reported with an explanatory hint. If reflection is
 * unavailable entirely, the fallback is a consumer-supplied {@code FileDescriptorSet} (plan §Risks /
 * fallback B), a later extension of this seam.
 */
public final class DefaultGrpcCallInvoker implements GrpcCallInvoker {

    @Override
    public String invokeUnary(Channel channel, String methodFullName, String requestJson, Metadata metadata, long deadlineMillis) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        GrpcMethodName name = GrpcMethodName.parse(methodFullName);
        Deadline deadline = Deadline.after(deadlineMillis, TimeUnit.MILLISECONDS);
        Descriptors.MethodDescriptor method = resolveMethod(channel, name, deadline);
        DynamicMessage request = DynamicMessages.fromJson(method.getInputType(), requestJson);
        MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(name.fullMethodName())
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getOutputType())))
                .build();
        Channel intercepted = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
        CallOptions callOptions = CallOptions.DEFAULT.withDeadline(deadline);
        DynamicMessage response = ClientCalls.blockingUnaryCall(intercepted, grpcMethod, callOptions, request);
        return DynamicMessages.toJson(response);
    }

    private Descriptors.MethodDescriptor resolveMethod(Channel channel, GrpcMethodName name, Deadline deadline) {
        List<FileDescriptorProto> protos = fetchDescriptors(channel, name.serviceName(), deadline);
        Descriptors.ServiceDescriptor service = findService(protos, name.serviceName());
        Descriptors.MethodDescriptor method = service.findMethodByName(name.methodName());
        if (method == null) {
            throw new StandTestException("gRPC method '" + name.methodName() + "' not found on service '" + name.serviceName() + "'");
        }
        return method;
    }

    private List<FileDescriptorProto> fetchDescriptors(Channel channel, String serviceName, Deadline deadline) {
        ServerReflectionGrpc.ServerReflectionStub stub = ServerReflectionGrpc.newStub(channel).withDeadline(deadline);
        List<FileDescriptorProto> files = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        StreamObserver<ServerReflectionResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServerReflectionResponse response) {
                collect(response, files, failure);
            }

            @Override
            public void onError(Throwable throwable) {
                failure.compareAndSet(null, mapReflectionError(throwable));
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };
        StreamObserver<ServerReflectionRequest> requestObserver = stub.serverReflectionInfo(responseObserver);
        requestObserver.onNext(ServerReflectionRequest.newBuilder().setFileContainingSymbol(serviceName).build());
        requestObserver.onCompleted();
        awaitReflection(latch, deadline, serviceName);
        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new StandTestException("gRPC reflection failed for service '" + serviceName + "': " + thrown.getMessage(), thrown);
        }
        if (files.isEmpty()) {
            throw new StandTestException("gRPC reflection returned no descriptors for service '" + serviceName + "'");
        }
        return files;
    }

    private static Throwable mapReflectionError(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException status && status.getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
            return new StandTestException("gRPC Server Reflection (grpc.reflection.v1) is not implemented by the target; the server may expose only the older v1alpha reflection, or reflection may be disabled. Original: " + throwable.getMessage(), throwable);
        }
        return throwable;
    }

    private static void collect(ServerReflectionResponse response, List<FileDescriptorProto> files, AtomicReference<Throwable> failure) {
        switch (response.getMessageResponseCase()) {
            case FILE_DESCRIPTOR_RESPONSE -> {
                for (var bytes : response.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                    try {
                        files.add(FileDescriptorProto.parseFrom(bytes));
                    } catch (InvalidProtocolBufferException invalid) {
                        failure.compareAndSet(null, invalid);
                    }
                }
            }
            case ERROR_RESPONSE -> failure.compareAndSet(null, new StandTestException("reflection error " + response.getErrorResponse().getErrorCode() + ": " + response.getErrorResponse().getErrorMessage()));
            default -> failure.compareAndSet(null, new StandTestException("unexpected reflection response: " + response.getMessageResponseCase()));
        }
    }

    private static void awaitReflection(CountDownLatch latch, Deadline deadline, String serviceName) {
        long remainingMillis = deadline.timeRemaining(TimeUnit.MILLISECONDS);
        if (remainingMillis <= 0) {
            throw new StandTestException("gRPC reflection deadline exceeded before a response for service '" + serviceName + "'");
        }
        try {
            if (!latch.await(remainingMillis, TimeUnit.MILLISECONDS)) {
                throw new StandTestException("gRPC reflection timed out for service '" + serviceName + "' after " + remainingMillis + "ms");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StandTestException("Interrupted while resolving gRPC descriptors for service '" + serviceName + "'", interrupted);
        }
    }

    private static Descriptors.ServiceDescriptor findService(List<FileDescriptorProto> protos, String serviceName) {
        Map<String, FileDescriptorProto> byName = new LinkedHashMap<>();
        for (FileDescriptorProto proto : protos) {
            byName.put(proto.getName(), proto);
        }
        Map<String, FileDescriptor> built = new LinkedHashMap<>();
        Set<String> inProgress = new HashSet<>();
        for (FileDescriptorProto proto : protos) {
            FileDescriptor file = buildFile(proto.getName(), byName, built, inProgress);
            Descriptors.ServiceDescriptor service = file.findServiceByName(localName(serviceName));
            if (service != null && service.getFullName().equals(serviceName)) {
                return service;
            }
        }
        throw new StandTestException("gRPC service '" + serviceName + "' not found in the reflected descriptors");
    }

    private static FileDescriptor buildFile(String name, Map<String, FileDescriptorProto> byName, Map<String, FileDescriptor> built, Set<String> inProgress) {
        FileDescriptor cached = built.get(name);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(name)) {
            throw new StandTestException("Cyclic dependency in the gRPC descriptor graph at '" + name + "'");
        }
        FileDescriptorProto proto = byName.get(name);
        if (proto == null) {
            throw new StandTestException("gRPC reflection did not return dependency descriptor '" + name + "'");
        }
        FileDescriptor[] dependencies = new FileDescriptor[proto.getDependencyCount()];
        for (int index = 0; index < dependencies.length; index++) {
            dependencies[index] = buildFile(proto.getDependency(index), byName, built, inProgress);
        }
        try {
            FileDescriptor file = FileDescriptor.buildFrom(proto, dependencies);
            built.put(name, file);
            inProgress.remove(name);
            return file;
        } catch (DescriptorValidationException invalid) {
            throw new StandTestException("Failed to build gRPC descriptor '" + name + "': " + invalid.getMessage(), invalid);
        }
    }

    private static String localName(String fullyQualified) {
        int dot = fullyQualified.lastIndexOf('.');
        return (dot < 0) ? fullyQualified : fullyQualified.substring(dot + 1);
    }
}
