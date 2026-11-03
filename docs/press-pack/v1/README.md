# PocketHive Press Set — V1

An English visual kit based on four user-supplied references: the light MCP slide, the dark HiveForge slide, the original NFT van and the piShowcase title image.

## Start here

- `index.html` — local asset catalogue; open in a browser after extracting the archive.
- `previews/brand-board.png` — overview of the visual direction.
- `previews/slide-night.png` and `previews/slide-day.png` — example compositions at 1920 × 1080.
- `slide-night.html` and `slide-day.html` — editable compositions with separate text layers. These are starting points, not an interactive roadmap or a PPTX template.

## Contents

| Folder | Contents |
|---|---|
| `assets/` | NFT van (1536 × 1024) and robot (1199 × 1312), transparent PNGs |
| `backgrounds/` | Night Drive with van; Clear Signal with a faint network. Native PNGs are 1672 × 941, approximately 16:9 |
| `logos/` | Logo for dark/light backgrounds and standalone mark, derived from the repository SVG |
| `icons/` | Seven icons in gold and ink: Hardening, SUTs, Datasets, Collections, Mocks, Test templating, Reporting |
| `badges/` | Smarter, 3DS, Business Reporting and three independent status labels |
| `png/` | 23 transparent PNG exports of logos, icons and badges |
| `previews/` | Brand board and two example slides |
| `tokens.json` | Palette, typography, slide dimensions and business mappings |
| `PROMPTS.json` | Exact image prompts and generation method |

## Visual guidelines

1. **Night Drive**: openings, big ideas and the roadmap. Dark ink, honey accents and controlled violet/cyan lighting. Keep the left side clear for copy.
2. **Clear Signal**: explanations, requirements, evidence and results. Light background, dark typography and the robot as a presenter.
3. Use `pockethive-dark` on dark backgrounds and `pockethive-light` on light backgrounds. Preserve proportions and leave breathing room around the mark.
4. Honey `#FFC107` leads attention. Violet `#8B5CF6` and cyan `#36DFFF` create atmosphere. Red belongs to the van; it does not indicate project status.
5. Slide typography: Arial Bold/Regular. Use large headlines, concise copy and one main message. The logo preserves the font family from the original SVG. PNG exports fix its appearance without depending on installed fonts.
6. For a 1920 × 1080 slide, allow around 96 px of safe space. Place raw backgrounds using fill with minimal cropping; never stretch characters.
7. Robot and van are separate illustrations. The Night Drive background already contains a van; do not add a second one.
8. Example headlines are proposed presentation copy. The established tagline remains `portable transaction swarm`.

## Business connections

- **Smarter** → SUTs, Datasets, Collections.
- **3DS** → Controllable Mocks.
- **Business communication** → Test Reporting.
- Hardening and Test templating have no additional business assignment in this edition.

**Available**, **Recently added** and **Planned** are independent reusable labels. This kit does not assign them automatically to features. The summary of delivered and recent improvements will be added later. The Test Reporting example explicitly shows a planned direction.

## Using the kit in a presentation

Place a background on a 16:9 slide. Add an SVG or PNG logo, your copy, and an optional mascot or feature icon. Put the business badge beside the benefit and show delivery status separately. Preview PNGs can be used directly, but their text is flattened. Use the editable HTML compositions or individual assets to change the copy.

## Sources and verification

- Illustrations and backgrounds: built-in `image_gen`, using the supplied reference images. These are new variants, not pixel-identical copies. Original user files were not modified.
- Logo: derivative of `ui-v2/public/logo.svg`, with a tighter view box and a dark wordmark for the light version. The application logo was not changed.
- Icons and badges: editable vectors created for this kit.
- Both characters have verified transparent alpha pixels. Backgrounds are opaque. All 23 SVGs have PNG exports. The brand board and both example slides were inspected in a browser.
- The local catalogue has no external fonts, analytics or network services. This kit does not change the PocketHive application.
