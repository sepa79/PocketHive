import { useCallback, useEffect, useRef, useState } from 'react'
import { Plug, Square } from 'lucide-react'
import { Client } from '@stomp/stompjs'

export default function Connectivity() {
  const [state, setState] = useState<'connected' | 'connecting' | 'disconnected'>('disconnected')
  const [menuOpen, setMenuOpen] = useState(false)
  const clientRef = useRef<Client | null>(null)
  const host = typeof window !== 'undefined' ? window.location.hostname : 'localhost'
  const url = `ws://${host}:15674/ws`
  const login = 'guest'
  const passcode = 'guest'

  const connect = useCallback(() => {
    setState('connecting')
    const client = new Client({
      brokerURL: url,
      connectHeaders: { login, passcode },
      reconnectDelay: 0,
      onConnect: () => setState('connected'),
      onWebSocketClose: () => setState('disconnected'),
      onStompError: () => setState('disconnected'),
    })
    client.activate()
    clientRef.current = client
  }, [url])

  const disconnect = useCallback(() => {
    clientRef.current?.deactivate()
    clientRef.current = null
    setState('disconnected')
  }, [])

  useEffect(() => {
    connect()
    return () => disconnect()
  }, [connect, disconnect])

  const toggle = () => {
    if (state === 'connected') {
      disconnect()
    } else {
      connect()
    }
  }

  const plugClasses =
    state === 'connected'
      ? 'text-green-400'
      : state === 'connecting'
      ? 'text-blue-400 animate-plug-in'
      : 'text-red-400 -translate-x-2'

  return (
    <div
      className="relative"
      onMouseEnter={() => setMenuOpen(true)}
      onMouseLeave={() => setMenuOpen(false)}
    >
      <button
        onClick={toggle}
        className="inline-flex items-center justify-center h-6 w-6"
        title={state === 'connected' ? 'Disconnect' : 'Connect'}
        aria-label={state === 'connected' ? 'Disconnect' : 'Connect'}
      >
        <div className="relative h-6 w-6">
          <Square size={20} className="absolute inset-1 text-white/30" />
          <Plug size={20} className={`absolute inset-1 transition-transform ${plugClasses}`} />
        </div>
      </button>
      {menuOpen && (
        <div className="absolute right-0 mt-1 w-48 rounded border border-white/20 bg-[#080a0e] p-2 text-xs shadow-lg">
          <div className="mb-1 font-semibold">Connection</div>
          <div>Status: {state}</div>
          <div>User: {login}</div>
          <div>Password: {passcode}</div>
          <div className="break-all">URL: {url}</div>
          <button
            className="mt-2 w-full rounded border border-white/20 px-2 py-1 text-left hover:bg-white/10"
            onClick={toggle}
          >
            {state === 'connected' ? 'Disconnect' : 'Connect'}
          </button>
        </div>
      )}
    </div>
  )
}
