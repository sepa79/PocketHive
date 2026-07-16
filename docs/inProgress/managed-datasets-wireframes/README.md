# Managed Datasets responsive planning wireframes

These standalone wireframes visualise the read-only Managed Dataset experience
specified by the lifecycle specification and the normative
[Operator UI Design Specification](../managed-datasets-operator-ui-design-spec.md).
They do not modify or depend on the production UI runtime and are not
production implementation.

The UI design specification, not the illustrative values in this folder, is
the semantic and acceptance authority. Production `ui-v2` shall obtain every
dynamic fact from authorised canonical Orchestrator read models and shall show
an honest unavailable/empty/stale state when that authority is absent. It shall
never copy or fall back to these rows.

## Included views

- `#datasets` — cross-swarm Dataset inventory and operational filtering;
- `#dataset` — canonical Dataset detail, decisions, Fitness, supply, consumers,
  and evidence; and
- `#inspector` — existing Swarm Inspector extended with contextual Dataset
  dependencies above bounded runtime diagnostics.

Open `index.html` directly or serve the repository root with any static HTTP
server. Add `?capture=1` before the hash to hide the wireframe-only view switcher
for screenshots, for example `index.html?capture=1#datasets`.

Eligible supply uses a compact current/target value in the inventory. Hover or
keyboard focus reveals the exact eligible, minimum, target and replenishment
values; click or tap pins the explanation until it is dismissed. Dataset names
and safety-critical secondary text wrap instead of being silently truncated.

## Design boundaries

- PocketHive `ui-v2` shell, colours, typography, cards, pills, tabs and
  responsive conventions are the visual source of truth.
- Dataset Fitness, supply and swarm-local activation remain separate states.
- Supply coverage, freshness/Fitness continuity and worker application are
  presented as separate clocks and gates.
- `WARMING` remains the formal lifecycle state and is paired with the plain-
  language explanation that initial seeding is active.
- The wireframes are read-only and contain fictional opaque identifiers,
  aggregate counts, revisions, states and timestamps solely to exercise layout.
  They define no runtime default, seed, capacity expectation or acceptance
  value.
- It intentionally provides no Dataset values, payment data, secrets, raw
  provider output, datastore browsing, seed/refresh commands, or lifecycle
  mutation.
- Desktop, tablet and phone layouts use semantic tables, keyboard-operable tabs,
  visible focus and text labels in addition to colour.

## Firefox capture set

The complete spec-aligned evidence set is in `captures/spec-aligned/`:

- `00-all-desktop.png` — contact sheet for inventory, all five Dataset detail
  tabs and Swarm Inspector at 1440 x 1040;
- `00-all-mobile.png` — contact sheet for the same seven views at 390 x 844;
- `01` through `07` — individual desktop captures; and
- `08` through `14` — individual mobile captures. Detail-tab captures use a
  wireframe-only `focusPanel=1` capture parameter so the selected panel, not
  five identical page headers, is visible in the phone evidence.

Earlier visual-comparison evidence remains in `captures/`:

- `captures/datasets-desktop-1440.png` — Dataset inventory, desktop;
- `captures/datasets-supply-tooltip-desktop-1440.png` — exact supply explanation,
  desktop hover/focus state;
- `captures/dataset-detail-desktop-1440.png` — Dataset detail, desktop;
- `captures/dataset-fitness-desktop-1440.png` — current Fitness attempt separated
  from the prior PASS decision for existing traffic;
- `captures/swarm-inspector-desktop-1440.png` — Swarm Inspector, desktop;
- `captures/datasets-tablet-1024.png` — Dataset inventory, tablet;
- `captures/datasets-mobile-390.png` — semantic Dataset cards, phone;
- `captures/datasets-supply-tooltip-mobile-390.png` — expanded supply explanation,
  phone tap state;
- `captures/dataset-detail-mobile-390.png` — Dataset detail, phone; and
- `captures/swarm-inspector-mobile-390.png` — Dataset dependencies and bounded
  runtime context, phone.

The captures were rendered and visually verified in Firefox 140.12 ESR at
1440 px, 1024 px and 390 px widths. Captures prove layout and interaction
fidelity only; they do not prove production data authority or API behaviour.
