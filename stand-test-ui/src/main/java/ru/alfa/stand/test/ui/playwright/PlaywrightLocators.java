package ru.alfa.stand.test.ui.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.Locale;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.ui.UiLocator;

/**
 * Translates the SDK's {@link UiLocator} into a Playwright {@link Locator}.
 *
 * <p>One switch, one direction, no cleverness: this is the whole of the mapping, and keeping it in its
 * own class is what lets the rest of the adapter stay unaware of Playwright.
 */
final class PlaywrightLocators {

    private PlaywrightLocators() {
    }

    static Locator locator(Page page, UiLocator locator) {
        return switch (locator.strategy()) {
            case TEST_ID -> page.getByTestId(locator.value());
            case ROLE -> page.getByRole(role(locator.value()), new Page.GetByRoleOptions().setName(locator.accessibleName()));
            case LABEL -> page.getByLabel(locator.value());
            case TEXT -> page.getByText(locator.value());
            case CSS -> page.locator(locator.value());
        };
    }

    private static AriaRole role(String role) {
        try {
            return AriaRole.valueOf(role.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new StandTestException("Unknown ARIA role '" + role + "' in a ROLE locator", unknown);
        }
    }
}
