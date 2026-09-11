package io.pockethive.work.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkConfigurationContractTest {
    private static final InputSettings INPUT_SETTINGS = new InputSettings("scheduler");
    private static final OutputSettings OUTPUT_SETTINGS = new OutputSettings("redis");

    private final WorkInputSettingsParser inputParser = inputParser(WorkerInputType.SCHEDULER);
    private final WorkOutputSettingsParser outputParser = outputParser(WorkerOutputType.REDIS);
    private final WorkConfigurationParser parser = parser(List.of(inputParser), List.of(outputParser));

    @Test
    void returnsCompleteImmutableResolvedConfiguration() {
        WorkConfigurationValidation result = validate(validNoneCandidate(), WorkConfigurationMode.RESOLVED);

        assertThat(result.problems()).isEmpty();
        assertThat(result.deferredPaths()).isEmpty();
        assertThat(result.configuration()).isEqualTo(new CompleteWorkConfiguration(
            WorkerInputType.SCHEDULER,
            INPUT_SETTINGS,
            WorkerOutputType.NONE,
            NoOutputWorkSettings.INSTANCE));
        assertThatThrownBy(() -> result.problems().add(problem("x")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.deferredPaths().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesBothSelectedDirectionsAndUsesCanonicalSettingsKeys() {
        var inputCalled = new AtomicBoolean();
        var outputCalled = new AtomicBoolean();
        WorkInputSettingsParser observingInput = inputParser(
            WorkerInputType.CSV_DATASET, inputCalled, "inputs.csv");
        WorkOutputSettingsParser observingOutput = outputParser(
            WorkerOutputType.REDIS, outputCalled, "outputs.redis");
        Map<String, Object> candidate = Map.of(
            "inputs", Map.of("type", "CSV_DATASET", "csv", Map.of()),
            "outputs", Map.of("type", "REDIS", "redis", Map.of()));

        WorkConfigurationValidation result = parser(List.of(observingInput), List.of(observingOutput))
            .validate(candidate, WorkConfigurationMode.RESOLVED);

        assertThat(result.problems()).isEmpty();
        assertThat(result.configuration().inputType()).isEqualTo(WorkerInputType.CSV_DATASET);
        assertThat(result.configuration().outputType()).isEqualTo(WorkerOutputType.REDIS);
        assertThat(result.configuration().outputSettings()).isEqualTo(OUTPUT_SETTINGS);
        assertThat(inputCalled).isTrue();
        assertThat(outputCalled).isTrue();
    }

    @Test
    void rejectsMissingOrNonObjectRoots() {
        assertProblem(
            parser.validate(Map.of("outputs", Map.of("type", "NONE")), WorkConfigurationMode.RESOLVED),
            "inputs");
        assertProblem(
            parser.validate(Map.of("inputs", "", "outputs", Map.of("type", "NONE")), WorkConfigurationMode.RESOLVED),
            "inputs");
        assertProblem(
            parser.validate(Map.of("inputs", schedulerInput(), "outputs", List.of()), WorkConfigurationMode.RESOLVED),
            "outputs");
    }

    @Test
    void rejectsMissingBlankAndUnsupportedTypes() {
        assertProblem(validate(candidate(Map.of("scheduler", Map.of()), noneOutput()),
            WorkConfigurationMode.RESOLVED), "inputs.type");
        assertProblem(validate(candidate(Map.of("type", " ", "scheduler", Map.of()), noneOutput()),
            WorkConfigurationMode.RESOLVED), "inputs.type");
        assertProblem(validate(candidate(Map.of("type", "UNKNOWN", "scheduler", Map.of()), noneOutput()),
            WorkConfigurationMode.RESOLVED), "inputs.type");
    }

    @Test
    void rejectsMissingNonObjectAndUnselectedSettingsBlocks() {
        assertProblem(validate(candidate(Map.of("type", "SCHEDULER"), noneOutput()),
            WorkConfigurationMode.RESOLVED), "inputs.scheduler");
        assertProblem(validate(candidate(Map.of("type", "SCHEDULER", "scheduler", ""), noneOutput()),
            WorkConfigurationMode.RESOLVED), "inputs.scheduler");
        WorkConfigurationValidation unselected = validate(candidate(
            Map.of("type", "SCHEDULER", "scheduler", Map.of(), "redis", Map.of()), noneOutput()),
            WorkConfigurationMode.RESOLVED);
        assertProblem(unselected, "inputs.redis");
        assertThat(unselected.configuration()).isNull();
    }

    @Test
    void noneRejectsEverySettingsBlockWithoutInvokingOutputParser() {
        var outputCalled = new AtomicBoolean();
        WorkConfigurationValidation result = parser(
            List.of(inputParser),
            List.of(outputParser(WorkerOutputType.REDIS, outputCalled, "outputs.redis"))
        ).validate(
            candidate(schedulerInput(), Map.of("type", "NONE", "none", Map.of())),
            WorkConfigurationMode.RESOLVED
        );

        assertProblem(result, "outputs.none");
        assertThat(result.configuration()).isNull();
        assertThat(outputCalled).isFalse();
    }

    @Test
    void requiresExactlyOneMatchingParserPerSelectedDirection() {
        WorkConfigurationValidation missingInput = parser(List.of(), List.of(outputParser))
            .validate(validNoneCandidate(), WorkConfigurationMode.RESOLVED);
        assertProblem(missingInput, "inputs.scheduler");

        WorkConfigurationValidation duplicateInput = parser(
            List.of(inputParser, inputParser(WorkerInputType.SCHEDULER)), List.of(outputParser)
        ).validate(validNoneCandidate(), WorkConfigurationMode.RESOLVED);
        assertProblem(duplicateInput, "inputs.scheduler");

        Map<String, Object> redisOutputCandidate = candidate(
            schedulerInput(), Map.of("type", "REDIS", "redis", Map.of()));
        WorkConfigurationValidation missingOutput = parser(List.of(inputParser), List.of())
            .validate(redisOutputCandidate, WorkConfigurationMode.RESOLVED);
        assertProblem(missingOutput, "outputs.redis");

        WorkConfigurationValidation duplicateOutput = parser(
            List.of(inputParser), List.of(outputParser, outputParser(WorkerOutputType.REDIS))
        ).validate(redisOutputCandidate, WorkConfigurationMode.RESOLVED);
        assertProblem(duplicateOutput, "outputs.redis");
    }

    @Test
    void authoringDefersSymbolicSelectorsAndFieldsWithoutExposingConfiguration() {
        Map<String, Object> symbolicSelector = candidate(
            Map.of("type", "{{ inputType }}", "scheduler", Map.of()), noneOutput());
        WorkConfigurationValidation selectorResult = validate(symbolicSelector, WorkConfigurationMode.AUTHORING);
        assertThat(selectorResult.deferredPaths()).containsExactly("inputs.type");
        assertThat(selectorResult.configuration()).isNull();

    }

    @Test
    void adapterDeferredResultStillValidatesTheOtherDirectionAndExposesNoConfiguration() {
        var outputCalled = new AtomicBoolean();
        WorkInputSettingsParser deferredInput = new WorkInputSettingsParser() {
            @Override
            public WorkerInputType type() {
                return WorkerInputType.SCHEDULER;
            }

            @Override
            public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path,
                                                         WorkConfigurationMode mode) {
                return new WorkInputSettingsParseResult(null, List.of(), List.of(path + ".ratePerSec"));
            }
        };
        WorkOutputSettingsParser observingOutput = outputParser(
            WorkerOutputType.REDIS, outputCalled, "outputs.redis");
        Map<String, Object> candidate = candidate(
            schedulerInput(), Map.of("type", "REDIS", "redis", Map.of()));

        WorkConfigurationValidation result = parser(List.of(deferredInput), List.of(observingOutput))
            .validate(candidate, WorkConfigurationMode.AUTHORING);

        assertThat(result.problems()).isEmpty();
        assertThat(result.deferredPaths()).containsExactly("inputs.scheduler.ratePerSec");
        assertThat(result.configuration()).isNull();
        assertThat(outputCalled).isTrue();
    }

    @Test
    void resolvedRejectsSymbolicSelector() {
        Map<String, Object> candidate = candidate(
            Map.of("type", "{{ inputType }}", "scheduler", Map.of()), noneOutput());

        WorkConfigurationValidation result = validate(candidate, WorkConfigurationMode.RESOLVED);

        assertProblem(result, "inputs.type");
        assertThat(result.deferredPaths()).isEmpty();
        assertThat(result.configuration()).isNull();
    }

    @Test
    void rejectsInvalidRegistriesAndNullParserResultsExplicitly() {
        assertThatThrownBy(() -> parser(
            new ArrayList<>(java.util.Arrays.asList(inputParser, null)), List.of(outputParser)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("inputParsers entry");

        WorkInputSettingsParser nullResultParser = new WorkInputSettingsParser() {
            @Override
            public WorkerInputType type() {
                return WorkerInputType.SCHEDULER;
            }

            @Override
            public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path,
                                                         WorkConfigurationMode mode) {
                return null;
            }
        };
        assertThatThrownBy(() -> parser(List.of(nullResultParser), List.of(outputParser))
            .validate(validNoneCandidate(), WorkConfigurationMode.RESOLVED))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("input parser result");
    }

    @Test
    void copiesParserRegistriesAtConstruction() {
        List<WorkInputSettingsParser> inputs = new ArrayList<>(List.of(inputParser));
        List<WorkOutputSettingsParser> outputs = new ArrayList<>(List.of(outputParser));
        WorkConfigurationParser immutableRegistryParser = parser(inputs, outputs);
        inputs.clear();
        outputs.clear();

        WorkConfigurationValidation result = immutableRegistryParser.validate(
            validNoneCandidate(), WorkConfigurationMode.RESOLVED);

        assertThat(result.problems()).isEmpty();
        assertThat(result.configuration()).isNotNull();
    }

    @Test
    void parseResultsAndAggregateRejectPartialValues() {
        assertThatThrownBy(() -> new WorkInputSettingsParseResult(
            INPUT_SETTINGS, List.of(problem("inputs.scheduler")), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkOutputSettingsParseResult(
            OUTPUT_SETTINGS, List.of(), List.of("outputs.redis.host")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkConfigurationValidation(
            complete(), List.of(problem("inputs.scheduler")), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkConfigurationValidation(null, List.of(), List.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ignoresNonWorkRoots() {
        Map<String, Object> candidate = Map.of(
            "inputs", schedulerInput(),
            "outputs", noneOutput(),
            "privateConfig", Map.of("secret", "value"),
            "execution", Map.of("maxInFlight", -1),
            "business", Map.of("unknown", true));

        assertThat(validate(candidate, WorkConfigurationMode.RESOLVED).configuration()).isNotNull();
    }

    private WorkConfigurationValidation validate(Map<String, Object> candidate, WorkConfigurationMode mode) {
        return parser.validate(candidate, mode);
    }

    private static WorkConfigurationParser parser(List<WorkInputSettingsParser> inputParsers,
                                                   List<WorkOutputSettingsParser> outputParsers) {
        return new WorkConfigurationParser(inputParsers, outputParsers);
    }

    private static WorkInputSettingsParser inputParser(WorkerInputType type) {
        return inputParser(type, new AtomicBoolean(), "inputs." + type.settingsKey());
    }

    private static WorkInputSettingsParser inputParser(WorkerInputType type, AtomicBoolean called,
                                                       String expectedPath) {
        return new WorkInputSettingsParser() {
            @Override
            public WorkerInputType type() {
                return type;
            }

            @Override
            public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path,
                                                         WorkConfigurationMode mode) {
                assertThat(path).isEqualTo(expectedPath);
                called.set(true);
                return new WorkInputSettingsParseResult(INPUT_SETTINGS, List.of(), List.of());
            }
        };
    }

    private static WorkOutputSettingsParser outputParser(WorkerOutputType type) {
        return outputParser(type, new AtomicBoolean(), "outputs." + type.settingsKey());
    }

    private static WorkOutputSettingsParser outputParser(WorkerOutputType type, AtomicBoolean called,
                                                         String expectedPath) {
        return new WorkOutputSettingsParser() {
            @Override
            public WorkerOutputType type() {
                return type;
            }

            @Override
            public WorkOutputSettingsParseResult validate(Map<?, ?> settings, String path,
                                                          WorkConfigurationMode mode) {
                assertThat(path).isEqualTo(expectedPath);
                called.set(true);
                return new WorkOutputSettingsParseResult(OUTPUT_SETTINGS, List.of(), List.of());
            }
        };
    }

    private static Map<String, Object> validNoneCandidate() {
        return candidate(schedulerInput(), noneOutput());
    }

    private static Map<String, Object> candidate(Map<String, Object> input, Map<String, Object> output) {
        return Map.of("inputs", input, "outputs", output);
    }

    private static Map<String, Object> schedulerInput() {
        return Map.of("type", "SCHEDULER", "scheduler", Map.of());
    }

    private static Map<String, Object> noneOutput() {
        return Map.of("type", "NONE");
    }

    private static CompleteWorkConfiguration complete() {
        return new CompleteWorkConfiguration(
            WorkerInputType.SCHEDULER, INPUT_SETTINGS, WorkerOutputType.NONE, NoOutputWorkSettings.INSTANCE);
    }

    private static void assertProblem(WorkConfigurationValidation result, String path) {
        assertThat(result.problems())
            .extracting(WorkConfigurationProblem::path)
            .contains(path);
        assertThat(result.configuration()).isNull();
    }

    private static WorkConfigurationProblem problem(String path) {
        return new WorkConfigurationProblem(path, "problem");
    }

    private record InputSettings(String name) implements WorkInputSettings {
    }

    private record OutputSettings(String name) implements WorkOutputSettings {
    }
}
