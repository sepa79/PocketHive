import { NavigationRail } from '@pockethive/ui'
import { PhNavigationItem } from './PhNavigationItem'
import { useAuth } from '../lib/authContext'

/**
 * Responsibility: select PH navigation entries from existing access projections.
 * Must not: derive permissions, own shared rail presentation or persist preferences.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell; RESP-UI-GLOBAL-ACCESS (access presentation).
 */
export function SideNav({ expanded, onExpandedChange }: { expanded: boolean; onExpandedChange: (expanded: boolean) => void }) {
  const auth = useAuth()

  return (
    <NavigationRail expanded={expanded} onExpandedChange={onExpandedChange} label="Navigation" expandLabel="Expand navigation" collapseLabel="Collapse navigation">
      {auth.canAccessPocketHive ? <PhNavigationItem to="/hive" title="Hive" icon="hive" expanded={expanded} /> : null}
      {auth.canAccessPocketHive ? <PhNavigationItem to="/proxy" title="Proxy" icon="proxy" expanded={expanded} /> : null}
      {auth.canAccessPocketHive ? <PhNavigationItem to="/journal" title="Journal" icon="journal" expanded={expanded} /> : null}
      {auth.canAccessPocketHive ? <PhNavigationItem to="/buzz" title="Buzz" icon="buzz" expanded={expanded} /> : null}
      {auth.canAccessPocketHive ? <PhNavigationItem to="/scenarios" title="Scenarios" icon="scenarios" expanded={expanded} /> : null}
      {auth.isAuthAdmin ? <PhNavigationItem to="/users" title="Users" icon="users" expanded={expanded} /> : null}
      <PhNavigationItem to="/other" title="Other" icon="other" expanded={expanded} />
    </NavigationRail>
  )
}
