package ru.alfa.stand.test.eq.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EqIdGeneratorTest {

    @Test
    void identifiersAreStableAndRunScoped() {
        EqIdGenerator first = new EqIdGenerator("run-1", 0);
        EqIdGenerator same = new EqIdGenerator("run-1", 0);
        EqIdGenerator nextStep = new EqIdGenerator("run-1", 1);
        EqIdGenerator nextRun = new EqIdGenerator("run-2", 0);

        assertThat(first.pin()).matches("T[A-Z0-9]{5}").isEqualTo(same.pin());
        assertThat(first.pin()).isNotEqualTo(nextStep.pin()).isNotEqualTo(nextRun.pin());
        assertThat(first.account(0, "RUR")).matches("40702810[0-9]{12}");
        assertThat(first.account(1, "RUR")).isNotEqualTo(first.account(0, "RUR"));
        assertThat(first.account(0, "USD")).matches("40702840[0-9]{12}");
        assertThat(first.registrationNumber()).matches("[0-9]{12}");
        assertThat(first.deal("PU_NWA")).isEqualTo(first.pin() + "_PU_NWA");
    }

    @Test
    void invalidInputsFailBeforeProducingIdentifiers() {
        assertThatThrownBy(() -> new EqIdGenerator(" ", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EqIdGenerator("run", -1)).isInstanceOf(IllegalArgumentException.class);
        EqIdGenerator generator = new EqIdGenerator("run", 0);
        assertThatThrownBy(() -> generator.account(-1, "RUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.account(0, "GBP")).isInstanceOf(IllegalArgumentException.class);
    }
}
