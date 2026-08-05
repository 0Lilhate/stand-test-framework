package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.assertion.AssertionMatchers;

class UiAssertionEvaluatorTest {

    private static final UiLocator STATUS = UiLocator.testId("status");

    private static final ElementSnapshot ACCEPTED = new ElementSnapshot(true, true, true, "Accepted", "42", Map.of("data-state", "final"));

    @Test
    @DisplayName("every verdict agrees with the core matcher engine — the adapter owns no comparison of its own")
    void evaluatorDelegatesToCoreMatchers() {
        List<UiAssertion> assertions = List.of(
                new UiAssertion(UiProperty.TEXT, "Accepted", AssertionMatcher.EQUALS),
                new UiAssertion(UiProperty.TEXT, "cept", AssertionMatcher.CONTAINS),
                new UiAssertion(UiProperty.TEXT, "Acc.*", AssertionMatcher.MATCHES),
                new UiAssertion(UiProperty.TEXT, true, AssertionMatcher.EXISTS),
                new UiAssertion(UiProperty.TEXT, true, AssertionMatcher.NOT_NULL),
                new UiAssertion(UiProperty.VALUE, "42", AssertionMatcher.EQUALS),
                new UiAssertion(UiProperty.ATTRIBUTE, "data-state", "final", AssertionMatcher.EQUALS));

        for (UiAssertion assertion : assertions) {
            boolean adapterVerdict = UiAssertionEvaluator.firstMismatch(List.of(assertion), STATUS, ACCEPTED) == null;
            assertThat(adapterVerdict).as("%s", assertion.describe()).isEqualTo(coreVerdict(assertion));
        }
    }

    @Test
    @DisplayName("an absent element answers boolean questions and fails string ones — absence is data")
    void absenceIsData() {
        assertThat(UiAssertionEvaluator.firstMismatch(List.of(new UiAssertion(UiProperty.VISIBLE, false, AssertionMatcher.EQUALS)), STATUS, ElementSnapshot.absent())).isNull();
        assertThat(UiAssertionEvaluator.firstMismatch(List.of(new UiAssertion(UiProperty.ENABLED, false, AssertionMatcher.EQUALS)), STATUS, ElementSnapshot.absent())).isNull();
        assertThat(UiAssertionEvaluator.firstMismatch(List.of(new UiAssertion(UiProperty.TEXT, true, AssertionMatcher.EXISTS)), STATUS, ElementSnapshot.absent()))
                .contains("not found on the page");
    }

    @Test
    @DisplayName("the first mismatch wins, and it names the locator, the expectation and what was seen")
    void firstMismatchIsReported() {
        String mismatch = UiAssertionEvaluator.firstMismatch(
                List.of(new UiAssertion(UiProperty.TEXT, "Accepted", AssertionMatcher.EQUALS), new UiAssertion(UiProperty.VALUE, "1", AssertionMatcher.EQUALS)),
                STATUS,
                new ElementSnapshot(true, true, true, "Rejected", "42", Map.of()));

        assertThat(mismatch).contains("TEST_ID(status)").contains("TEXT EQUALS <Accepted>").contains("<Rejected>").doesNotContain("VALUE");
    }

    @Test
    @DisplayName("a sensitive element never puts its content into the failure message — neither expected nor observed")
    void sensitiveElementValuesAreMasked() {
        UiLocator password = UiLocator.label("Password").asSensitive();

        String mismatch = UiAssertionEvaluator.firstMismatch(
                List.of(new UiAssertion(UiProperty.VALUE, "hunter2", AssertionMatcher.EQUALS)),
                password,
                new ElementSnapshot(true, true, true, null, "s3cret-typed", Map.of()));

        assertThat(mismatch)
                .doesNotContain("hunter2")
                .doesNotContain("s3cret-typed")
                .contains("LABEL(Password)")
                .contains("VALUE EQUALS <masked>")
                .contains("but got <masked>");
    }

    @Test
    @DisplayName("without the mark the value is echoed — masking is opt-in, diagnostics stay the default")
    void ordinaryElementValuesAreEchoed() {
        String mismatch = UiAssertionEvaluator.firstMismatch(
                List.of(new UiAssertion(UiProperty.VALUE, "expected", AssertionMatcher.EQUALS)),
                UiLocator.label("Amount"),
                new ElementSnapshot(true, true, true, null, "actual", Map.of()));

        assertThat(mismatch).contains("expected").contains("actual");
    }

    @Test
    @DisplayName("only the attributes actually asserted on or captured are requested from the driver")
    void attributeNamesAreExactlyWhatIsNeeded() {
        assertThat(UiAssertionEvaluator.attributeNames(
                List.of(new UiAssertion(UiProperty.ATTRIBUTE, "data-state", "final", AssertionMatcher.EQUALS), new UiAssertion(UiProperty.TEXT, "x", AssertionMatcher.EQUALS)),
                List.of(new UiCapture("id", STATUS, UiCaptureSource.ATTRIBUTE, "data-id"), new UiCapture("text", STATUS))))
                .containsExactly("data-state", "data-id");
    }

    private static boolean coreVerdict(UiAssertion assertion) {
        Object actual = switch (assertion.property()) {
            case TEXT -> ACCEPTED.text();
            case VALUE -> ACCEPTED.value();
            case ATTRIBUTE -> ACCEPTED.attribute(assertion.attribute());
            case VISIBLE -> ACCEPTED.visible();
            case ENABLED -> ACCEPTED.enabled();
        };
        return AssertionMatchers.matches(assertion.matcher(), assertion.expectedValue(), true, actual);
    }
}
