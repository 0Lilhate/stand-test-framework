package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DynamicMessage;
import io.grpc.health.v1.HealthCheckRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Server-free coverage of the JSON&lt;-&gt;DynamicMessage conversion, using the Health protobuf types that
 * ship on the classpath (no generated stubs of our own).
 */
class DynamicMessagesTest {

    @Test
    @DisplayName("JSON round-trips through a DynamicMessage")
    void roundTrip() {
        DynamicMessage message = DynamicMessages.fromJson(HealthCheckRequest.getDescriptor(), "{\"service\":\"db\"}");
        assertThat(DynamicMessages.toJson(message)).contains("\"service\":\"db\"");
    }

    @Test
    @DisplayName("a blank request yields the default (empty) message")
    void blankRequestIsEmpty() {
        DynamicMessage message = DynamicMessages.fromJson(HealthCheckRequest.getDescriptor(), " ");
        assertThat(DynamicMessages.toJson(message)).isEqualTo("{}");
    }

    @Test
    @DisplayName("invalid JSON is a configuration error")
    void invalidJsonFails() {
        assertThatThrownBy(() -> DynamicMessages.fromJson(HealthCheckRequest.getDescriptor(), "not json"))
                .isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("an unknown field is rejected (fail-closed)")
    void unknownFieldFails() {
        assertThatThrownBy(() -> DynamicMessages.fromJson(HealthCheckRequest.getDescriptor(), "{\"nope\":1}"))
                .isInstanceOf(StandTestException.class);
    }
}
