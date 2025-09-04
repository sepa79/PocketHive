import { useEffect, useRef, useState } from 'react'
import { Plug, Square } from 'lucide-react'
import { Client } from '@stomp/stompjs'
import { setClient as setStompClient } from '../lib/stompClient'
import { logOther } from '../lib/logs'
import { getConfig, setConfig } from '../lib/config'

export default function Connectivity() {
  const [state, setState] = useState<'connected' | 'connecting' | 'disconnected'>('disconnected')
  const [menuOpen, setMenuOpen] = useState(false)
  const cfg = getConfig()
  const [url, setUrl] = useState(cfg.stompUrl)
  const [login, setLogin] = useState(cfg.stompUser)
  const [passcode, setPasscode] = useState(cfg.stompPasscode)
  const [subscription, setSubscription] = useState(cfg.stompSubscription)
  const clientRef = useRef<Client | null>(null)

  function connect() {
    setState('connecting')
    const client = new Client({
      brokerURL: url,
      connectHeaders: { login, passcode },
      reconnectDelay: 0,
      onConnect: () => {
        setState('connected')
        setStompClient(client, subscription)
        logOther('STOMP connected')
      },
      onWebSocketClose: (evt) => {
        setState('disconnected')
        setStompClient(null)
        logOther(
          `STOMP disconnected (code ${'code' in evt ? evt.code : 'n/a'}, reason ${'reason' in evt ? evt.reason : 'n/a'})`,
        )
      },
      onWebSocketError: (evt) => {
        const err = evt as unknown as ErrorEvent
        logOther(`STOMP socket error: ${err.message || err.type}`)
      },
      onStompError: (frame) => {
        setState('disconnected')
        setStompClient(null)
        const msg = frame.headers.message
        const body = frame.body
        logOther(`STOMP error: ${msg}${body ? ` - ${body}` : ''}`)
      },
    })
    try {
      client.activate()
      clientRef.current = client
    } catch (error) {
      setState('disconnected')
      logOther(`STOMP activation failed: ${error instanceof Error ? error.message : 'Unknown error'}`)
    }
  }

  function disconnect() {
    clientRef.current?.deactivate()
    clientRef.current = null
    setState('disconnected')
    setStompClient(null)
    logOther('STOMP disconnected')
  }

  useEffect(() => {
    connect()
    return () => disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (state === 'connected' && clientRef.current) {
      setStompClient(clientRef.current, subscription)
    }
  }, [state, subscription])

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
        <div className="absolute right-0 mt-1 w-56 rounded border border-white/20 bg-[#080a0e] p-2 text-xs shadow-lg">
          <div className="mb-1 font-semibold">Connection</div>
          <div className="mb-1">Status: {state}</div>
          <label className="mb-1 block">
            <span className="mb-0.5 block">User</span>
            <input
              className="w-full rounded border border-white/20 bg-transparent px-1 py-0.5"
              value={login}
              onChange={(e) => {
                setLogin(e.target.value)
                setConfig({ stompUser: e.target.value })
              }}
            />
          </label>
          <label className="mb-1 block">
            <span className="mb-0.5 block">Password</span>
            <input
              type="password"
              className="w-full rounded border border-white/20 bg-transparent px-1 py-0.5"
              value={passcode}
              onChange={(e) => {
                setPasscode(e.target.value)
                setConfig({ stompPasscode: e.target.value })
              }}
            />
          </label>
          <label className="mb-1 block">
            <span className="mb-0.5 block">URL</span>
            <input
              className="w-full rounded border border-white/20 bg-transparent px-1 py-0.5"
              value={url}
              onChange={(e) => {
                setUrl(e.target.value)
                setConfig({ stompUrl: e.target.value })
              }}
            />
          </label>
          <label className="mb-1 block">
            <span className="mb-0.5 block">Subscription</span>
            <input
              className="w-full rounded border border-white/20 bg-transparent px-1 py-0.5"
              value={subscription}
              onChange={(e) => {
                setSubscription(e.target.value)
                setConfig({ stompSubscription: e.target.value })
              }}
            />
          </label>
          <button
            className="mt-1 w-full rounded border border-white/20 px-2 py-1 text-left hover:bg-white/10"
            onClick={toggle}
          >
            {state === 'connected' ? 'Disconnect' : 'Connect'}
          </button>
        </div>
      )}
    </div>
  )
}
