import { describe, expect, it } from 'vitest'
import { authenticatedRequest } from '../src/authenticatedRequest.js'

describe('authenticatedRequest', () => {
  it('owns authorization while preserving Request inputs, body and cancellation', async () => {
    const abort = new AbortController()
    const source = new Request(`${window.location.origin}/api/catalog`, {
      method: 'POST', headers: { Authorization: 'Bearer attacker', 'X-Revision': '2' }, body: 'synthetic', signal: abort.signal,
    })
    const request = authenticatedRequest(source, { headers: { Authorization: 'Bearer override', 'X-Revision': '3' } }, 'phweb_owned')
    expect(request.headers.get('Authorization')).toBe('Bearer phweb_owned')
    expect(request.headers.get('X-Revision')).toBe('3')
    expect(await request.text()).toBe('synthetic')
    expect(request.redirect).toBe('error')
    expect(request.credentials).toBe('same-origin')
    abort.abort()
    expect(request.signal.aborted).toBe(true)
  })

  it('removes caller authorization for anonymous requests', () => {
    const request = authenticatedRequest('/api/catalog', { headers: { Authorization: 'Bearer override' } }, null)
    expect(request.headers.has('Authorization')).toBe(false)
  })

  it.each(['https://evil.example/collect', '//evil.example/collect', 'http://user:password@localhost:49152/api'])('rejects credential leakage to %s', url => {
    expect(() => authenticatedRequest(url, { redirect: 'follow' }, 'phweb_owned')).toThrow('Authenticated requests must stay on this application origin.')
  })
})
