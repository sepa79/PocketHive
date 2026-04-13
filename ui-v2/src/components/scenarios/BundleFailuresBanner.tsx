import { useState } from 'react'
import type { BundleLoadFailure } from '../../lib/scenariosApi'

export function BundleFailuresBanner({ failures }: { failures: BundleLoadFailure[] }) {
  const [expanded, setExpanded] = useState(false)
  if (failures.length === 0) return null

  return (
    <div
      className="card"
      style={{
        borderColor: 'rgba(255, 193, 7, 0.4)',
        background: 'rgba(255, 193, 7, 0.08)',
        marginBottom: 12,
      }}
    >
      <div className="row between">
        <div className="row" style={{ gap: 8 }}>
          <span className="pill pillWarn">
            ⚠ {failures.length} bundle{failures.length > 1 ? 's' : ''} failed to load
          </span>
          <span className="muted">These bundles are not available for use.</span>
        </div>
        <button
          type="button"
          className="actionButton actionButtonGhost"
          onClick={() => setExpanded((prev) => !prev)}
        >
          {expanded ? 'Hide details' : 'Show details'}
        </button>
      </div>

      {expanded && (
        <div style={{ marginTop: 12, display: 'grid', gap: 10 }}>
          {failures.map((failure) => (
            <div
              key={failure.bundlePath}
              style={{ borderTop: '1px solid rgba(255,255,255,0.08)', paddingTop: 10 }}
            >
              <div className="h2" style={{ fontSize: 12 }}>{failure.bundlePath}</div>
              <div className="muted" style={{ marginTop: 4 }}>{failure.reason}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
