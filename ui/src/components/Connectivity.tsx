import { useCallback, useEffect, useRef, useState } from 'react'
import { Plug, PlugZap } from 'lucide-react'
import { Client } from '@stomp/stompjs'

export default function Connectivity() {
  const [connected, setConnected] = useState(false)
  const clientRef = useRef<Client | null>(null)
  const host = typeof window !== 'undefined' ? window.location.hostname : 'localhost'
  const url = `ws://${host}:15674/ws`

  const connect = useCallback(() => {
    const client = new Client({
      brokerURL: url,
      reconnectDelay: 0,
      onConnect: () => setConnected(true),
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    })
    client.activate()
    clientRef.current = client
  }, [url])

  const disconnect = () => {
    clientRef.current?.deactivate()
    clientRef.current = null
    setConnected(false)
  }

  useEffect(() => {
    connect()
    return () => disconnect()
  }, [connect])

  const toggle = () => {
    if (connected) {
      disconnect()
    } else {
      connect()
    }
  }

  const Icon = connected ? PlugZap : Plug

  return (
    <button
      onClick={toggle}
      className="inline-flex items-center justify-center h-6 w-6" 
      title={connected ? 'Disconnect' : 'Reconnect'}
      aria-label={connected ? 'Disconnect' : 'Reconnect'}
    >
      <Icon className={connected ? 'text-green-400' : 'text-red-400'} size={18} />
    </button>
  )
}
