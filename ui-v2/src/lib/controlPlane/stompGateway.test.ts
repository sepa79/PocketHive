import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./wireLogStore', () => ({ recordWireLog: vi.fn(() => ({ errors: [] })) }))
import { recordWireLog } from './wireLogStore'
import { startStompGateway, stopStompGateway } from './stompGateway'

class Socket {
  static OPEN = 1
  static instances: Socket[] = []
  readyState = 1
  send = vi.fn()
  close = vi.fn()
  handlers = new Map<string, (event: { data: string }) => void>()
  constructor(public url: string) { Socket.instances.push(this) }
  addEventListener(name: string, handler: (event: { data: string }) => void) { this.handlers.set(name, handler) }
  emit(name: string, data = '') { this.handlers.get(name)?.({ data }) }
}

const options = {
  url: 'wss://example.test/stomp/websocket',
  topics: ['/exchange/tenant.events/#'],
  destinationPrefix: '/exchange/tenant.events/',
}

describe('Control STOMP addresses from server', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('window', globalThis)
    vi.stubGlobal('WebSocket', Socket)
    Socket.instances = []
    vi.clearAllMocks()
  })
  afterEach(() => { stopStompGateway(); vi.useRealTimers(); vi.unstubAllGlobals() })

  it('subscribes verbatim and normalizes received destinations once before wire-log decoding', () => {
    startStompGateway(options)
    const socket = Socket.instances[0]
    socket.emit('open')
    socket.emit('message', 'CONNECTED\n\n\0')
    expect(socket.send).toHaveBeenCalledWith(expect.stringContaining('destination:/exchange/tenant.events/#'))
    socket.emit('message', 'MESSAGE\ndestination:/exchange/tenant.events/event.metric.status-full.s.r.i\n\n{}\0')
    expect(recordWireLog).toHaveBeenCalledExactlyOnceWith('stomp', 'event.metric.status-full.s.r.i', '{}')
    socket.emit('message', 'MESSAGE\ndestination:/exchange/other/event.metric.status-full.s.r.i\n\n{}\0')
    expect(recordWireLog).toHaveBeenCalledTimes(1)
  })

  it('cannot reconnect with old projection after stop and restart', () => {
    startStompGateway(options)
    const old = Socket.instances[0]
    old.emit('close')
    stopStompGateway()
    startStompGateway({ ...options, topics: ['/exchange/new.events/#'], destinationPrefix: '/exchange/new.events/' })
    old.emit('message', 'MESSAGE\ndestination:/exchange/tenant.events/event.metric.status-full.s.r.i\n\n{}\0')
    vi.advanceTimersByTime(40_000)
    expect(Socket.instances).toHaveLength(2)
    expect(recordWireLog).not.toHaveBeenCalled()
    Socket.instances[1].emit('message', 'CONNECTED\n\n\0')
    expect(Socket.instances[1].send).toHaveBeenCalledWith(expect.stringContaining('destination:/exchange/new.events/#'))
  })
})
