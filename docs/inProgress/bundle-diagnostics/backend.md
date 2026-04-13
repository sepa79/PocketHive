# Backend Changes — Scenario Manager

## Overview

All changes are isolated to `scenario-manager-service`. No other services are touched.

---

## 1. New record: `BundleLoadFailure`

Add as a public record inside `ScenarioService`:

```java
public record BundleLoadFailure(
    String bundlePath,   // relative path e.g. "bundles/my-scenario"
    String reason        // human-readable plain English
) {}
```

Add a concurrent map alongside the existing `scenarios` map:

```java
private final Map<String, BundleLoadFailure> loadFailures = new ConcurrentHashMap<>();
```

The key is the relative bundle path string. This map is cleared and repopulated on every `reload()`.

---

## 2. New record: `DefunctResult`

Replace the `boolean` return type of `determineDefunct()` with a result record:

```java
private record DefunctResult(boolean defunct, String reason) {
    static DefunctResult ok() { return new DefunctResult(false, null); }
    static DefunctResult because(String reason) { return new DefunctResult(true, reason); }
}
```

Update `determineDefunct()` to return `DefunctResult` and produce a human-readable reason for each failure category (see [failure-catalogue.md](./failure-catalogue.md)).

---

## 3. Updated `ScenarioRecord`

Add `defunctReason` field:

```java
private record ScenarioRecord(
    Scenario scenario,
    Format format,
    boolean defunct,
    String defunctReason,   // ← new, null when not defunct
    Path descriptorFile,
    Path bundleDir,
    String folderPath
) {}
```

Update `recordForLoaded()` to populate `defunctReason` from `DefunctResult`.

---

## 4. Updated `ScenarioSummary`

```java
public record ScenarioSummary(
    String id,
    String name,
    String folderPath,
    boolean defunct,          // ← new
    String defunctReason      // ← new, null when not defunct
) {}
```

Update `toSummary()` to populate both new fields from `ScenarioRecord`.

---

## 5. Harden `reload()` — per-bundle error isolation

### `loadFromBundles()`

Wrap the per-file block in a try/catch:

```java
try {
    Format format = detectFormat(path);
    Scenario scenario = read(path, format);
    ScenarioRecord record = recordForLoaded(scenario, format, path, path.getParent());
    ScenarioRecord previous = target.put(scenario.getId(), record);
    if (previous != null) {
        // Category 6 — duplicate id
        String loserPath = relativeBundlePath(path.getParent());
        String winnerPath = relativeBundlePath(previous.bundleDir());
        loadFailures.put(loserPath, new BundleLoadFailure(
            loserPath,
            "Duplicate scenario id '" + scenario.getId() + "' — another bundle at '" + winnerPath + "' was loaded instead"
        ));
        logger.warn("Duplicate scenario id '{}' found while loading {}; keeping latest", scenario.getId(), path);
    }
} catch (Exception e) {
    // Category 1 — parse failure
    String relPath = relativeBundlePath(path.getParent());
    String reason = cleanParseError(e.getMessage());
    loadFailures.put(relPath, new BundleLoadFailure(relPath, "Could not read scenario file: " + reason));
    logger.warn("Failed to load bundle at {}: {}", path, e.getMessage());
}
```

Apply the same pattern to `loadFromDirectory()`.

### `reload()` — clear failures at start

```java
public synchronized void reload() throws IOException {
    Map<String, ScenarioRecord> loaded = new HashMap<>();
    loadFailures.clear();   // ← add this
    // ... rest unchanged
}
```

### Helper: `cleanParseError(String message)`

Strip Java class names and stack noise from Jackson messages:

```java
private static String cleanParseError(String message) {
    if (message == null) return "unknown parse error";
    // Remove "com.fasterxml..." prefixes
    String cleaned = message.replaceAll("\\(com\\.fasterxml[^)]*\\)", "").trim();
    // Truncate at 200 chars
    return cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned;
}
```

### Helper: `relativeBundlePath(Path dir)`

```java
private String relativeBundlePath(Path dir) {
    if (dir == null) return "unknown";
    try {
        return bundleRootDir.toAbsolutePath().normalize()
            .relativize(dir.toAbsolutePath().normalize())
            .toString().replace('\\', '/');
    } catch (Exception e) {
        return dir.toString().replace('\\', '/');
    }
}
```

---

## 6. New public method: `listLoadFailures()`

```java
public List<BundleLoadFailure> listLoadFailures() {
    return loadFailures.values().stream()
        .sorted(Comparator.comparing(BundleLoadFailure::bundlePath))
        .toList();
}
```

---

## 7. New endpoint: `GET /scenarios/failures`

Add to `ScenarioController`:

```java
@GetMapping(value = "/failures", produces = MediaType.APPLICATION_JSON_VALUE)
public List<BundleLoadFailureView> failures() {
    log.info("[REST] GET /scenarios/failures");
    List<BundleLoadFailureView> result = service.listLoadFailures().stream()
        .map(f -> new BundleLoadFailureView(f.bundlePath(), f.reason()))
        .toList();
    log.info("[REST] GET /scenarios/failures -> {} items", result.size());
    return result;
}

public record BundleLoadFailureView(String bundlePath, String reason) {}
```

---

## 8. Updated `GET /api/templates`

In `CapabilityCatalogueController`, change `templates()` to use `listAllSummaries()` instead of `list()` so defunct scenarios are included. Add `defunct` and `defunctReason` to `ScenarioTemplateView`:

```java
public record ScenarioTemplateView(
    String id,
    String name,
    String folderPath,
    String description,
    String controllerImage,
    List<BeeImage> bees,
    boolean defunct,          // ← new
    String defunctReason      // ← new
) {}
```

Update `buildScenarioTemplate()` to populate from the summary's `defunct` and `defunctReason`.

---

## 9. `determineDefunct()` — human-readable reasons per category

```java
private DefunctResult determineDefunct(Scenario scenario) {
    String scenarioId = scenario.getId();
    if (scenarioId == null || scenarioId.isBlank()) {
        return DefunctResult.because("Scenario is missing a required 'id' field");
    }
    SwarmTemplate template = scenario.getTemplate();
    if (template == null) {
        return DefunctResult.because("Scenario has no swarm template defined");
    }

    List<String> reasons = new ArrayList<>();
    checkImageReference(scenarioId, "controller", template.image(), reasons);
    if (template.bees() != null) {
        for (Bee bee : template.bees()) {
            checkImageReference(scenarioId, "bee '" + bee.role() + "'", bee.image(), reasons);
        }
    }
    if (!reasons.isEmpty()) {
        return DefunctResult.because(String.join("; ", reasons));
    }
    return DefunctResult.ok();
}
```

Update `checkImageReference()` to add human-readable strings to the `reasons` list:

- Missing image: `"Controller image is not defined"` / `"Bee 'generator' has no image defined"`
- No manifest: `"No capability manifest found for image 'io.pockethive/generator:0.15.11' (bee: generator). Check that this image version is installed."`
