package io.pockethive.mcp.config;
import io.pockethive.mcp.config.EnvironmentHealthTargetProperties;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.mcp.application.EnvironmentHealthContract;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvironmentHealthPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void mapsOneValidatedCatalogueWithoutAddingOrChangingTargets() {
        EnvironmentHealthProperties properties = properties(Duration.ofSeconds(3), List.of(
            target("owner", "/owner/", "/owner/actuator/health")));

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.catalogue()).singleElement().satisfies(target -> {
            assertThat(target.id()).isEqualTo("owner");
            assertThat(target.name()).isEqualTo("Owner");
            assertThat(target.endpointPath().toString()).isEqualTo("/owner/");
            assertThat(target.probePath()).isEqualTo("/owner/actuator/health");
            assertThat(target.contract()).isEqualTo(EnvironmentHealthContract.SPRING_UP);
        });
    }

    @Test
    void rejectsMissingOrNonPositiveTimeout() {
        assertThat(validator.validate(properties(null, List.of(target("owner", "/", "/health")))))
            .isNotEmpty();
        assertThat(validator.validate(properties(Duration.ZERO, List.of(target("owner", "/", "/health")))))
            .isNotEmpty();
        assertThat(validator.validate(properties(Duration.ofSeconds(-1),
            List.of(target("owner", "/", "/health"))))).isNotEmpty();
    }

    @Test
    void rejectsDuplicateIdsAndNonIngressPaths() {
        assertThat(validator.validate(properties(Duration.ofSeconds(1), List.of(
            target("owner", "/one/", "/one/health"),
            target("owner", "/two/", "/two/health"))))).isNotEmpty();
        assertThat(validator.validate(properties(Duration.ofSeconds(1), List.of(
            target("owner", "https://other.example/", "/health"))))).isNotEmpty();
        assertThat(validator.validate(properties(Duration.ofSeconds(1), List.of(
            target("owner", "/owner/", "/health?alternate=true"))))).isNotEmpty();
    }

    private static EnvironmentHealthProperties properties(
        Duration timeout,
        List<EnvironmentHealthTargetProperties> targets
    ) {
        return new EnvironmentHealthProperties(timeout, targets);
    }

    private static EnvironmentHealthTargetProperties target(String id, String endpointPath, String probePath) {
        return new EnvironmentHealthTargetProperties(
            id, "Owner", endpointPath, probePath, EnvironmentHealthContract.SPRING_UP);
    }
}
