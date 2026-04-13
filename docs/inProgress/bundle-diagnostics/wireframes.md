# Wireframes

ASCII wireframes for all affected UI surfaces. Uses existing CSS classes from `ui-v2/src/styles.css`.

---

## 1. ScenariosPage — with failures banner (expanded)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Scenarios                                          [Upload bundle]           │
├─────────────────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ ⚠ 2 bundles failed to load                        [Hide details]        │ │  ← BundleFailuresBanner
│ │ These bundles are not available for use.                                 │ │    card with pillWarn border
│ │ ─────────────────────────────────────────────────────────────────────── │ │
│ │ bundles/my-broken-scenario                                               │ │
│ │ Could not read scenario file: mapping values are not allowed here        │ │
│ │ at line 5, column 8                                                      │ │
│ │ ─────────────────────────────────────────────────────────────────────── │ │
│ │ bundles/my-scenario-copy                                                 │ │
│ │ Duplicate scenario id 'my-scenario' — another bundle at                  │ │
│ │ 'bundles/my-scenario' was loaded instead                                 │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│ ┌──────────────────────────────┐  ┌──────────────────────────────────────┐  │
│ │ Folders          [Refresh]   │  │ Details                  [SELECTED]  │  │
│ │                              │  │                                      │  │
│ │ Filter: [All folders    ▼]   │  │ ID      my-broken-scenario           │  │
│ │ New folder: [          ]     │  │ Folder  bundles                      │  │
│ │ [Add] [Delete (empty only)]  │  │                                      │  │
│ │                              │  │ ┌──────────────────────────────────┐ │  │
│ ├──────────────────────────────┤  │ │ DEFUNCT  This bundle cannot be   │ │  │  ← pillBad card
│ │ Scenarios              [8]   │  │ │          used to create a swarm  │ │  │    pillBad border
│ │                              │  │ │                                  │ │  │
│ │ ▶ bundles (4)                │  │ │ No capability manifest found for │ │  │
│ │   ▶ demo (1)                 │  │ │ image 'io.pockethive/generator:0.15.11'           │ │  │
│ │   │  [my-broken] [DEFUNCT]   │  │ │ (bee: generator). Check that     │ │  │
│ │   ▶ perf (2)                 │  │ │ this image version is installed. │ │  │
│ │   │  [perf-scenario-a]       │  │ └──────────────────────────────────┘ │  │
│ │   │  [perf-scenario-b]       │  │                                      │  │
│ │ ▶ (root) (2)                 │  │ Move to folder: [root          ▼]   │  │
│ │   [my-scenario]              │  │                                      │  │
│ │   [my-scenario-2]            │  │ [Move] [Download bundle]             │  │
│ │                              │  │ [Delete bundle]                      │  │
│ └──────────────────────────────┘  └──────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Notes:**
- `[DEFUNCT]` pill uses `pillBad` class (red)
- Defunct scenario button has `opacity: 0.75`
- Defunct reason card uses `pillBad` border + background
- Action buttons (Move, Download, Delete) remain enabled for defunct bundles
- Banner is collapsed by default, expanded on click

---

## 2. ScenariosPage — no failures (healthy state)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Scenarios                                          [Upload bundle]           │
├─────────────────────────────────────────────────────────────────────────────┤
│  ← no banner shown                                                           │
│                                                                               │
│ ┌──────────────────────────────┐  ┌──────────────────────────────────────┐  │
│ │ Folders          [Refresh]   │  │ Details                  [SELECTED]  │  │
│ │ ...                          │  │ ...                                  │  │
│ └──────────────────────────────┘  └──────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Notes:** When `failures.length === 0`, `BundleFailuresBanner` renders nothing. No visual change to the healthy state.

---

## 3. CreateSwarmModal — template picker with defunct entries

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Create swarm                                                      [Close]    │
│ Provision a controller from a scenario template.                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ Swarm ID: [demo                    ]   Network mode: [Direct          ▼]    │
│                                                                               │
│ ┌──────────────────────────────────┐  ┌──────────────────────────────────┐  │
│ │ Templates    [Filter...        ] │  │ (select a template)              │  │
│ │ 1 template(s) unavailable —      │  │                                  │  │
│ │ hover for details                │  │                                  │  │
│ │ ─────────────────────────────── │  │                                  │  │
│ │ ▶ bundles (4)                    │  │                                  │  │
│ │   ▶ demo (1)                     │  │                                  │  │
│ │   │                              │  │                                  │  │
│ │   │  ┌───────────────────────┐   │  │                                  │  │
│ │   │  │ My Broken   DEFUNCT   │   │  │  ← opacity 0.45, cursor:not-    │  │
│ │   │  │ bundles/demo/my-bro.. │   │  │    allowed, title=reason        │  │
│ │   │  │ No capability manifest│   │  │                                  │  │
│ │   │  │ found for image...    │   │  │                                  │  │
│ │   │  └───────────────────────┘   │  │                                  │  │
│ │   ▶ perf (2)                     │  │                                  │  │
│ │   │  ┌───────────────────────┐   │  │                                  │  │
│ │   │  │ Perf Scenario A       │   │  │                                  │  │
│ │   │  │ bundles/perf/...      │   │  │                                  │  │
│ │   │  │ No description        │   │  │                                  │  │
│ │   │  └───────────────────────┘   │  │                                  │  │
│ └──────────────────────────────────┘  └──────────────────────────────────┘  │
│                                                                               │
│ [ ] Pull images on create                                        [Create]    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Notes:**
- Defunct template item: `opacity: 0.45`, `cursor: not-allowed`, not clickable
- `DEFUNCT` pill (red) shown inline in the template button header
- Description area shows the `defunctReason` text instead of the scenario description
- `title` attribute on the button shows the reason on hover
- Notice line "N template(s) unavailable — hover for details" shown above the list when any defunct exist
- Clicking a defunct entry does nothing (early return in onClick)
- If somehow a defunct template id ends up selected and user clicks Create, a validation error is shown

---

## 4. Pill and card styles reference

All styles use existing classes from `ui-v2/src/styles.css`:

| Element | Class | Colour |
|---|---|---|
| DEFUNCT pill | `pill pillBad` | Red border + background |
| Defunct reason card border | inline style `rgba(255, 95, 95, 0.35)` | Red |
| Defunct reason card background | inline style `rgba(255, 95, 95, 0.08)` | Red tint |
| Failures banner pill | `pill pillWarn` | Amber |
| Failures banner card border | inline style `rgba(255, 193, 7, 0.4)` | Amber |
| Failures banner card background | inline style `rgba(255, 193, 7, 0.08)` | Amber tint |
