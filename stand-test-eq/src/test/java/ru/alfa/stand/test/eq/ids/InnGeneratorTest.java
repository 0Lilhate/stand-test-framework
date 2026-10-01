package ru.alfa.stand.test.eq.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InnGeneratorTest {

    @Test
    void organisationInnsHaveValidControlDigits() {
        InnGenerator generator = new InnGenerator("run-1", "77", List.of("01", "02"));
        for (int index = 0; index < 50; index++) {
            String inn = generator.organisation();
            assertThat(inn).hasSize(10).matches("[0-9]{10}");
            assertThat(InnGenerator.isValidOrganisation(inn)).as("control digit of %s", inn).isTrue();
        }
    }

    @Test
    void individualInnsHaveValidControlDigits() {
        InnGenerator generator = new InnGenerator("run-1", "77", List.of("01"));
        for (int index = 0; index < 50; index++) {
            String inn = generator.individual();
            assertThat(inn).hasSize(12).matches("[0-9]{12}");
            assertThat(InnGenerator.isValidIndividual(inn)).as("control digits of %s", inn).isTrue();
        }
    }

    @Test
    void sequentialCallsInOneRunNeverRepeat() {
        InnGenerator generator = new InnGenerator("run-1", "77", List.of("01"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int index = 0; index < 1000; index++) {
            assertThat(seen.add(generator.organisation())).isTrue();
        }
    }

    @Test
    void twoRunsDoNotCollideAtTheStart() {
        assertThat(new InnGenerator("run-a", "77", List.of("01")).organisation())
                .isNotEqualTo(new InnGenerator("run-b", "77", List.of("01")).organisation());
    }
}