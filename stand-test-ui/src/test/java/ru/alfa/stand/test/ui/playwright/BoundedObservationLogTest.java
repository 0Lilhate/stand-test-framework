package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bounds on what a page can put into a failure report, and the promise that neither bound is silent.
 *
 * <p>The console and network logs are written by the page and read once, on the failure path. Unbounded,
 * the page decides how much memory the run holds and how large an attachment a reviewer is handed. The
 * point of these tests is less that the bound holds than that a reader can always tell a complete log from
 * a tail — a truncation nobody is told about is worse than no log, because it reads as the whole story.
 */
class BoundedObservationLogTest {

    @Test
    @DisplayName("a log below the bound is returned whole, with no marker invented")
    void shortLogIsUntouched() {
        BoundedObservationLog log = new BoundedObservationLog("console line");
        log.record("error: first");
        log.record("warning: second");

        assertThat(log.snapshot()).containsExactly("error: first", "warning: second");
    }

    @Test
    @DisplayName("nothing observed is an empty log, so a failing step attaches no empty block")
    void emptyLogStaysEmpty() {
        assertThat(new BoundedObservationLog("console line").snapshot()).isEmpty();
    }

    @Test
    @DisplayName("past the bound the NEWEST entries are kept — a failure is explained by what came just before it")
    void theTailIsWhatSurvives() {
        BoundedObservationLog log = new BoundedObservationLog("console line");
        for (int i = 0; i < BoundedObservationLog.MAX_ENTRIES + 50; i++) {
            log.record("line-" + i);
        }
        List<String> snapshot = log.snapshot();

        assertThat(snapshot).hasSize(BoundedObservationLog.MAX_ENTRIES + 1);
        assertThat(snapshot.get(snapshot.size() - 1))
                .as("the most recent line must survive: it is the one next to the failure")
                .isEqualTo("line-" + (BoundedObservationLog.MAX_ENTRIES + 49));
        assertThat(snapshot).doesNotContain("line-0");
    }

    @Test
    @DisplayName("a truncated log says so in its own first line, naming how many entries went")
    void droppingIsAnnounced() {
        BoundedObservationLog log = new BoundedObservationLog("network exchange");
        for (int i = 0; i < BoundedObservationLog.MAX_ENTRIES + 7; i++) {
            log.record("GET /x " + i);
        }

        assertThat(log.snapshot().get(0))
                .as("a reader must be able to tell a complete log from a tail")
                .contains("7")
                .contains("network exchange")
                .contains(String.valueOf(BoundedObservationLog.MAX_ENTRIES));
    }

    @Test
    @DisplayName("one enormous entry is cut and says so — a page may log an object graph on a single line")
    void oneHugeEntryIsCut() {
        BoundedObservationLog log = new BoundedObservationLog("console line");
        log.record("x".repeat(BoundedObservationLog.MAX_ENTRY_CHARS * 3));

        String only = log.snapshot().get(0);
        assertThat(only.length())
                .as("the bound on one entry must hold, or the bound on their number buys nothing")
                .isLessThan(BoundedObservationLog.MAX_ENTRY_CHARS + 100);
        assertThat(only).contains("cut").contains(String.valueOf(BoundedObservationLog.MAX_ENTRY_CHARS * 3));
    }

    @Test
    @DisplayName("a null observation is ignored rather than recorded as a blank line")
    void nullIsIgnored() {
        BoundedObservationLog log = new BoundedObservationLog("console line");
        log.record(null);

        assertThat(log.snapshot()).isEmpty();
    }
}
