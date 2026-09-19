# Screenshot evidence

| Reader context | Details |
| --- | --- |
| Audience | Documentation authors and reviewers |
| Prerequisites | Access to the full-resolution image and the release/context evidence recorded below |
| Expected outcome | Identify what each screenshot shows, where it came from, what was changed after capture, and what it does not prove |
| Last verified PocketHive version | PocketHive `v0.15.35` |

Evidence boundary: official local ingress `http://localhost:8088`. Manifest
checked: 2026-07-30.

This page records the seven maintained screenshot assets. Six appear in the
current customer workflows; `help-hub.png` remains reference evidence. The
manifest records image identity and capture context, not proof that every UI
state, role, or deployment environment was visually verified.

## Capture boundary

The walkthrough used dark theme, the checked-in
`scenarios/bundles/local-rest-topology` example, and swarm `docs-demo`.
Signed-in captures used the local Compose DEV account `local-admin`; the Help
capture was anonymous. Device pixel ratio 1 was recorded for the capture
session. Visible values were generated demonstration data, and the review
recorded that no masking was required.

The exact source commit was not recorded with these image files. Treat
`v0.15.35` as their version boundary and do not attribute them to a later
commit without recapturing or independently verifying the same state.

All seven documentation assets are exactly 1440 × 900 pixels. Five are direct
1440 × 900 files. The original `help-hub.png` and `hive-runtime.png` frames were
1920 × 945; on 2026-07-30 they were proportionally reduced to 1440 × 709 and
centered on a 1440 × 900 canvas using the sampled application background
`rgb(6, 7, 11)`. No content was cropped, generated, or rearranged. Their
original browser viewport was not recorded, so describe them as normalized
documentation assets rather than direct 1440 × 900 captures.

## Image manifest

The application URL in the route column is relative to the official ingress.
The repository source for each linked image is its URL path beneath
`docs-site/static`.

| Image file | Exact application route | Documentation pixels / source DPR | Account | State shown | Sensitivity and redaction |
| --- | --- | --- | --- | --- | --- |
| [`home-overview.png`](/img/guides/ui/home-overview.png) | `/` | 1440 × 900 / 1 | Local DEV `local-admin` | Signed-in Home with task-oriented entry points | Generated demo data; no masking recorded |
| [`scenarios-workspace.png`](/img/guides/ui/scenarios-workspace.png) | `/scenarios` | 1440 × 900 / 1 | Local DEV `local-admin` | `local-rest-topology` selected with a valid result | Generated demo data; no masking recorded |
| [`help-hub.png`](/img/guides/ui/help-hub.png) | `/help` | 1440 × 900 normalized / 1 | Anonymous | Version-matched Help routes available without sign-in | Generated demo data; no masking recorded |
| [`create-swarm.png`](/img/guides/operators/create-swarm.png) | `/hive` | 1440 × 900 / 1 | Local DEV `local-admin` | Direct `docs-demo` creation form using `wiremock-local` | Generated demo data; no masking recorded |
| [`hive-runtime.png`](/img/guides/operators/hive-runtime.png) | `/hive` | 1440 × 900 normalized / 1 | Local DEV `local-admin` | Start dispatched with a fresh `RUNNING`/enabled snapshot | Generated demo data; no masking recorded |
| [`swarm-journal.png`](/img/guides/operators/swarm-journal.png) | `/journal` | 1440 × 900 / 1 | Local DEV `local-admin` | Demo create, template, and start Journal sequence | Generated demo data; no masking recorded |
| [`swarm-topology.png`](/img/guides/operators/swarm-topology.png) | `/hive/docs-demo/view` | 1440 × 900 / 1 | Local DEV `local-admin` | Enabled workers and the scenario's three-edge logical topology | Generated demo data; no masking recorded |

The account column describes the capture identity, not the minimum role
required for the route. The sensitivity column records the completed review;
every replacement image still requires a fresh check for tokens, customer
names, payloads, endpoints, correlation identifiers, and configuration.

## File identity

These SHA-256 values identify the files covered by this manifest:

