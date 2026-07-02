package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class GrpcMethodNameTest {

    @Test
    @DisplayName("a package.Service/Method name parses into service and method")
    void parsesFullName() {
        GrpcMethodName name = GrpcMethodName.parse("billing.BillingService/Charge");
        assertThat(name.serviceName()).isEqualTo("billing.BillingService");
        assertThat(name.methodName()).isEqualTo("Charge");
        assertThat(name.fullMethodName()).isEqualTo("billing.BillingService/Charge");
    }

    @Test
    @DisplayName("a name without a slash is rejected")
    void rejectsMissingSlash() {
        assertThatThrownBy(() -> GrpcMethodName.parse("billing.BillingService.Charge"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("package.Service/Method");
    }

    @Test
    @DisplayName("a name with more than one slash is rejected")
    void rejectsMultipleSlashes() {
        assertThatThrownBy(() -> GrpcMethodName.parse("a/b/c"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("package.Service/Method");
    }

    @Test
    @DisplayName("a blank name is rejected")
    void rejectsBlank() {
        assertThatThrownBy(() -> GrpcMethodName.parse(" "))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("blank");
    }
}
