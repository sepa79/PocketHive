import { useEffect, useState } from 'react'

type UiState = 'connecting' | 'healthy' | 'unhealthy' | 'down'

export default function Health() {
  const [state, setState] = useState<UiState>('connecting')

  useEffect(() => {
    let last: string | null = null
    const ping = async () => {
      try {
        const res = await fetch('/healthz', { cache: 'no-store' })
        const text = await res.text().catch(() => '')
        const ok = res.ok && /ok/i.test(text)
        const status: UiState = ok ? 'healthy' : 'unhealthy'
        if (status !== last) {
          setState(status)
          last = status
        }
      } catch {
        if (last !== 'down') {
          setState('down')
          last = 'down'
        }
      }
    }
    ping()
    const id = setInterval(ping, 15000)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="hal">
      <span id="status-ui" className="hal-eye" data-state={state} title="UI Health"></span>
    </div>
  )
}