```text
home-overview.png        f7cc9c6a290a5165e598a33539754475541212fb1628f7c4b96e69f9c0315943
scenarios-workspace.png  93fcec0c51639ac1623cad31f9498440225583985090ac3f4b6e0a532c6c516c
help-hub.png              e4b9c6c855a702a62098c91d51d69e1484c437069ffedb97ac70bdeb9a1aa9fd
create-swarm.png          92813dfe42949f38f722631f3110a6e79183b3e63433900ad6d37fbd18a58a0b
hive-runtime.png          3f9c396473bc8a9d1ab8cc5bb9e13e167edc4f37246134d6411ae55b101811c8
swarm-journal.png         8ab2cea00cc87f747d3554f29a77add38c14d0735e52271b4d2621076a34576c
swarm-topology.png        c9a20443787fb00b7f0742d0d4f39cccd7d7176c2291c64ee47c36b73eda32d7
```

A matching hash proves file identity only. It does not prove that the visible
state is still current in another release.

### Normalization provenance

| File | Original pixels | Original SHA-256 | Deterministic transformation |
| --- | ---: | --- | --- |
| `help-hub.png` | 1920 × 945 | `dde0c7eafe0347e355719863ea6448ca7d406075ea590697cbdad80968528217` | Proportional 1440 × 709 resize, centered with 95/96-pixel vertical padding |
| `hive-runtime.png` | 1920 × 945 | `d58afd2b5c713b0b83ce407d83174c1c9a504a2bdc54d739913e7fa66a186f5f` | Proportional 1440 × 709 resize, centered with 95/96-pixel vertical padding |

This provenance records the correction but does not replace a future
route-level recapture at a known 1440 × 900 CSS viewport.

## Alt text and caption standard

Alt text states the task or meaningful state visible in the image. Do not use
phrases such as "image of" or repeat the surrounding paragraph. A caption
tells the reader what to verify and, where it matters, the evidence boundary.

| Image | Recommended alt text | Caption intent and proof boundary |
| --- | --- | --- |
| `home-overview.png` | PocketHive Home with task-based entry points | Identify the routes available from Home; do not imply that every route is available to every role. |
| `scenarios-workspace.png` | Local REST topology selected and valid in the Scenarios workspace | Point out the bundle tree, selected file, metadata, and validation result. A valid workspace result does not prove deployment. |
| `help-hub.png` | PocketHive Help hub with task-based documentation links | Explain that Help routes to documentation bundled with the release. The screenshot alone does not prove every target resolves. |
| `create-swarm.png` | Create swarm form for the local REST topology and WireMock SUT | Identify the scenario, SUT, image-pull choice, and worker preview before creation. It does not prove that creation completed. |
| `hive-runtime.png` | Running docs-demo swarm with fresh enabled worker aggregate | Identify the aggregate and lifecycle controls. A single frame does not prove the full lifecycle history. |
| `swarm-journal.png` | Correlated create, template, and start entries in the Hive Journal | Explain how Journal connects requests, signals, and outcomes. The visible rows are not the complete Journal history. |
| `swarm-topology.png` | Enabled docs-demo workers and three-edge scenario topology | Relate worker scopes to the scenario graph. The logical graph does not prove live message flow. |

## Replacing or adding a screenshot

1. Capture the running PocketHive release through its official ingress. Do not
   substitute a mockup, an archived UI, or a direct service port.
2. Record the exact route, release and source commit, viewport, resulting pixel
   dimensions, DPR, theme, account/role, selected scenario or swarm, and UI
   state.
   If a file is normalized after capture, record the original hash, exact
   transformation, padding/crop decision, and new hash.
3. Inspect the full-resolution image for secrets and customer information.
   Record any masking; do not use "sanitized" as an assumption.
4. Verify that the visible state is supported by stronger evidence when the
   guide makes a runtime claim. A screenshot is orientation evidence, not
   lifecycle convergence evidence.
5. Store the file under `docs-site/static/img/guides/`, update this manifest
   and its SHA-256 value, then update the embedding page's alt text and caption.
6. Build the documentation at both `/` and `/docs/` and inspect the rendered
   image at a narrow and a desktop width.

## Related guides

- [PocketHive application guide](application-guide.md)
- [15-minute quickstart](../onboarding/quickstart-15min.md)
- [Swarm lifecycle](../operators/swarm-lifecycle.md)
- [Observability and troubleshooting](../operators/observability-troubleshooting.md)
