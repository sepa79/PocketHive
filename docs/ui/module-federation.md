# PocketHive UI Module Federation Notes

The shell now exposes a stable API for Module Federation remotes. Core providers have been pulled into `ShellProviders`, which wraps
children with:

- React Query's `QueryClientProvider` (shared instance).
- The global configuration context from `lib/config`.
- A lightweight theme context for future branding work.
- (Optionally) the shell `BrowserRouter` for host usage.

Remotes should import the shared hooks and helpers from the new `shell/shared` barrel to guarantee they consume the singleton
instances maintained by the host. Example usage from a remote micro-frontend:

```tsx
import { createShellRoot, useConfig, useUIStore } from 'shell/shared'

const shell = createShellRoot(container)

shell.render(<RemoteFeature />)

function RemoteFeature() {
  const cfg = useConfig()
  const { toggleSidebar } = useUIStore()

  return (
    <section>
      <span>{cfg.prometheus}</span>
      <button onClick={toggleSidebar}>Toggle shell sidebar</button>
    </section>
  )
}
```

See `ui/src/remote/createShellRoot.test.tsx` for an automated verification that remotes receive host-configured endpoints and the shared
Zustand store when mounted through the shell providers.
