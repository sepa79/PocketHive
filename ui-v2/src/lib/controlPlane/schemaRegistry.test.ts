import { readFileSync } from 'node:fs'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { loadControlPlaneSchema, resetControlPlaneSchema } from './schemaRegistry'

vi.mock('../auth', () => ({ readStoredAccessToken: () => 'test-session' }))

const canonical = (name: string) => JSON.parse(readFileSync(new URL(`../../../../docs/spec/${name}`, import.meta.url), 'utf8'))
const bundle = canonical('control-events.schema.json')
bundle.$defs['swarm-lifecycle.schema.json'] = canonical('swarm-lifecycle.schema.json')
const event = {
  timestamp: '2026-09-14T12:00:00Z', version: '2', kind: 'journal', type: 'work-journal',
  origin: 'worker', scope: { swarmId: 'swarm', role: 'processor', instance: 'worker' },
  correlationId: 'correlation', idempotencyKey: 'idempotency',
  runtime: { templateId: 'template', runId: 'run' }, data: { message: 'test' },
}

beforeEach(() => resetControlPlaneSchema())
afterEach(() => vi.unstubAllGlobals())

it('compiles canonical lifecycle refs and still rejects invalid types, required fields and formats', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(bundle), { headers: { ETag: 'v1' } })))
  const state = await loadControlPlaneSchema()
  expect(state.error).toBeUndefined()
  expect(state.status).toBe('ready')
  expect(state.validator?.(event)).toBe(true)
  expect(state.validator?.({ ...event, runtime: { templateId: 'template' } })).toBe(false)
  expect(state.validator?.({ ...event, runtime: { templateId: 'template', runId: 42 } })).toBe(false)
  expect(state.validator?.({ ...event, timestamp: 'yesterday' })).toBe(false)
  expect(state.validator?.({ ...event, kind: 'invalid' })).toBe(false)
  expect(state.validator?.({ ...event, runtime: undefined })).toBe(false)
})

it('reuses an unchanged bundle and recompiles a changed dependency', async () => {
  const changed = structuredClone(bundle)
  changed.$defs['swarm-lifecycle.schema.json'].$defs.RuntimeMetadata.properties.runId = { const: 'new-run' }
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(bundle), { headers: { ETag: 'v1' } }))
    .mockResolvedValueOnce(new Response(null, { status: 304 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(changed), { headers: { ETag: 'v2' } }))
  vi.stubGlobal('fetch', fetch)
  const first = await loadControlPlaneSchema()
  expect((await loadControlPlaneSchema()).validator).toBe(first.validator)
  expect(fetch.mock.calls[1][1].headers['If-None-Match']).toBe('v1')
  const next = await loadControlPlaneSchema()
  expect(next.status).toBe('ready')
  expect(next.etag).toBe('v2')
  expect(next.validator?.(event)).toBe(false)
})

it('fails explicitly when the canonical dependency is missing', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(canonical('control-events.schema.json')))))
  const state = await loadControlPlaneSchema()
  expect(state.status).toBe('error')
  expect(state.validator).toBeUndefined()
  expect(state.error).toContain('swarm-lifecycle.schema.json')
})
