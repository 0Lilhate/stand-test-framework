package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultGrpcChannelFactoryTest {

    @Test
    @DisplayName("builds a channel for the resolved target without connecting")
    void buildsChannel() {
        DefaultGrpcChannelFactory factory = new DefaultGrpcChannelFactory();
        ManagedChannel channel = factory.create(new ResolvedGrpcTarget("localhost:50051"));
        try {
            assertThat(channel).isNotNull();
            assertThat(channel.authority()).contains("localhost:50051");
        } finally {
            channel.shutdownNow();
        }
    }
}
