package io.pockethive.work.local.csv;

import io.pockethive.work.config.WorkMutationRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvWorkInputMutationPolicyTest {
    private final CsvWorkInputMutationPolicy policy = new CsvWorkInputMutationPolicy(new CsvDatasetParser());

    @Test
    void validatesRateAgainstStartupThenPreviousThenPatchCandidate() {
        var startup = settings("/startup.csv", 1.0);
        var previous = new LinkedHashMap<String, Object>();
        previous.put("filePath", "/previous.csv");
        var patch = new LinkedHashMap<String, Object>();
        patch.put("ratePerSec", 2.5);

        assertThatCode(() -> policy.validate(request(startup, previous, patch, 1.0, 2.5)))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidCompleteCandidate() {
        var patch = new LinkedHashMap<String, Object>();
        patch.put("ratePerSec", -0.1);

        assertThatThrownBy(() -> policy.validate(request(settings("/data.csv", 1.0), Map.of(), patch, 1.0, -0.1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputs.csv.ratePerSec")
            .hasMessageContaining("finite number >= 0.0");
    }

    @Test
    void rejectsStartupCandidateInvalidatedBeforeLiveRateChange() {
        var startup = settings("/data.csv", 1.0);
        var previous = new LinkedHashMap<String, Object>();
        previous.put("skipHeader", "yes");
        var patch = new LinkedHashMap<String, Object>();
        patch.put("ratePerSec", 2.0);

        assertThatThrownBy(() -> policy.validate(request(startup, previous, patch, 1.0, 2.0)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputs.csv.skipHeader");
    }

    private static WorkMutationRequest request(Map<String, Object> startup, Map<String, Object> previous,
                                               Map<String, Object> patch, Object oldRate, Object newRate) {
        return new WorkMutationRequest("testWorker", startup, previous, patch, "inputs.csv.ratePerSec", oldRate,
            newRate, false);
    }

    private static Map<String, Object> settings(String path, double rate) {
        return Map.of("filePath", path, "ratePerSec", rate, "rotate", false, "skipHeader", true,
            "delimiter", ",", "charset", "UTF-8", "startupDelaySeconds", 0, "tickIntervalMs", 1000);
    }
}
