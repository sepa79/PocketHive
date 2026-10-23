# Pocket UI foundation

`@pockethive/ui` owns the shared React presentation shell, not an application's
authentication, routes, permissions, menus, preferences or API state. Its current
consumer is PH; PocketPlanner and PocketData can import the same package.

## Typed API

- `AppShell`: required `navigationExpanded`, `header`, `navigation`, `toolbar` and
  `children` slots. Use `null` for intentionally absent regions.
- `AppHeader`: required `leading`, `center` and `trailing` slots. Account controls,
  logo and notices come from the application.
- `NavigationRail`: controlled `expanded`, `onExpandedChange(boolean)`, `label`,
  `expandLabel`, `collapseLabel` and application-supplied children. It never writes
  browser storage or selects a default application view.
- `NavigationItem`: `label`, `icon`, `expanded` and a render function receiving
  link presentation props. The host supplies its router link, destination and
  active-route semantics. Forward the supplied props to that link.

Import the canonical theme baseline once at the application entry:

```tsx
import '@pockethive/ui/styles.css'
import { AppHeader, AppShell, NavigationItem, NavigationRail } from '@pockethive/ui'
```

Layout uses CSS Modules. Shared tokens live only in `src/tokens.css` and select the
light variant via `document.documentElement.dataset.theme = 'light'`. Changing
the selected theme remains the host's responsibility. Domain-specific styles
remain in the application; do not copy them into this package or copy these
components into another app.

## Build and verification

Use Node 22.22.2 or a Node version supported by the consuming PH Vite release.
The source package is compiled by the consumer, with React as a peer dependency.
Install the shared package before the consumer; Vite deduplicates React against
the consuming application so hooks use its one runtime.

```sh
npm ci --prefix packages/ui
npm run typecheck --prefix packages/ui
npm test --prefix packages/ui
npm ci --prefix ui-v2
npm test --prefix ui-v2
npm run build --prefix ui-v2
```

The PH Docker build installs this source package in the build stage only. No
shared Node process, authentication service, store or runtime server is added.

Ownership: `docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell`.
