package ru.alfa.stand.test.eq.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.SectionEntry;

class EqBackendConfigParserTest {

    @Test
    void parsesSelectedShowcasesBackend() {
        SectionEntry entry = new SectionEntry("eq", Map.of(
                "kind", "showcases", "service", "showcases", "path", "/showcases/load/list"));

        assertThat(EqBackendConfigParser.parseShowcases("ift", entry))
                .isEqualTo(new ShowcasesBackendConfig("eq", "showcases", "/showcases/load/list"));
    }

    @Test
    void rejectsUnknownAndMalformedFieldsWithLocation() {
        SectionEntry unknown = new SectionEntry("eq", Map.of(
                "kind", "showcases", "service", "showcases", "path", "/load", "servcie", "typo"));
        assertThatThrownBy(() -> EqBackendConfigParser.parseShowcases("ift", unknown))
                .hasMessageContaining("ift").hasMessageContaining("eq").hasMessageContaining("servcie");

        SectionEntry wrongType = new SectionEntry("eq", Map.of(
                "kind", "showcases", "service", 42, "path", "/load"));
        assertThatThrownBy(() -> EqBackendConfigParser.parseShowcases("ift", wrongType))
                .hasMessageContaining("service");

        SectionEntry unsafePath = new SectionEntry("eq", Map.of(
                "kind", "showcases", "service", "showcases", "path", "https://stand/load"));
        assertThatThrownBy(() -> EqBackendConfigParser.parseShowcases("ift", unsafePath))
                .hasMessageContaining("path");

        SectionEntry marker = new SectionEntry("eq", Map.of(
                "kind", "showcases", "service", "literal://showcases", "path", "/load"));
        assertThatThrownBy(() -> EqBackendConfigParser.parseShowcases("ift", marker))
                .hasMessageContaining("literal marker");
    }
}
