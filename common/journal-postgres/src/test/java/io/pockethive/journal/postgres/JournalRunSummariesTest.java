package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.journal.api.JournalRunSummary;
import io.pockethive.journal.api.SwarmRunSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalRunSummariesTest {
    private static final Instant EARLY = Instant.parse("2026-09-23T10:00:00Z");
    private static final Instant LATE = EARLY.plusSeconds(60);

    @Test
    void newerLiveEndExtendsPinnedSummaryWithoutLosingPinnedFirstTimeCountOrMetadata() {
        var pinned = new SwarmRunSummary("alpha", "run", EARLY, EARLY, 20, true, "scenario", null, List.of("pinned"), "note");
        var live = new SwarmRunSummary("alpha", "run", LATE, LATE, 3, false, "other", "plan", List.of("live"), "other");
        var result = JournalRunSummaries.merge(List.of(pinned), List.of(live));
        assertThat(result).containsExactly(new SwarmRunSummary("alpha", "run", EARLY, LATE, 20, true,
            "scenario", "plan", List.of("pinned"), "note"));
        assertThat(JournalRunSummary.from(result.getFirst()))
            .isEqualTo(new JournalRunSummary("run", EARLY, LATE, 20, true));
    }

    @Test
    void equalOrOlderLiveEndDoesNotChangePinnedStatistics() {
        var pinned = row("alpha", "run", LATE, 1, true);
        assertThat(JournalRunSummaries.merge(List.of(pinned), List.of(row("alpha", "run", LATE, 50, false))))
            .containsExactly(pinned);
        assertThat(JournalRunSummaries.merge(List.of(pinned), List.of(row("alpha", "run", EARLY, 50, false))))
            .containsExactly(pinned);
    }

    @Test
    void keepsSwarmIdentityAndExistingNullFirstOrdering() {
        var nullTime = row("alpha", "empty", null, 0, true);
        var alpha = row("alpha", "same-run", EARLY, 1, true);
        var beta = row("beta", "same-run", LATE, 2, false);
        assertThat(JournalRunSummaries.merge(List.of(alpha, nullTime), List.of(beta)))
            .containsExactly(nullTime, beta, alpha);
    }

    @Test
    void fillsMissingPinnedTimesAndSkipsIncompleteIdentities() {
        var live = row("alpha", "run", LATE, 2, false);
        assertThat(JournalRunSummaries.merge(List.of(row("alpha", "run", null, 0, true)), List.of(live)))
            .containsExactly(row("alpha", "run", LATE, 2, true));
        assertThat(JournalRunSummaries.merge(List.of(row(null, "run", EARLY, 1, true)),
            List.of(row("alpha", null, EARLY, 1, false)))).isEmpty();
    }

    private SwarmRunSummary row(String swarm, String run, Instant time, long entries, boolean pinned) {
        return new SwarmRunSummary(swarm, run, time, time, entries, pinned, null, null, null, null);
    }
}
