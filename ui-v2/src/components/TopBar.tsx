import { NavLink, useNavigate } from 'react-router-dom'
import { Breadcrumbs } from './Breadcrumbs'
import { ConnectivityIndicator } from './ConnectivityIndicator'
import { UserMenu } from './UserMenu'
import { LogoMark } from './LogoMark'
import { useTopBarContext } from './TopBarContext'

export function TopBar() {
  const navigate = useNavigate()
  const topBar = useTopBarContext()

  return (
    <div className="topBarInner">
      <div className="topBarLeft">
        <NavLink to="/" className="logoLink logoLinkBare" aria-label="PocketHive home" title="Home">
          <span className="brandMark" aria-hidden="true">
            <LogoMark size={28} />
          </span>
          <span className="brandWordmark" aria-hidden="true">
            <span className="brandWordPocket">Pocket</span>
            <span className="brandWordHive">Hive</span>
          </span>
        </NavLink>
        <Breadcrumbs />
      </div>
      <div className="topBarCenter">{topBar?.toolbar}</div>
      <div className="topBarRight">
        <ConnectivityIndicator />
        <button
          type="button"
          className="iconButton iconButtonBare"
          title="Help"
          onClick={() => navigate('/help')}
          aria-label="Help"
        >
          <span className="odysseyIcon" aria-hidden="true" />
        </button>
        <UserMenu />
      </div>
    </div>
  )
}
