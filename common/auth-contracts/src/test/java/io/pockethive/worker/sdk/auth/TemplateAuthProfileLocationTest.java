package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateAuthProfileLocationTest {
    @Test
    void nearestMatchStopsBeforeProbingAncestorsOrDefault() {
        Path nearest = Path.of("/bundle/templates/requests/profiles.yaml");
        List<Path> probes = new ArrayList<>();

        var selected = TemplateAuthProfileLocation.forTemplates("/bundle/templates/requests",
            Path.of("/fallback/profiles.yaml"), candidate -> {
                probes.add(candidate);
                return true;
            });

        assertThat(selected).contains(nearest);
        assertThat(probes).containsExactly(nearest);
    }

    @Test
    void searchesAncestorsInNearestFirstOrder() {
        Path ancestor = Path.of("/bundle/profiles.yaml");
        List<Path> probes = new ArrayList<>();

        var selected = TemplateAuthProfileLocation.forTemplates("/bundle/templates/requests",
            Path.of("/fallback/profiles.yaml"), candidate -> {
                probes.add(candidate);
                return candidate.equals(ancestor);
            });

        assertThat(selected).contains(ancestor);
        assertThat(probes).containsExactly(
            Path.of("/bundle/templates/requests/profiles.yaml"),
            Path.of("/bundle/templates/profiles.yaml"),
            ancestor);
    }

    @Test
    void checksExplicitDefaultAfterAllAncestorsUsingItsFilename() {
        Path defaultProfile = Path.of("/fallback/custom-profiles.yaml");
        List<Path> probes = new ArrayList<>();

        var selected = TemplateAuthProfileLocation.forTemplates("/bundle/templates", defaultProfile, candidate -> {
            probes.add(candidate);
            return candidate.equals(defaultProfile);
        });

        assertThat(selected).contains(defaultProfile);
        assertThat(probes).containsExactly(
            Path.of("/bundle/templates/custom-profiles.yaml"),
            Path.of("/bundle/custom-profiles.yaml"),
            Path.of("/custom-profiles.yaml"),
            defaultProfile);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void absentOrBlankRootsProbeOnlyTheExplicitDefault(String templateRoot) {
        Path defaultProfile = Path.of("/fallback/profiles.yaml");
        List<Path> probes = new ArrayList<>();

        var selected = TemplateAuthProfileLocation.forTemplates(templateRoot, defaultProfile, candidate -> {
            probes.add(candidate);
            return true;
        });

        assertThat(selected).contains(defaultProfile);
        assertThat(probes).containsExactly(defaultProfile);
    }

    @Test
    void relativeRootIsMadeAbsoluteAndNormalizedBeforeProbing() {
        Path expected = Path.of("requests/profiles.yaml").toAbsolutePath().normalize();
        List<Path> probes = new ArrayList<>();

        var selected = TemplateAuthProfileLocation.forTemplates("templates/../requests/.",
            Path.of("/fallback/profiles.yaml"), candidate -> {
                probes.add(candidate);
                return true;
            });

        assertThat(selected).contains(expected);
        assertThat(probes).containsExactly(expected);
    }

    @Test
    void exhaustedSearchReturnsEmptyAndRetainsTheAppendedDefaultProbe() {
        Path defaultProfile = Path.of("/bundle/profiles.yaml");
        List<Path> probes = new ArrayList<>();

        var selected = TemplateAuthProfileLocation.forTemplates("/bundle/templates", defaultProfile, candidate -> {
            probes.add(candidate);
            return false;
        });

        assertThat(selected).isEmpty();
        assertThat(probes).containsExactly(
            Path.of("/bundle/templates/profiles.yaml"),
            defaultProfile,
            Path.of("/profiles.yaml"),
            defaultProfile);
    }

    @Test
    void injectedBoundaryCanRejectOutsideCandidatesBeforeAFileProbe() {
        Path bundle = Path.of("/bundle");
        Path defaultProfile = bundle.resolve("profiles.yaml");
        List<Path> candidates = new ArrayList<>();
        List<Path> fileProbes = new ArrayList<>();

        var selected = TemplateAuthProfileLocation.forTemplates("/bundle/templates/requests", defaultProfile,
            candidate -> {
                candidates.add(candidate);
                if (!candidate.startsWith(bundle)) return false;
                fileProbes.add(candidate);
                return false;
            });

        assertThat(selected).isEmpty();
        assertThat(candidates).contains(Path.of("/profiles.yaml"));
        assertThat(fileProbes).isNotEmpty().contains(defaultProfile)
            .allMatch(candidate -> candidate.startsWith(bundle));
    }
}
