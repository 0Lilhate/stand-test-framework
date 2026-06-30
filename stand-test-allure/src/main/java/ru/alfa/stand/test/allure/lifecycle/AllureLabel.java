package ru.alfa.stand.test.allure.lifecycle;

import java.util.Objects;

/**
 * Transport-neutral name/value label applied to the Allure test case (for example a {@code tag} label).
 *
 * <p>Kept separate from a plain map because Allure labels are not unique by name — a scenario may carry
 * several {@code tag} labels. {@link DefaultAllureLifecycleFacade} translates this to
 * {@code io.qameta.allure.model.Label}.
 *
 * @param name the non-blank label name
 * @param value the label value (never null; may be empty)
 */
public record AllureLabel(String name, String value) {

    public AllureLabel {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("label name must not be blank");
        }
        Objects.requireNonNull(value, "label value must not be null");
    }
}
