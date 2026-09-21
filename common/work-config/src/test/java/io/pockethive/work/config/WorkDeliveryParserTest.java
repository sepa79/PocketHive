package io.pockethive.work.config;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkDeliveryParserTest {
    private final WorkDeliveryParser parser = new WorkDeliveryParser();

    @Test void absentAndExplicitImmediateHaveOneMeaning() {
        assertThat(parser.parseOutput(Map.of())).isEqualTo(WorkDelivery.IMMEDIATE);
        assertThat(parser.parse(Map.of("mode", "IMMEDIATE"))).isEqualTo(WorkDelivery.IMMEDIATE);
    }

    @Test void authoredNumbersAndEnvironmentTextResolveToTheSameIntent() {
        var expected = new WorkDelivery(WorkDeliveryMode.DELAYED, 180000);
        assertThat(parser.parse(Map.of("mode", "DELAYED", "delayMs", 180000))).isEqualTo(expected);
        assertThat(parser.parse(Map.of("mode", "DELAYED", "delayMs", "180000"))).isEqualTo(expected);
        assertThat(parser.parse(parser.configuration(expected))).isEqualTo(expected);
    }

    @Test void invalidDeclarationsFailInsteadOfPublishingImmediately() {
        for (Object value : List.of("DELAYED", Map.of(), Map.of("mode", "later"),
            Map.of("mode", "DELAYED"), Map.of("mode", "IMMEDIATE", "delayMs", 0),
            Map.of("mode", "DELAYED", "delayMs", -1), Map.of("mode", "DELAYED", "delayMs", 0),
            Map.of("mode", "DELAYED", "delayMs", 2.9), Map.of("mode", "DELAYED", "delayMs", true),
            Map.of("mode", "DELAYED", "delayMs", "9223372036854775808"),
            Map.of("mode", "DELAYED", "delayMs", 1, "typo", true))) {
            assertThatThrownBy(() -> parser.parse(value)).as("%s", value).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> parser.parseOutput(java.util.Collections.singletonMap("delivery", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unsupportedOutputCannotAcceptDelayedDelivery() {
        var parser = new WorkConfigurationParser(List.of(), List.of());
        // Output validation must still report delivery failure independently of the invalid input.
        for (String type : List.of("RABBITMQ", "REDIS", "NONE")) {
            var result = parser.validate(Map.of("inputs", Map.of("type", "SCHEDULER"), "outputs",
                Map.of("type", type, "delivery", Map.of("mode", "DELAYED", "delayMs", 1))),
                WorkConfigurationMode.AUTHORING);
            assertThat(result.problems()).anySatisfy(problem -> {
                assertThat(problem.path()).isEqualTo("outputs.delivery");
                assertThat(problem.message()).contains("does not support DELAYED");
            });
        }
    }
}
