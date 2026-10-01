package ru.alfa.stand.test.eq.backend.showcases;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ShowcasesRecordsTest {

    @Test
    void producesTheFourPositionalRecordsOfTheExistingOrganisationFixture() {
        LocalDate today = LocalDate.of(2026, 9, 28);
        List<ShowcasesRecords.Record> records = new ArrayList<>(ShowcasesRecords.organisation(
                "TABCDE", "123456789012", "ООО TEST"));
        records.add(ShowcasesRecords.account("40702810123456789012", "TABCDE", "CA", "RUR", today));
        records.add(ShowcasesRecords.deal("TABCDE_PU_NWA", "TABCDE", "40702810123456789012", "PU_NWA", today));

        List<?> payload = new ObjectMapper().readValue(ShowcasesRecords.toJson(records), List.class);
        assertThat(payload).hasSize(4);
        Map<?, ?> aclm = (Map<?, ?>) payload.get(0);
        Map<?, ?> acln = (Map<?, ?>) payload.get(1);
        Map<?, ?> uacm = (Map<?, ?>) payload.get(2);
        Map<?, ?> udlm = (Map<?, ?>) payload.get(3);
        assertThat(aclm.get("businessCode")).isEqualTo("ACLM");
        assertThat(aclm.containsKey("version")).isFalse();
        assertThat(value(aclm, 4)).isEqualTo("123456789012");
        assertThat(acln.get("businessCode")).isEqualTo("ACLN");
        assertThat(acln.get("version")).isEqualTo(2);
        assertThat(value(acln, 2)).isEqualTo("ООО TEST");
        assertThat(uacm.get("businessCode")).isEqualTo("UACM");
        assertThat(value(uacm, 2)).isEqualTo("4101");
        assertThat(value(uacm, 7)).isEqualTo("2026-09-28T00:00");
        assertThat(value(uacm, 13)).isEqualTo("40702");
        assertThat(udlm.get("businessCode")).isEqualTo("UDLM");
        assertThat(value(udlm, 0)).isEqualTo("TABCDE_PU_NWA");
        assertThat(value(udlm, 4)).isEqualTo("2026-09-28T00:00");
    }

    private static Object value(Map<?, ?> record, int index) {
        List<?> fields = (List<?>) record.get("fields");
        return ((Map<?, ?>) fields.get(index)).get("value");
    }
}
