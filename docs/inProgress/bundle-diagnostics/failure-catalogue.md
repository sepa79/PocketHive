# Bundle Failure Catalogue

Every reason a bundle can silently disappear from the scenario list, with the exact code location, cause, and user-facing message.

---

## Category 1 — Parse failure

**Where:** `loadFromBundles()` / `loadFromDirectory()` → `read(path, format)`

**Cause:** The `scenario.yaml` / `scenario.json` file contains malformed YAML or JSON that Jackson cannot parse. Currently this exception propagates and aborts the entire `reload()`, potentially hiding all other bundles.

**Fix required:** Catch `IOException` / `JsonProcessingException` per file, store as `BundleLoadFailure`, continue loading remaining bundles.

**User-facing message:**
```
Could not read scenario file: <trimmed Jackson message, class names stripped>
```

**Example:**
```
Could not read scenario file: mapping values are not allowed here at line 5, column 8
```

**Surfaces in UI:** `BundleFailuresBanner` on ScenariosPage (these bundles have no id so cannot appear in the list).

---

## Category 2 — Missing scenario id

**Where:** `determineDefunct()` → `if (scenarioId == null) return true`

**Cause:** The file parsed successfully but has no `id:` field or the field is blank.

**User-facing message:**
```
Scenario is missing a required 'id' field
```

**Surfaces in UI:** Defunct badge in scenario list + reason in details panel.

---

## Category 3 — No swarm template

**Where:** `determineDefunct()` → `if (template == null) return true`

**Cause:** The scenario YAML has no `template:` block.

**User-facing message:**
```
Scenario has no swarm template defined
```

**Surfaces in UI:** Defunct badge in scenario list + reason in details panel.

---

## Category 4 — Missing image on a component

**Where:** `checkImageReference()` → `if (imageReference == null || imageReference.isBlank())`

**Cause:** A bee or the controller has a blank or missing `image:` field.

**User-facing messages:**
```
Controller image is not defined
Bee 'generator' has no image defined
```

**Surfaces in UI:** Defunct badge in scenario list + reason in details panel.

---

## Category 5 — Unresolvable capability manifest

**Where:** `checkImageReference()` → `capabilities.findByImageReference(imageReference).isEmpty()`

**Cause:** The image reference is present but no capability manifest in `scenario-manager-service/capabilities/` matches it. Most common cause is a version tag mismatch (e.g. image uses `0.15.11` but only `latest` manifest is installed).

**User-facing message:**
```
No capability manifest found for image 'io.pockethive/generator:0.15.11' (bee: generator). Check that this image version is installed.
```

**Surfaces in UI:** Defunct badge in scenario list + reason in details panel + greyed entry in CreateSwarmModal template picker.

**How to fix:** Add or update the capability manifest YAML in `scenario-manager-service/capabilities/` to include the image tag in use, or update the scenario to reference an installed tag.

---

## Category 6 — Duplicate scenario id

**Where:** `loadFromBundles()` / `loadFromDirectory()` → `target.put(scenario.getId(), record)` silently overwrites

**Cause:** Two bundles on disk have the same `id:` value in their `scenario.yaml`. The second one wins and the first disappears with only a server-side `logger.warn`.

**Fix required:** When `target.put()` returns a non-null previous record, store a `BundleLoadFailure` for the losing bundle's path.

**User-facing message:**
```
Duplicate scenario id — another bundle at 'bundles/other-folder/my-scenario' was loaded instead
```

**Surfaces in UI:** `BundleFailuresBanner` on ScenariosPage.

---

## Summary table

| # | Category | Defunct flag | Surfaces in list | Surfaces in banner |
|---|---|---|---|---|
| 1 | Parse failure | — (no id) | ✗ | ✓ |
| 2 | Missing id | ✓ | ✓ | ✗ |
| 3 | No template | ✓ | ✓ | ✗ |
| 4 | Missing image | ✓ | ✓ | ✗ |
| 5 | No capability manifest | ✓ | ✓ + modal | ✗ |
| 6 | Duplicate id | — (no id for loser) | ✗ | ✓ |
