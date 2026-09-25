package io.pockethive.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Responsibility: select existing download targets and filenames then delegate ZIP export.
 * Must not: encode ZIP entries, authorize HTTP requests or mutate bundles.
 * Contract: RESP-SCENARIO-BUNDLE-DOWNLOAD — docs/architecture/runtime-responsibilities.md#resp-scenario-bundle-download.
 */
@Service
public class ScenarioBundleDownloadService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioBundleDownloadService.class);
    private final ScenarioService scenarios;
    private final ScenarioBundleZipExporter exporter;
    public ScenarioBundleDownloadService(ScenarioService scenarios, ScenarioBundleZipExporter exporter) {
        this.scenarios = scenarios;
        this.exporter = exporter;
    }
    public BundleDownload byScenarioId(String id) throws IOException {
        Scenario scenario = scenarios.find(id).orElseThrow(ScenarioDownloadNotFoundException::new);
        Path root;
        try {
            root = scenarios.bundleDirFor(scenario.getId());
        } catch (IllegalArgumentException e) {
            throw new ScenarioDownloadNotFoundException("Scenario bundle not found", e);
        }
        if (!Files.isDirectory(root)) {
            log.warn("Bundle directory {} for scenario '{}' not found", root, id);
            throw new ScenarioDownloadNotFoundException("Scenario bundle not found");
        }
        return new BundleDownload(exporter.export(root), scenario.getId() + "-bundle.zip");
    }
    public BundleDownload byBundleKey(String bundleKey) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            if (location.root() == null || !Files.isDirectory(location.root())) {
                throw new IllegalArgumentException("Bundle '%s' not found".formatted(location.bundleKey()));
            }
            return new BundleDownload(exporter.export(location.root()),
                scenarios.fallbackBundleName(location.bundlePath()) + "-bundle.zip");
        }
    }
}
