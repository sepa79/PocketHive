import { NavLink } from 'react-router-dom'
import { Icon } from './Icon'

function NavItem({
  to,
  title,
  icon,
  expanded,
}: {
  to: string
  title: string
  icon: Parameters<typeof Icon>[0]['name']
  expanded: boolean
}) {
  return (
    <NavLink to={to} className={expanded ? 'navItem' : 'navIcon'} title={title} aria-label={title}>
      <Icon name={icon} />
      {expanded ? <span className="navLabel">{title}</span> : null}
    </NavLink>
  )
}

export function SideNav({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  return (
    <div className="navIconStack">
      <div className={expanded ? 'navHeader navHeaderExpanded' : 'navHeader'}>
        <button
          type="button"
          className={expanded ? 'navCollapseBtn navCollapseBtnExpanded' : 'navCollapseBtn'}
          onClick={onToggle}
          title={expanded ? 'Collapse' : 'Expand'}
          aria-label={expanded ? 'Collapse navigation' : 'Expand navigation'}
        >
          {expanded ? '«' : '»'}
        </button>
        {expanded ? <div className="navHeaderTitle">Navigation</div> : null}
      </div>

      <NavItem to="/hive" title="Hive" icon="hive" expanded={expanded} />
      <NavItem to="/journal" title="Journal" icon="journal" expanded={expanded} />
      <NavItem to="/buzz" title="Buzz" icon="buzz" expanded={expanded} />
      <NavItem to="/scenarios" title="Scenarios" icon="scenarios" expanded={expanded} />
      <NavItem to="/other" title="Other" icon="other" expanded={expanded} />
    </div>
  )
}
