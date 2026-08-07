package ru.alfa.stand.test.ui.playwright;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A bounded record of what the browser reported — the console lines and the network exchanges a failing
 * step attaches to its report.
 *
 * <p>Both logs grow for the whole life of a page and are read once, on the failure path. Unbounded, that
 * is a page's ability to decide how much memory a run holds and how large an attachment a reviewer is
 * handed: a chatty frontend logging in a render loop, or a single {@code console.log} of a megabyte-sized
 * object, and the report becomes unreadable at exactly the moment it matters. So there are two bounds —
 * how many entries are kept, and how long one entry may be.
 *
 * <p><strong>Dropping is never silent.</strong> A truncated log says so in its own first line, and a
 * truncated entry says so at its own end. That is the whole difference between a bound and a defect: a
 * reader who sees five hundred console lines must be able to tell "this is everything" from "this is the
 * tail". The same rule the rest of the failure path follows — a missing artefact is reported, never
 * quietly absent.
 *
 * <p>The <em>newest</em> entries are the ones kept. A failure is explained by what happened just before
 * it, so the oldest go first when the bound is reached.
 *
 * <p>Synchronised rather than copy-on-write: the listeners run on Playwright's event-dispatch path, which
 * is not guaranteed to be the run thread that reads the snapshot. Copy-on-write made every append copy the
 * whole buffer, which is quadratic exactly for the chatty page this bound exists to survive.
 */
final class BoundedObservationLog {

    /**
     * How many entries one log keeps. A thousand lines is far more than a human reads and still a bounded
     * attachment; past that, the tail is what carries the failure.
     */
    static final int MAX_ENTRIES = 1000;

    /** How long one entry may be before it is cut. A page may log an entire object graph on one line. */
    static final int MAX_ENTRY_CHARS = 1000;

    private final String what;

    private final ArrayDeque<String> entries = new ArrayDeque<>();

    private long dropped;

    /**
     * Creates the log.
     *
     * @param what what one entry is, named in the truncation marker (for example {@code "console line"})
     */
    BoundedObservationLog(String what) {
        this.what = Objects.requireNonNull(what, "what must not be null");
    }

    /**
     * Records one observation, cutting it to length and evicting the oldest when the bound is reached.
     *
     * @param entry the observed line; {@code null} is ignored
     */
    synchronized void record(String entry) {
        if (entry == null) {
            return;
        }
        this.entries.addLast(cut(entry));
        while (this.entries.size() > MAX_ENTRIES) {
            this.entries.removeFirst();
            this.dropped++;
        }
    }

    /**
     * The entries as an immutable snapshot, newest-last, preceded by a marker when anything was dropped.
     *
     * @return the log, or an empty list when nothing was ever observed
     */
    synchronized List<String> snapshot() {
        if (this.entries.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(this.entries.size() + 1);
        if (this.dropped > 0) {
            out.add("… " + this.dropped + " earlier " + this.what + "(s) dropped: this log keeps the last " + MAX_ENTRIES);
        }
        out.addAll(this.entries);
        return List.copyOf(out);
    }

    private static String cut(String entry) {
        if (entry.length() <= MAX_ENTRY_CHARS) {
            return entry;
        }
        return entry.substring(0, MAX_ENTRY_CHARS) + " …[cut, " + entry.length() + " chars in full]";
    }
}
