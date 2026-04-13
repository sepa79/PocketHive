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
│ │ bundles/old-duplicate                                                    │ │
│ │ Duplicate scenario id 'local-rest' — another bundle at                   │ │
│ │ 'bundles/local-rest' was loaded instead                                  │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│ ┌──────────────────────────────┐  ┌──────────────────────────────────────┐  │
│ │ Folders          [Refresh]   │  │ Details                  [SELECTED]  │  │
│ │                              │  │                                      │  │
│ │ Filter: [All folders    ▼]   │  │ ID      ctap-iso8583-request-builder │  │
│ │ New folder: [          ]     │  │ Folder  bundles                      │  │
│ │ [Add] [Delete (empty only)]  │  │                                      │  │
│ │                              │  │ ┌──────────────────────────────────┐ │  │
│ ├──────────────────────────────┤  │ │ DEFUNCT  This bundle cannot be   │ │  │  ← pillBad card
│ │ Scenarios              [16]  │  │ │          used to create a swarm  │ │  │    pillBad border
│ │                              │  │ │                                  │ │  │
│ │ ▶ bundles (12)               │  │ │ No capability manifest found for │ │  │
│ │   ▶ ctap (1)                 │  │ │ image 'io.pockethive/generator:  │ │  │
│ │   │  [ctap-iso8583] [DEFUNCT]│  │ │ 0.15.11' (bee: generator).      │ │  │
│ │   ▶ webauth (4)              │  │ │ Check that this image version    │ │  │
│ │   │  [webauth-balance-redis] │  │ │ is installed.                    │ │  │
│ │   │  [webauth-loop-redis]    │  │ └──────────────────────────────────┘ │  │
│ │   │  ...                     │  │                                      │  │
│ │ ▶ (root) (4)                 │  │ Move to folder: [root          ▼]   │  │
│ │   [local-rest]               │  │                                      │  │
│ │   [local-rest-topology]      │  │ [Move] [Download bundle]             │  │
│ │   ...                        │  │ [Delete bundle]                      │  │
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
│ │ ▶ bundles (12)                   │  │                                  │  │
│ │   ▶ ctap (1)                     │  │                                  │  │
│ │   │                              │  │                                  │  │
│ │   │  ┌───────────────────────┐   │  │                                  │  │
│ │   │  │ CTAP ISO8583   DEFUNCT│   │  │  ← opacity 0.45, cursor:not-    │  │
│ │   │  │ bundles/ctap/ctap-... │   │  │    allowed, title=reason        │  │
│ │   │  │ No capability manifest│   │  │                                  │  │
│ │   │  │ found for image...    │   │  │                                  │  │
│ │   │  └───────────────────────┘   │  │                                  │  │
│ │   ▶ webauth (4)                  │  │                                  │  │
│ │   │  ┌───────────────────────┐   │  │                                  │  │
│ │   │  │ WebAuth Balance Redis │   │  │                                  │  │
│ │   │  │ bundles/webauth/...   │   │  │                                  │  │
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
