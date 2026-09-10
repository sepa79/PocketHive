package io.pockethive.worker.sdk.input.csv;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CsvDataSetWorkInputTest {

    @Test
    void invalidRateRejectsCsvOverridesBeforeChangingTheFile() {
        var properties = baseProperties();
        var input = inputFor(properties);
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("filePath", "/other.csv");
        fields.put("ratePerSec", null);
        assertThatThrownBy(() -> input.applyRawConfigOverrides(java.util.Map.of("inputs", java.util.Map.of("csv", fields))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inputs.csv.ratePerSec");
        assertThat(properties.getFilePath()).isEqualTo("/app/scenario/users.csv");
        assertThat(properties.ratePerSec()).isEqualTo(1.0);
        fields.put("ratePerSec", "3.0");
        input.applyRawConfigOverrides(java.util.Map.of("inputs", java.util.Map.of("csv", fields)));
        assertThat(properties.getFilePath()).isEqualTo("/other.csv");
        assertThat(properties.ratePerSec()).isEqualTo(3.0);
    }

    @Test
    void validatesDirectRuntimeConfigWithHighRateAndNoBusinessUpperBound() {
        CsvDataSetInputProperties properties = baseProperties();
        properties.setRatePerSec(2500.5);

        CsvDataSetWorkInput input = inputFor(properties);

        assertThatCode(input::validateConfiguration)
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsNegativeDirectRuntimeRateBeforeSchedulerStart() {
        CsvDataSetInputProperties properties = baseProperties();
        properties.setRatePerSec(-0.1);

        CsvDataSetWorkInput input = inputFor(properties);

        assertThatThrownBy(input::validateConfiguration)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputs.csv.ratePerSec")
            .hasMessageContaining(">= 0.0");
    }

    @Test
    void rejectsDirectRuntimeTickIntervalBelowManifestRangeBeforeSchedulerStart() {
        CsvDataSetInputProperties properties = baseProperties();
        properties.setTickIntervalMs(99);

        CsvDataSetWorkInput input = inputFor(properties);

        assertThatThrownBy(input::validateConfiguration)
            .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
            .hasMessageContaining("inputs.csv.tickIntervalMs")
            .hasMessageContaining(">= 100");
    }

    private static CsvDataSetInputProperties baseProperties() {
        CsvDataSetInputProperties properties = new CsvDataSetInputProperties();
        properties.setFilePath("/app/scenario/users.csv");
        properties.setRatePerSec(1.0);
        properties.setRotate(false);
        properties.setSkipHeader(true);
        properties.setDelimiter(",");
        properties.setCharset("UTF-8");
        properties.setStartupDelaySeconds(0);
        properties.setTickIntervalMs(1000);
        return properties;
    }

    private static CsvDataSetWorkInput inputFor(CsvDataSetInputProperties properties) {
        WorkerRuntime runtime = (workerBeanName, message) -> message;
        return new CsvDataSetWorkInput(
            definition(),
            mock(WorkerControlPlaneRuntime.class),
            runtime,
            new ControlPlaneIdentity("swarm-1", "role", "instance-1"),
            properties,
            LoggerFactory.getLogger("test-csv-input")
        );
    }

    private static WorkerDefinition definition() {
        return new WorkerDefinition(
            "csvWorker",
            Object.class,
            WorkerInputType.CSV_DATASET,
            "test-role",
            WorkIoBindings.none(),
            Void.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "Test CSV dataset worker",
            Set.of()
        );
    }
}
