package ru.alfa.stand.test.eq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.scenario.GenericStep;

class EqSeedTest {

    @Test
    @DisplayName("BR-10 BR-13: organisation builder creates a lazy eq.seed model with backend alias")
    void organisationProducesModel() {
        EqAccount account = EqAccount.type("CA")
                .currency("RUR")
                .topUp(new BigDecimal("100000"))
                .servicePackage("PU_NWA");

        GenericStep step = EqSeed.organisation("client")
                .name("Test organisation")
                .account(account)
                .build();

        assertThat(step.type()).isEqualTo("eq.seed");
        assertThat(step.parameters()).containsEntry(EqStepParameters.BACKEND, "eq")
                .containsEntry(EqStepParameters.ALIAS, "client")
                .containsEntry(EqStepParameters.CLIENT_KIND, "organisation");
        List<?> accounts = (List<?>) step.parameters().get(EqStepParameters.ACCOUNTS);
        assertThat(accounts).hasSize(1);
        assertThat(accountParameters(accounts.get(0))).containsEntry(EqStepParameters.TYPE, "CA")
                .containsEntry(EqStepParameters.CURRENCY, "RUR")
                .containsEntry(EqStepParameters.TOP_UP, "100000")
                .containsEntry(EqStepParameters.SERVICE_PACKAGE, "PU_NWA");
    }

    @Test
    @DisplayName("BR-11 BR-31: optional attributes remain absent and approximation is explicit")
    void optionalAttributesAndApproximation() {
        GenericStep step = EqSeed.organisation("client")
                .backend("alternate")
                .account(EqAccount.create().openedAt(LocalDate.of(2024, 1, 2)))
                .allowApproximation(EqAttribute.OPENED_AT)
                .build();

        assertThat(step.parameters()).containsEntry(EqStepParameters.BACKEND, "alternate")
                .containsEntry(EqStepParameters.ALLOW_APPROXIMATION, List.of("OPENED_AT"));
        List<?> accounts = (List<?>) step.parameters().get(EqStepParameters.ACCOUNTS);
        assertThat(accountParameters(accounts.get(0))).containsOnlyKeys(EqStepParameters.OPENED_AT);
    }

    @Test
    @DisplayName("BR-10: individual service package belongs to the client")
    void individualPackagePlacement() {
        GenericStep step = EqSeed.individual("person")
                .servicePackage("T04")
                .account(EqAccount.create())
                .build();

        assertThat(step.parameters()).containsEntry(EqStepParameters.SERVICE_PACKAGE, "T04");
        assertThatThrownBy(() -> EqSeed.individual("person")
                .account(EqAccount.create().servicePackage("T04"))
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service package");
    }

    @Test
    @DisplayName("BR-10: a seed requires an account and keeps its built model immutable")
    void requiresAccountAndBuildsImmutableStep() {
        assertThatThrownBy(() -> EqSeed.organisation("client").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account");

        EqSeed builder = EqSeed.organisation("client").account(EqAccount.create());
        GenericStep step = builder.build();
        builder.account(EqAccount.create().currency("USD"));

        assertThat((List<?>) step.parameters().get(EqStepParameters.ACCOUNTS)).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> accountParameters(Object value) {
        return (Map<String, Object>) value;
    }
}
