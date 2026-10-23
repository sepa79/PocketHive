import { NavigationItem } from '@pockethive/ui'
import { NavLink } from 'react-router-dom'
import { Icon } from './Icon'

type PhNavigationItemProps = {
  to: string
  title: string
  icon: Parameters<typeof Icon>[0]['name']
  expanded: boolean
}

/**
 * Responsibility: adapt shared navigation presentation to PH routes and icons.
 * Must not: select menu visibility or maintain a separate active route.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function PhNavigationItem({ to, title, icon, expanded }: PhNavigationItemProps) {
  return (
    <NavigationItem label={title} icon={<Icon name={icon} />} expanded={expanded}>
      {(presentation) => <NavLink to={to} {...presentation} />}
    </NavigationItem>
  )
}
