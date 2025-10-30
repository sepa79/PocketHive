import { describe, it, expect, vi } from 'vitest'
import { apiFetch } from './api'
import { subscribeLogs, resetLogs, type LogEntry } from './logs'

/**
 * @vitest-environment jsdom
 */

describe('apiFetch', () => {
  it('logs request and response with same correlationId', async () => {
    resetLogs()
    const fetchMock = vi.fn().mockResolvedValue({
      clone: () => ({ text: () => Promise.resolve('ok') }),
      text: () => Promise.resolve('ok'),
    }) as unknown as typeof fetch
    global.fetch = fetchMock
    let entries: LogEntry[] = []
    const unsubscribe = subscribeLogs((l) => {
      entries = l
    })
    await apiFetch('/foo', { headers: { 'content-type': 'application/json' } })
    unsubscribe()
    expect(fetchMock).toHaveBeenCalled()
    expect(entries).toHaveLength(2)
    expect(entries[0].source).toBe('ui')
    expect(entries[1].source).toBe('hive')
    expect(entries[0].correlationId).toBeDefined()
    expect(entries[0].correlationId).toBe(entries[1].correlationId)
    const fetchInit = fetchMock.mock.calls[0][1] as RequestInit
    const headers = (fetchInit?.headers ?? {}) as Record<string, string>
    expect(headers['x-correlation-id']).toBeDefined()
    expect(headers['content-type']).toBe('application/json')
  })

  it('omits correlation header when requested but keeps correlated logs', async () => {
    resetLogs()
    const fetchMock = vi.fn().mockResolvedValue({
      clone: () => ({ text: () => Promise.resolve('ok') }),
      text: () => Promise.resolve('ok'),
    }) as unknown as typeof fetch
    global.fetch = fetchMock
    let entries: LogEntry[] = []
    const unsubscribe = subscribeLogs((l) => {
      entries = l
    })
    await apiFetch('http://example.com/foo', { omitCorrelationId: true })
    unsubscribe()
    expect(fetchMock).toHaveBeenCalled()
    const fetchInit = fetchMock.mock.calls[0][1] as RequestInit
    const headers = (fetchInit?.headers ?? {}) as Record<string, string>
    expect(headers['x-correlation-id']).toBeUndefined()
    expect(entries).toHaveLength(2)
    expect(entries[0].correlationId).toBeDefined()
    expect(entries[0].correlationId).toBe(entries[1].correlationId)
  })
})
