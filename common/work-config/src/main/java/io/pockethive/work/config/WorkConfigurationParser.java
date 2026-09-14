package io.pockethive.work.config;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: validate outer Work IO and aggregate exactly one selected parser per direction.
 * Must not: parse adapter fields, mutate accepted state or inspect non-Work configuration roots.
 * Contract: RESP-WORK-CONFIGURATION-PARSER —
 * docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public final class WorkConfigurationParser {
    private final List<WorkInputSettingsParser> inputParsers;
    private final List<WorkOutputSettingsParser> outputParsers;

    public WorkConfigurationParser(List<WorkInputSettingsParser> inputParsers,
                                   List<WorkOutputSettingsParser> outputParsers) {
        this.inputParsers = copyInputParserRegistry(inputParsers);
        this.outputParsers = copyOutputParserRegistry(outputParsers);
    }

    public WorkConfigurationValidation validate(Map<String, Object> configuration, WorkConfigurationMode mode) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(mode, "mode");

        var problems = new ArrayList<WorkConfigurationProblem>();
        problems.addAll(new io.pockethive.work.config.policy.InputLifecyclePolicy()
            .configurationProblems(configuration.get(WorkConfigurationFields.INPUTS), WorkConfigurationFields.INPUTS));
        if (!problems.isEmpty()) return new WorkConfigurationValidation(null, problems, List.of());
        var deferredPaths = new ArrayList<String>();
        InputResult input = validateInput(configuration.get(WorkConfigurationFields.INPUTS), mode,
            inputParsers, problems, deferredPaths);
        OutputResult output = validateOutput(configuration.get(WorkConfigurationFields.OUTPUTS), mode,
            outputParsers, problems, deferredPaths);

        CompleteWorkConfiguration complete = null;
        if (problems.isEmpty() && deferredPaths.isEmpty()) {
            complete = new CompleteWorkConfiguration(
                Objects.requireNonNull(input, "validated input").type(),
                input.settings(),
                Objects.requireNonNull(output, "validated output").type(),
                output.settings());
        }
        return new WorkConfigurationValidation(complete, problems, deferredPaths);
    }

    private static InputResult validateInput(Object value, WorkConfigurationMode mode,
                                             List<WorkInputSettingsParser> parsers,
                                             List<WorkConfigurationProblem> problems,
                                             List<String> deferredPaths) {
        Map<?, ?> fields = requireObject(value, WorkConfigurationFields.INPUTS, problems);
        if (fields == null) {
            return null;
        }
        WorkIoType type = parseType(fields, WorkConfigurationFields.INPUTS,
            Stream.concat(Arrays.stream(WorkerInputType.values()), parsers.stream().map(WorkInputSettingsParser::type)).toList(),
            mode, problems, deferredPaths);
        if (type == null) {
            return null;
        }
        String settingsKey = type.settingsKey();
        Map<?, ?> settings = requireSelectedBlock(fields, WorkConfigurationFields.INPUTS, settingsKey, mode, problems);
        if (settings == null) {
            return null;
        }
        List<WorkInputSettingsParser> matches = parsers.stream()
            .filter(parser -> parser.type().equals(type))
            .toList();
        if (!requireExactlyOneParser(matches, WorkConfigurationFields.path(
            WorkConfigurationFields.INPUTS, settingsKey), problems)) {
            return null;
        }
        WorkInputSettingsParseResult result = Objects.requireNonNull(
            matches.getFirst().validate(settings, WorkConfigurationFields.path(
                WorkConfigurationFields.INPUTS, settingsKey), mode),
            "input parser result");
        problems.addAll(result.problems());
        deferredPaths.addAll(result.deferredPaths());
        return result.settings() == null ? null : new InputResult(type, result.settings());
    }

    private static OutputResult validateOutput(Object value, WorkConfigurationMode mode,
                                               List<WorkOutputSettingsParser> parsers,
                                               List<WorkConfigurationProblem> problems,
                                               List<String> deferredPaths) {
        Map<?, ?> fields = requireObject(value, WorkConfigurationFields.OUTPUTS, problems);
        if (fields == null) {
            return null;
        }
        WorkIoType type = parseType(fields, WorkConfigurationFields.OUTPUTS,
            Stream.concat(Arrays.stream(WorkerOutputType.values()), parsers.stream().map(WorkOutputSettingsParser::type)).toList(),
            mode, problems, deferredPaths);
        if (type == null) {
            return null;
        }
        if (type == WorkerOutputType.NONE) {
            return validateNoOutput(fields, problems);
        }
        String settingsKey = type.settingsKey();
        Map<?, ?> settings = requireSelectedBlock(fields, WorkConfigurationFields.OUTPUTS, settingsKey, mode, problems);
        if (settings == null) {
            return null;
        }
        List<WorkOutputSettingsParser> matches = parsers.stream()
            .filter(parser -> parser.type().equals(type))
            .toList();
        if (!requireExactlyOneParser(matches, WorkConfigurationFields.path(
            WorkConfigurationFields.OUTPUTS, settingsKey), problems)) {
            return null;
        }
        WorkOutputSettingsParseResult result = Objects.requireNonNull(
            matches.getFirst().validate(settings, WorkConfigurationFields.path(
                WorkConfigurationFields.OUTPUTS, settingsKey), mode),
            "output parser result");
        problems.addAll(result.problems());
        deferredPaths.addAll(result.deferredPaths());
        return result.settings() == null ? null : new OutputResult(type, result.settings());
    }

    private static OutputResult validateNoOutput(Map<?, ?> fields, List<WorkConfigurationProblem> problems) {
        List<?> unsupported = fields.keySet().stream()
            .filter(key -> !WorkConfigurationFields.TYPE.equals(key))
            .toList();
        unsupported.forEach(key -> problems.add(problem(
            WorkConfigurationFields.path(WorkConfigurationFields.OUTPUTS, String.valueOf(key)),
            "NONE output must not declare settings.")));
        return unsupported.isEmpty()
            ? new OutputResult(WorkerOutputType.NONE, NoOutputWorkSettings.INSTANCE)
            : null;
    }

    private static Map<?, ?> requireSelectedBlock(Map<?, ?> fields, String root, String settingsKey, WorkConfigurationMode mode,
                                                  List<WorkConfigurationProblem> problems) {
        Set<String> supportedKeys = Set.of(WorkConfigurationFields.TYPE, settingsKey);
        List<?> unsupported = fields.keySet().stream()
            .filter(key -> !supportedKeys.contains(key))
            .toList();
        unsupported.forEach(key -> problems.add(problem(
            WorkConfigurationFields.path(root, String.valueOf(key)), "Unsupported or unselected settings block.")));
        if (!fields.containsKey(settingsKey) && mode == WorkConfigurationMode.AUTHORING) {
            return unsupported.isEmpty() ? Map.of() : null;
        }
        if (!fields.containsKey(settingsKey)) {
            problems.add(problem(WorkConfigurationFields.path(root, settingsKey),
                "Selected settings block is required."));
        }
        if (!unsupported.isEmpty() || !fields.containsKey(settingsKey)) {
            return null;
        }
        return requireObject(fields.get(settingsKey), WorkConfigurationFields.path(root, settingsKey), problems);
    }

    private static Map<?, ?> requireObject(Object value, String path,
                                           List<WorkConfigurationProblem> problems) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        problems.add(problem(path, "Settings must be an object."));
        return null;
    }

    private static WorkIoType parseType(Map<?, ?> fields, String root, List<? extends WorkIoType> declaredTypes,
                                                   WorkConfigurationMode mode,
                                                   List<WorkConfigurationProblem> problems,
                                                   List<String> deferredPaths) {
        Object declaredType = fields.get(WorkConfigurationFields.TYPE);
        String typePath = WorkConfigurationFields.path(root, WorkConfigurationFields.TYPE);
        if (WorkConfigurationExpressions.symbolic(declaredType, typePath, mode, problems, deferredPaths)) {
            return null;
        }
        if (!(declaredType instanceof String text) || text.isBlank()) {
            problems.add(problem(typePath, "Type must be configured as nonblank text."));
            return null;
        }
        try {
            return WorkIoTypeParser.parse(text, declaredTypes);
        } catch (IllegalArgumentException exception) {
            problems.add(problem(typePath, exception.getMessage()));
            return null;
        }
    }

    private static boolean requireExactlyOneParser(List<?> matches, String path,
                                                   List<WorkConfigurationProblem> problems) {
        if (matches.size() == 1) {
            return true;
        }
        String message = matches.isEmpty()
            ? "No settings parser is registered."
            : "Multiple settings parsers are registered.";
        problems.add(problem(path, message));
        return false;
    }

    private static List<WorkInputSettingsParser> copyInputParserRegistry(List<WorkInputSettingsParser> parsers) {
        Objects.requireNonNull(parsers, "inputParsers");
        for (WorkInputSettingsParser parser : parsers) {
            Objects.requireNonNull(parser, "inputParsers entry");
            Objects.requireNonNull(parser.type(), "input parser type");
        }
        return List.copyOf(parsers);
    }

    private static List<WorkOutputSettingsParser> copyOutputParserRegistry(List<WorkOutputSettingsParser> parsers) {
        Objects.requireNonNull(parsers, "outputParsers");
        for (WorkOutputSettingsParser parser : parsers) {
            Objects.requireNonNull(parser, "outputParsers entry");
            Objects.requireNonNull(parser.type(), "output parser type");
        }
        return List.copyOf(parsers);
    }

    private static WorkConfigurationProblem problem(String path, String message) {
        return new WorkConfigurationProblem(path, message);
    }

    private record InputResult(WorkIoType type, WorkInputSettings settings) {
    }

    private record OutputResult(WorkIoType type, WorkOutputSettings settings) {
    }
}
