import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BrowserTokenStore } from '../src/BrowserTokenStore.js'

describe('BrowserTokenStore', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.restoreAllMocks())

  it('stores only a tab-scoped credential with expiry and clears it explicitly', () => {
    const store = new BrowserTokenStore('SYNTHETIC_AUTH')
    expect(store.read()).toBeNull()
    const token = { accessToken: 'phweb_synthetic', expiresAt: Date.now() + 100000 }
    store.write(token)
    expect(store.read()).toEqual(token)
    expect(Object.keys(JSON.parse(sessionStorage.getItem('SYNTHETIC_AUTH')!))).toEqual(['accessToken', 'expiresAt'])
    expect(localStorage.getItem('SYNTHETIC_AUTH')).toBeNull()
    store.write(null)
    expect(store.read()).toBeNull()
  })

  it.each(['invalid JSON', '{"accessToken":"phweb_secret"}', '{"accessToken":"phweb_bad\\r\\nInjected: yes","expiresAt":9999999999999}', 'null'])('rejects and removes malformed stored credentials', value => {
    sessionStorage.setItem('SYNTHETIC_AUTH', value)
    expect(() => new BrowserTokenStore('SYNTHETIC_AUTH').read()).toThrow('Stored sign-in is invalid.')
    expect(sessionStorage.getItem('SYNTHETIC_AUTH')).toBeNull()
  })

  it('fails explicitly when session storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(() => new BrowserTokenStore('SYNTHETIC_AUTH').read()).toThrow('Session storage is unavailable.')
  })
})
