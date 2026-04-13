# Flow Diagrams

---

## 1. Bundle loading flow (after fix)

```
ScenarioService.reload()
│
├── loadFailures.clear()
│
├── loadFromDirectory(storageDir)
│   └── for each *.yaml / *.json file
│       ├── try
│       │   ├── read(path, format)          ← parse YAML/JSON
│       │   ├── recordForLoaded()
│       │   │   ├── applyDefaultImageTag()
│       │   │   └── determineDefunct()
│       │   │       ├── id missing?         → DefunctResult.because("Scenario is missing a required 'id' field")
│       │   │       ├── template missing?   → DefunctResult.because("Scenario has no swarm template defined")
│       │   │       ├── image missing?      → DefunctResult.because("Controller image is not defined")
│       │   │       ├── no capability?      → DefunctResult.because("No capability manifest found for image '...'")
│       │   │       └── all ok              → DefunctResult.ok()
│       │   └── target.put(id, record)
│       │       └── duplicate?              → loadFailures.put(path, "Duplicate scenario id...")
│       └── catch Exception
│           └── loadFailures.put(path, "Could not read scenario file: ...")
│
├── loadFromBundles(bundleRootDir)          ← same pattern as above
│
├── scenarios.clear()
├── scenarios.putAll(loaded)
└── log: "Loaded N scenario(s) (M available)"
```

---

## 2. API call flow — ScenariosPage load

```
Browser (ScenariosPage)
│
├── reload() called on mount / Refresh button
│   │
│   ├── GET /scenario-manager/scenarios?includeDefunct=true
│   │   └── ScenarioController.list(includeDefunct=true)
│   │       └── ScenarioService.listAllSummaries()
│   │           └── returns all ScenarioRecords mapped to ScenarioSummary
│   │               (includes defunct=true/false + defunctReason)
│   │
│   ├── GET /scenario-manager/scenarios/folders
│   │   └── ScenarioController.listBundleFolders()
│   │
│   └── GET /scenario-manager/scenarios/failures        ← new
│       └── ScenarioController.failures()
│           └── ScenarioService.listLoadFailures()
│               └── returns loadFailures map values
│
├── setItems(list)
├── setFolders(folderList)
└── setFailures(failureList)
    │
    ├── failures.length > 0 → render BundleFailuresBanner
    └── items with defunct=true → render DEFUNCT pill + reason card
```

---

## 3. API call flow — CreateSwarmModal load

```
Browser (CreateSwarmModal)
│
├── loadTemplates() called when modal opens
│   │
│   ├── GET /scenario-manager/api/templates
│   │   └── CapabilityCatalogueController.templates()
│   │       └── availableScenarios.listAll()          ← changed from list()
│   │           └── returns ALL scenarios including defunct
│   │               with defunct=true/false + defunctReason
│   │
│   └── GET /scenario-manager/network-profiles
│
├── setTemplates(normalizeTemplates(payload))
│   └── defunct templates included with defunct=true
│
└── render template list
    ├── defunct entries: opacity 0.45, not clickable, reason shown
    └── available entries: normal, clickable
```

---

## 4. User flow — operator diagnoses a broken bundle

```
Operator opens Scenarios page
│
├── Sees BundleFailuresBanner: "2 bundles failed to load"
│   └── Clicks "Show details"
│       └── Reads: "bundles/my-broken-scenario — Could not read scenario file: ..."
│           └── Action: fixes the YAML syntax error in the file
│               └── Clicks Refresh → banner disappears
│
├── Sees scenario "CTAP ISO8583" with [DEFUNCT] badge
│   └── Clicks on it
│       └── Reads in details panel:
│           "No capability manifest found for image 'io.pockethive/generator:0.15.11'
│            (bee: generator). Check that this image version is installed."
│           └── Action: adds capability manifest for 0.15.11
│               OR updates scenario.yaml to use :latest
│               └── Clicks Refresh → DEFUNCT badge disappears
│
└── Opens Create Swarm modal
    └── Sees "CTAP ISO8583" greyed out with DEFUNCT pill
        └── Hovers → tooltip shows reason
            └── Cannot click / select it
```

---

## 5. Reload flow — operator fixes a file on disk

```
Operator fixes scenario.yaml on disk
│
└── POST /scenario-manager/scenarios/reload
    └── ScenarioService.reload()
        ├── loadFailures.clear()
        ├── re-scans all bundle directories
        └── fixed bundle now loads successfully
            └── GET /scenarios/failures → empty array
                └── BundleFailuresBanner disappears on next UI refresh
```

Note: The UI Refresh button on ScenariosPage re-fetches all three endpoints
(`/scenarios`, `/scenarios/folders`, `/scenarios/failures`) but does NOT
trigger a server-side reload. To pick up files changed on disk, an operator
must either restart the service or call `POST /scenarios/reload` directly.
This is existing behaviour and is not changed by this feature.
