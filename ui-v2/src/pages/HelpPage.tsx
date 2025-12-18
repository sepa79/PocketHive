export function HelpPage() {
  return (
    <div className="page">
      <h1 className="h1">Help</h1>
      <div className="muted">
        Placeholder. Next step: context help per screen + links to PocketHive docs (and optionally a Markdown viewer).
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="h2">Quick links</div>
        <ul className="list">
          <li>
            <a href="https://github.com/sepa79/PocketHive/tree/main/docs" target="_blank" rel="noreferrer">
              Repository docs
            </a>
          </li>
          <li>
            <a href="https://github.com/sepa79/PocketHive/blob/main/docs/USAGE.md" target="_blank" rel="noreferrer">
              Usage
            </a>
          </li>
          <li>
            <a href="https://github.com/sepa79/PocketHive/blob/main/docs/ARCHITECTURE.md" target="_blank" rel="noreferrer">
              Architecture
            </a>
          </li>
        </ul>
      </div>
    </div>
  )
}

