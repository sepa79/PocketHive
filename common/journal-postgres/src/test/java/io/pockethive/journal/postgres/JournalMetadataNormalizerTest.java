package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import io.pockethive.journal.api.SwarmRunMetadataUpdate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalMetadataNormalizerTest {
    @Test
    void trimsDropsEmptyDeduplicatesAfterTruncationAndLimitsTags() {
        var tags = new ArrayList<>(Arrays.asList(null, " ", " tag ", "tag", "x".repeat(70), "x".repeat(65)));
        for (int i = 0; i < 40; i++) tags.add("t" + i);
        var result = JournalMetadataNormalizer.clean(new SwarmRunMetadataUpdate(" p ", " ", tags));
        assertThat(result.testPlan()).isEqualTo("p");
        assertThat(result.description()).isNull();
        assertThat(result.tags()).hasSize(32).startsWith("tag", "x".repeat(64)).endsWith("t29");
    }

    @Test
    void nullAndEmptyRequestsClearMetadata() {
        assertThat(JournalMetadataNormalizer.clean(null)).isEqualTo(new SwarmRunMetadataUpdate(null, null, null));
        assertThat(JournalMetadataNormalizer.clean(new SwarmRunMetadataUpdate(" ", " ", List.of(" "))))
            .isEqualTo(new SwarmRunMetadataUpdate(null, null, null));
    }
}
