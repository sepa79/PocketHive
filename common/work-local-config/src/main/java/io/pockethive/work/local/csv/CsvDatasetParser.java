package io.pockethive.work.local.csv;

import static io.pockethive.work.config.WorkConfigurationExpressions.symbolic;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Responsibility: parse complete CSV settings and merge patches through one validation contract.
 * Must not: load files, bind environment, render expressions or change accepted runtime state.
 * Contract: RESP-WORK-CSV-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-csv-settings.
 */
public final class CsvDatasetParser {
    public static final String PATH = "inputs.csv";
    public static final String FILE_PATH = "filePath", ROTATE = "rotate", SKIP_HEADER = "skipHeader",
        DELIMITER = "delimiter", CHARSET = "charset";
    public static final Set<String> FIELDS = Set.of(FILE_PATH, ROTATE, SKIP_HEADER, DELIMITER, CHARSET,
        InputRateParser.FIELD, InputScheduleField.STARTUP_DELAY_SECONDS.key(), InputScheduleField.TICK_INTERVAL_MS.key());

    public CsvDatasetSettings parse(Object value, String path) {
        var result = validate(value, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) throw new WorkConfigurationException(result.problems());
        return result.settings();
    }

    public CsvDatasetSettings merge(CsvDatasetSettings base, Map<?, ?> patch, String path) {
        Map<Object, Object> candidate = new LinkedHashMap<>(configuration(base));
        candidate.putAll(patch);
        return parse(candidate, path);
    }

    public static Map<String, Object> configuration(CsvDatasetSettings settings) {
        return Map.of(FILE_PATH, settings.filePath(), ROTATE, settings.rotate(), SKIP_HEADER, settings.skipHeader(),
            DELIMITER, settings.delimiter().pattern(), CHARSET, settings.charset().name(),
            InputRateParser.FIELD, settings.ratePerSec(),
            InputScheduleField.STARTUP_DELAY_SECONDS.key(), settings.startupDelaySeconds(),
            InputScheduleField.TICK_INTERVAL_MS.key(), settings.tickIntervalMs());
    }

    public CsvDatasetValidation validate(Object value, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(mode);
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        if (symbolic(value, path, mode, problems, deferred)) return new CsvDatasetValidation(null, problems, deferred);
        if (!(value instanceof Map<?, ?> fields)) {
            return new CsvDatasetValidation(null, List.of(new WorkConfigurationProblem(path, "CSV settings must be an object.")), List.of());
        }
        fields.keySet().stream().filter(key -> key == null || !FIELDS.contains(key)).forEach(key ->
            problems.add(new WorkConfigurationProblem(path + "." + key, "Unsupported CSV setting.")));
        String file = text(fields.get(FILE_PATH), path + "." + FILE_PATH, mode, problems, deferred);
        if (file != null) {
            try { Path.of(file); }
            catch (IllegalArgumentException ex) { problems.add(new WorkConfigurationProblem(path + "." + FILE_PATH, "Must be a valid path.")); }
        }
        Boolean rotate = bool(fields.get(ROTATE), path + "." + ROTATE, mode, problems, deferred);
        Boolean header = bool(fields.get(SKIP_HEADER), path + "." + SKIP_HEADER, mode, problems, deferred);
        String delimiter = text(fields.get(DELIMITER), path + "." + DELIMITER, mode, problems, deferred);
        Pattern pattern = null;
        if (delimiter != null) {
            try { pattern = Pattern.compile(delimiter); }
            catch (IllegalArgumentException ex) { problems.add(new WorkConfigurationProblem(path + "." + DELIMITER, "Must be a valid delimiter regex.")); }
        }
        String charsetName = text(fields.get(CHARSET), path + "." + CHARSET, mode, problems, deferred);
        Charset charset = null;
        if (charsetName != null) {
            try { charset = Charset.forName(charsetName); }
            catch (IllegalArgumentException ex) { problems.add(new WorkConfigurationProblem(path + "." + CHARSET, "Must name a supported charset.")); }
        }
        var rate = new InputRateParser().validate(fields.get(InputRateParser.FIELD), path + "." + InputRateParser.FIELD, mode);
        problems.addAll(rate.problems()); deferred.addAll(rate.deferredPaths());
        var schedule = new InputScheduleParser();
        var delayField = InputScheduleField.STARTUP_DELAY_SECONDS;
        var tickField = InputScheduleField.TICK_INTERVAL_MS;
        var delay = schedule.validate(fields.get(delayField.key()), delayField, path + "." + delayField.key(), mode);
        var tick = schedule.validate(fields.get(tickField.key()), tickField, path + "." + tickField.key(), mode);
        problems.addAll(delay.problems()); deferred.addAll(delay.deferredPaths());
        problems.addAll(tick.problems()); deferred.addAll(tick.deferredPaths());
        CsvDatasetSettings settings = problems.isEmpty() && deferred.isEmpty()
            ? new CsvDatasetSettings(file, rate.ratePerSec(), rotate, header, pattern, charset, delay.value(), tick.value(),
                schedule.startupDelayMillis(delay.value(), path + "." + delayField.key())) : null;
        return new CsvDatasetValidation(settings, problems, deferred);
    }

    private static String text(Object value, String path, WorkConfigurationMode mode,
                               List<WorkConfigurationProblem> problems, List<String> deferred) {
        if (symbolic(value, path, mode, problems, deferred)) return null;
        if (value instanceof String text && !text.isBlank()) return text;
        problems.add(new WorkConfigurationProblem(path, "Must be configured as nonblank text."));
        return null;
    }

    private static Boolean bool(Object value, String path, WorkConfigurationMode mode,
                                List<WorkConfigurationProblem> problems, List<String> deferred) {
        if (symbolic(value, path, mode, problems, deferred)) return null;
        if (value instanceof Boolean flag) return flag;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        problems.add(new WorkConfigurationProblem(path, "Must be boolean or exact true/false property text."));
        return null;
    }
}
