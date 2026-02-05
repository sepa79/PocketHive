import { useEffect, useMemo, useState } from 'react'
import Editor from '@monaco-editor/react'
import {
  deleteScenarioSut,
  fetchScenarioSutRaw,
  listScenarioBundleSuts,
  saveScenarioSutRaw,
} from '../../lib/scenarioManagerApi'

interface Props {
  scenarioId: string
  onClose: () => void
  onSaved?: (sutId: string) => void
}

function defaultSutTemplate(sutId: string) {
  return `id: ${sutId}
name: ${sutId}

endpoints:
  api:
    id: api
    kind: http
    baseUrl: http://example.local
`
}

export default function ScenarioSutsModal({ scenarioId, onClose, onSaved }: Props) {
  const [sutIds, setSutIds] = useState<string[]>([])
  const [selectedSutId, setSelectedSutId] = useState<string | null>(null)
  const [raw, setRaw] = useState<string>('')
  const [loadingList, setLoadingList] = useState(true)
  const [loadingSut, setLoadingSut] = useState(false)
  const [saving, setSaving] = useState(false)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const title = useMemo(
    () => `bundle-local SUTs — ${scenarioId}`,
    [scenarioId],
  )

  const reloadList = async (keepSelected: string | null) => {
    setLoadingList(true)
    try {
      const ids = await listScenarioBundleSuts(scenarioId)
      setSutIds(ids)
      if (keepSelected && ids.includes(keepSelected)) {
        setSelectedSutId(keepSelected)
      } else if (!selectedSutId && ids.length > 0) {
        setSelectedSutId(ids[0] ?? null)
      } else if (keepSelected && !ids.includes(keepSelected)) {
        setSelectedSutId(null)
      }
    } finally {
      setLoadingList(false)
    }
  }

  useEffect(() => {
    void reloadList(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scenarioId])

  useEffect(() => {
    let cancelled = false
    const sutId = selectedSutId
    if (!sutId) {
      setRaw('')
      setMissing(false)
      setError(null)
      return
    }

    setLoadingSut(true)
    setError(null)
    setMissing(false)

    const load = async () => {
      try {
        const text = await fetchScenarioSutRaw(scenarioId, sutId)
        if (cancelled) return
        if (text == null) {
          setMissing(true)
          setRaw(defaultSutTemplate(sutId))
        } else {
          setRaw(text)
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load sut.yaml')
        }
      } finally {
        if (!cancelled) {
          setLoadingSut(false)
        }
      }
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [scenarioId, selectedSutId])

  const handleCreate = async () => {
    const input = window.prompt('New SUT id (directory name under bundle sut/):', 'sut-A')
    const sutId = (input ?? '').trim()
    if (!sutId) return
    if (sutIds.includes(sutId)) {
      setSelectedSutId(sutId)
      return
    }
    setSelectedSutId(sutId)
    setMissing(true)
    setRaw(defaultSutTemplate(sutId))
  }

  const handleSave = async () => {
    const sutId = selectedSutId
    if (!sutId || saving) return
    setSaving(true)
    setError(null)
    try {
      await saveScenarioSutRaw(scenarioId, sutId, raw)
      setMissing(false)
      await reloadList(sutId)
      onSaved?.(sutId)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save sut.yaml')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    const sutId = selectedSutId
    if (!sutId || saving) return
    const ok = window.confirm(`Delete SUT '${sutId}' from bundle? This removes bundle/sut/${sutId}/.`)
    if (!ok) return
    setSaving(true)
    setError(null)
    try {
      await deleteScenarioSut(scenarioId, sutId)
      setSelectedSutId(null)
      setRaw('')
      setMissing(false)
      await reloadList(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete SUT')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4">
      <div className="w-[1150px] max-w-[98vw] h-[85vh] max-h-[85vh] rounded-lg border border-white/10 bg-[#1a1d24] shadow-xl flex flex-col">
        <div className="flex items-center justify-between gap-3 px-4 py-3 border-b border-white/10">
          <div className="min-w-0">
            <div className="text-sm font-semibold text-white/90 truncate">{title}</div>
            <div className="text-[11px] text-white/60">
              Edit bundle-local SUT definitions under <span className="text-white/70">sut/&lt;sutId&gt;/sut.yaml</span>.
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-50"
              disabled={loadingList || saving}
              onClick={() => void handleCreate()}
            >
              New SUT
            </button>
            <button
              type="button"
              className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-50"
              disabled={!selectedSutId || loadingSut || saving}
              onClick={() => void handleSave()}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
            <button
              type="button"
              className="rounded bg-red-500/20 px-2 py-1 text-[11px] text-red-200 hover:bg-red-500/30 disabled:opacity-50"
              disabled={!selectedSutId || saving}
              onClick={() => void handleDelete()}
            >
              Delete
            </button>
            <button
              type="button"
              className="rounded bg-white/5 px-2 py-1 text-[11px] text-white/80 hover:bg-white/15"
              onClick={onClose}
            >
              Close
            </button>
          </div>
        </div>

        {error && (
          <div className="px-4 py-2 text-[11px] text-red-400 border-b border-white/10">
            {error}
          </div>
        )}

        <div className="flex-1 min-h-0 flex">
          <div className="w-[260px] border-r border-white/10 flex flex-col">
            <div className="px-3 py-2 border-b border-white/10">
              <div className="text-[11px] text-white/70">SUTs in bundle</div>
            </div>
            <div className="flex-1 overflow-y-auto p-2 space-y-1">
              {loadingList && (
                <div className="text-[11px] text-white/50 px-2 py-1">Loading…</div>
              )}
              {!loadingList && sutIds.length === 0 && (
                <div className="text-[11px] text-white/50 px-2 py-1">
                  No SUTs yet. Click “New SUT”.
                </div>
              )}
              {sutIds.map((sutId) => (
                <button
                  key={sutId}
                  type="button"
                  className={[
                    'w-full text-left rounded px-2 py-1 text-[11px]',
                    selectedSutId === sutId
                      ? 'bg-white/15 text-white'
                      : 'text-white/70 hover:bg-white/10',
                  ].join(' ')}
                  onClick={() => setSelectedSutId(sutId)}
                >
                  {sutId}
                </button>
              ))}
            </div>
          </div>

          <div className="flex-1 min-w-0 flex flex-col">
            <div className="px-3 py-2 border-b border-white/10 flex items-center justify-between">
              <div className="text-[11px] text-white/70">
                {selectedSutId ? (
                  <>
                    Editing <span className="text-white/90">{selectedSutId}</span>
                    {missing && <span className="ml-2 text-amber-300">(not saved yet)</span>}
                  </>
                ) : (
                  'Select a SUT or create a new one.'
                )}
              </div>
              {loadingSut && (
                <div className="text-[11px] text-white/50">Loading…</div>
              )}
            </div>
            <div className="flex-1 min-h-0">
              <Editor
                language="yaml"
                theme="vs-dark"
                value={raw}
                onChange={(value) => setRaw(value ?? '')}
                options={{
                  minimap: { enabled: false },
                  fontSize: 12,
                  wordWrap: 'on',
                  scrollBeyondLastLine: false,
                  renderWhitespace: 'selection',
                  automaticLayout: true,
                  readOnly: !selectedSutId,
                }}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
