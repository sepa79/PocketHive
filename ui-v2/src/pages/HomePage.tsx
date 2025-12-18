import { Link } from 'react-router-dom'

export function HomePage() {
  const fullLogoSrc = `${import.meta.env.BASE_URL}logo.svg`
  return (
    <div className="page homePage">
      <div className="homeHero">
        <img className="homeLogoFull" src={fullLogoSrc} alt="PocketHive" />
        <div className="muted" style={{ textAlign: 'center' }}>
          UI v2 (WIP). This is the new shell foundation.
        </div>
      </div>

      <div className="tileGrid" style={{ marginTop: 14 }}>
        <Link className="tile" to="/scenarios">
          <div className="tileTitle">Scenarios</div>
          <div className="tileDesc">Browse, inspect, and edit scenario bundles.</div>
        </Link>
        <Link className="tile" to="/hive">
          <div className="tileTitle">Hive</div>
          <div className="tileDesc">Manage swarms and runtime state.</div>
        </Link>
        <Link className="tile" to="/journal">
          <div className="tileTitle">Journal</div>
          <div className="tileDesc">History and current runs.</div>
        </Link>
        <Link className="tile" to="/health">
          <div className="tileTitle">Connectivity</div>
          <div className="tileDesc">Backend health + connection details.</div>
        </Link>
      </div>
    </div>
  )
}
