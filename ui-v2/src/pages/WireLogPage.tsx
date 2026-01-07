import { useEffect, useMemo, useState } from 'react'
import {
  clearWireLog,
  exportWireLogJsonl,
  subscribeWireLog,
  type WireLogEntry,
} from '../lib/controlPlane/wireLogStore'

function formatFileName() {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(
    now.getHours(),
  )}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `wire-log-${stamp}.jsonl`
}

function downloadJsonl() {
  const payload = exportWireLogJsonl()
  const blob = new Blob([payload], { type: 'application/jsonl' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = formatFileName()
  link.click()
  URL.revokeObjectURL(url)
}

export function WireLogPage() {
  const [entries, setEntries] = useState<WireLogEntry[]>([])

  useEffect(() => {
    const unsubscribe = subscribeWireLog(setEntries)
    return () => {
      unsubscribe()
    }
  }, [])

  const newest = useMemo(() => [...entries].reverse(), [entries])
  const invalidCount = useMemo(
    () => entries.reduce((sum, entry) => sum + entry.errors.length, 0),
    [entries],
  )

  return (
    <div className="page">
      <div className="row between">
        <div>
          <h1 className="h1">Wire Log (Buzz v2)</h1>
          <div className="muted">Raw control-plane traffic. No masking in v1.</div>
        </div>
        <div className="row">
          <button type="button" className="actionButton" onClick={downloadJsonl}>
            Export JSONL
          </button>
          <button type="button" className="actionButton actionButtonDanger" onClick={clearWireLog}>
            Clear
          </button>
        </div>
      </div>

      <div className="card" style={{ marginTop: 12 }}>
        <div className="row between">
          <div className="h2">Summary</div>
          <div className={invalidCount > 0 ? 'pill pillBad' : 'pill pillOk'}>
            {invalidCount > 0 ? `${invalidCount} invalid` : 'all valid'}
          </div>
        </div>
        <div className="kvGrid" style={{ marginTop: 10 }}>
          <div className="kv">
            <div className="k">Entries</div>
            <div className="v">{entries.length}</div>
          </div>
          <div className="kv">
            <div className="k">Retention</div>
            <div className="v">5,000 entries / 10 MB</div>
          </div>
        </div>
      </div>

      {newest.length === 0 ? (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="muted">No messages captured yet.</div>
        </div>
      ) : (
        <div className="wireLogList">
          {newest.map((entry) => (
            <div className="card" key={entry.id} style={{ marginTop: 12 }}>
              <div className="row between">
                <div className="h2">
                  {entry.receivedAt} · {entry.source}
                </div>
                <div className={entry.errors.length > 0 ? 'pill pillBad' : 'pill pillOk'}>
                  {entry.errors.length > 0 ? 'invalid' : 'ok'}
                </div>
              </div>
              <div className="muted" style={{ marginTop: 6 }}>
                {entry.routingKey ?? 'n/a'}
              </div>
              {entry.errors.length > 0 ? (
                <ul className="list">
                  {entry.errors.map((error, index) => (
                    <li key={`${entry.id}-error-${index}`}>
                      {error.errorCode}: {error.message}
                    </li>
                  ))}
                </ul>
              ) : null}
              <pre className="codePre" style={{ marginTop: 10 }}>
                {entry.payload}
              </pre>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
