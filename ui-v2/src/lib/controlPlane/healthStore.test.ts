import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  load: vi.fn(), start: vi.fn(), stop: vi.fn(), settings: { enabled: true, url: 'wss://host/stomp', user: 'u', passcode: 'p' },
  settingsListener: undefined as undefined | ((value: { enabled: boolean; url: string; user: string; passcode: string }) => void),
}))
vi.mock('./connectionInfo', () => ({ loadControlPlaneConnectionInfo: mocks.load }))
vi.mock('./schemaRegistry', () => ({ subscribeSchemaState: (fn: (state: { status: string }) => void) => fn({ status: 'ready' }) }))
vi.mock('./settingsStore', () => ({
  getControlPlaneSettings: () => mocks.settings,
  subscribeControlPlaneSettings: (fn: typeof mocks.settingsListener) => { mocks.settingsListener = fn },
}))
vi.mock('./stompGateway', () => ({
  startStompGateway: mocks.start, stopStompGateway: mocks.stop,
  subscribeStompMessages: vi.fn(), subscribeStompMetrics: vi.fn(), subscribeStompState: vi.fn(),
}))
vi.mock('./stateStore', () => ({ applyStatusEnvelope: vi.fn(), hasStatusSnapshot: vi.fn(), requestEviction: vi.fn() }))
vi.mock('./restGateway', () => ({ requestControlPlaneRefresh: vi.fn() }))

beforeEach(() => {
  vi.resetModules(); vi.clearAllMocks(); vi.useFakeTimers(); vi.stubGlobal('window', globalThis)
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
it('keeps STOMP stopped and exposes the information-loading failure', async () => {
  mocks.load.mockRejectedValue(new Error('HTTP 503'))
  const health = await import('./healthStore')
  const observed = vi.fn()
  health.subscribeControlPlaneHealth(observed)
  health.startControlPlaneHealth()
  await Promise.resolve()
  expect(mocks.start).not.toHaveBeenCalled()
  expect(observed).toHaveBeenLastCalledWith(expect.objectContaining({ connectionInfoError: 'HTTP 503' }))
})
it('ignores a pending response after disabling and uses a fresh projection on re-enable', async () => {
  let resolve!: (value: { subscriptionDestination: string; destinationPrefix: string }) => void
  mocks.load.mockReturnValueOnce(new Promise((done) => { resolve = done }))
  const health = await import('./healthStore')
  health.startControlPlaneHealth()
  mocks.settingsListener?.({ ...mocks.settings, enabled: false })
  resolve({ subscriptionDestination: '/exchange/old/#', destinationPrefix: '/exchange/old/' })
  await Promise.resolve()
  expect(mocks.start).not.toHaveBeenCalled()
  mocks.load.mockResolvedValueOnce({ subscriptionDestination: '/exchange/current/#', destinationPrefix: '/exchange/current/' })
  mocks.settingsListener?.(mocks.settings)
  await Promise.resolve()
  expect(mocks.start).toHaveBeenCalledExactlyOnceWith({
    url: mocks.settings.url, topics: ['/exchange/current/#'], destinationPrefix: '/exchange/current/',
    connectHeaders: { login: 'u', passcode: 'p' },
  })
})
