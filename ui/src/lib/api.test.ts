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
    await apiFetch('/foo')
    unsubscribe()
    expect(fetchMock).toHaveBeenCalled()
    expect(entries).toHaveLength(2)
    expect(entries[0].source).toBe('ui')
    expect(entries[1].source).toBe('hive')
    expect(entries[0].correlationId).toBeDefined()
    expect(entries[0].correlationId).toBe(entries[1].correlationId)
  })
})
