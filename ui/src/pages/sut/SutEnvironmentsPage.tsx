import { useEffect, useMemo, useState } from 'react'
import { useSearchParams, Link } from 'react-router-dom'
import { apiFetch } from '../../lib/api'

type SutEndpoint = {
  id: string
  kind?: string | null
  baseUrl?: string | null
}

type SutEnvironment = {
  id: string
  name?: string | null
  type?: string | null
  endpoints?: Record<string, SutEndpoint | undefined> | null
}

export default function SutEnvironmentsPage() {
  const [envs, setEnvs] = useState<SutEnvironment[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searchParams] = useSearchParams()
  const initialId = searchParams.get('sutId')
  const [selectedId, setSelectedId] = useState<string | null>(initialId)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const response = await apiFetch('/scenario-manager/sut-environments')
        if (!response.ok) {
          throw new Error(`HTTP ${response.status} from scenario-manager`)
        }
        let raw: unknown
        try {
          raw = (await response.json()) as unknown
        } catch {
          throw new Error('Unexpected non‑JSON response from scenario-manager (sut-environments)')
        }
        if (cancelled) return
        const normalized = normalizeSutList(raw)
        setEnvs(normalized)
        if (normalized.length > 0) {
          setSelectedId((current) => current ?? normalized[0].id)
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load environments')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [])

  const selected = useMemo(
    () => envs.find((e) => e.id === selectedId) ?? null,
    [envs, selectedId],
  )

  const endpointEntries = useMemo(() => {
    if (!selected?.endpoints) return []
    return Object.entries(selected.endpoints)
      .map(([id, value]) => ({
        id,
        kind: value?.kind ?? null,
        baseUrl: value?.baseUrl ?? null,
      }))
      .sort((a, b) => a.id.localeCompare(b.id))
  }, [selected])

  return (
    <div className="flex h-full min-h-0 bg-[#05070b] text-white">
      <div className="w-72 border-r border-white/10 px-4 py-4 space-y-3">
        <div className="flex items-center justify-between">
          <h1 className="text-sm font-semibold text-white/90">Systems under test</h1>
          <Link
            to="/hive"
            className="text-xs text-sky-300 hover:text-sky-200 transition"
          >
            Back to Hive
          </Link>
        </div>
        {loading && (
          <div className="text-xs text-white/60">Loading environments…</div>
        )}
        {error && (
          <div className="text-xs text-red-400">Failed to load: {error}</div>
        )}
        <div className="space-y-1 overflow-y-auto max-h-[calc(100vh-6rem)] pr-1">
          {envs.map((env) => {
            const isSelected = env.id === selectedId
            const endpointCount = env.endpoints
              ? Object.keys(env.endpoints).length
              : 0
            return (
              <button
                key={env.id}
                type="button"
                onClick={() => setSelectedId(env.id)}
                className={`w-full text-left rounded-md px-3 py-2 text-xs border ${
                  isSelected
                    ? 'border-sky-400 bg-sky-500/10'
                    : 'border-white/10 bg-white/5 hover:bg-white/10'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-white/90">
                    {env.name || env.id}
                  </span>
                  {env.type && (
                    <span className="px-1.5 py-0.5 rounded-full bg-white/10 text-[10px] text-white/80">
                      {env.type}
                    </span>
                  )}
                </div>
                <div className="mt-0.5 text-[10px] text-white/60">
                  {env.id}
                  {endpointCount > 0 && ` • ${endpointCount} endpoint${endpointCount === 1 ? '' : 's'}`}
                </div>
              </button>
            )
          })}
          {!loading && !error && envs.length === 0 && (
            <div className="text-xs text-white/60">No environments defined.</div>
          )}
        </div>
      </div>
      <div className="flex-1 px-6 py-4 overflow-y-auto">
        {!selected && !loading && (
          <div className="text-sm text-white/70">
            Select an environment on the left to view details.
          </div>
        )}
        {selected && (
          <div className="space-y-4">
            <div>
              <h2 className="text-base font-semibold text-white/90">
                {selected.name || selected.id}
              </h2>
              <div className="mt-1 text-xs text-white/60 space-x-2">
                <span>ID: {selected.id}</span>
                {selected.type && <span>Type: {selected.type}</span>}
              </div>
            </div>
            <div>
              <h3 className="text-xs font-semibold text-white/80 mb-1.5">
                Endpoints
              </h3>
              {endpointEntries.length === 0 ? (
                <div className="text-xs text-white/60">No endpoints defined.</div>
              ) : (
                <table className="w-full text-xs border border-white/10 rounded-md overflow-hidden">
                  <thead className="bg-white/5 text-white/70">
                    <tr>
                      <th className="px-2 py-1 text-left font-medium">ID</th>
                      <th className="px-2 py-1 text-left font-medium">Kind</th>
                      <th className="px-2 py-1 text-left font-medium">Base URL</th>
                    </tr>
                  </thead>
                  <tbody>
                    {endpointEntries.map((ep) => (
                      <tr key={ep.id} className="border-t border-white/10">
                        <td className="px-2 py-1 align-top font-mono text-[11px] text-white/90">
                          {ep.id}
                        </td>
                        <td className="px-2 py-1 align-top text-white/80">
                          {ep.kind || '—'}
                        </td>
                        <td className="px-2 py-1 align-top font-mono text-[11px] text-sky-100 break-all">
                          {ep.baseUrl || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <div>
              <h3 className="text-xs font-semibold text-white/80 mb-1.5">
                Raw environment (YAML)
              </h3>
              <pre className="bg-black/60 border border-white/10 rounded-md p-3 text-[11px] text-sky-100 overflow-x-auto whitespace-pre-wrap">
{JSON.stringify(selected, null, 2)}
              </pre>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function normalizeSutList(raw: unknown): SutEnvironment[] {
  if (!raw || typeof raw !== 'object') return []
  if (Array.isArray(raw)) {
    return raw
      .map((item) => normalizeSut(item))
      .filter((env): env is SutEnvironment => env !== null)
  }
  // Scenario Manager currently returns a flat array; other shapes are ignored.
  return []
}

function normalizeSut(raw: unknown): SutEnvironment | null {
  if (!raw || typeof raw !== 'object') return null
  const obj = raw as Record<string, unknown>
  const id = typeof obj.id === 'string' ? obj.id.trim() : ''
  if (!id) return null
  const name =
    typeof obj.name === 'string' && obj.name.trim().length > 0
      ? obj.name.trim()
      : null
  const type =
    typeof obj.type === 'string' && obj.type.trim().length > 0
      ? obj.type.trim()
      : null

  let endpoints: SutEnvironment['endpoints'] = null
  if (obj.endpoints && typeof obj.endpoints === 'object') {
    const rawEndpoints = obj.endpoints as Record<string, unknown>
    const normalized: Record<string, SutEndpoint> = {}
    for (const [key, value] of Object.entries(rawEndpoints)) {
      if (!value || typeof value !== 'object') continue
      const ep = value as Record<string, unknown>
      normalized[key] = {
        id: key,
        kind:
          typeof ep.kind === 'string' && ep.kind.trim().length > 0
            ? ep.kind.trim()
            : null,
        baseUrl:
          typeof ep.baseUrl === 'string' && ep.baseUrl.trim().length > 0
            ? ep.baseUrl.trim()
            : null,
      }
    }
    endpoints = normalized
  }

  return { id, name, type, endpoints }
}
