import { useEffect, useMemo, useState } from 'react'
import Editor from '@monaco-editor/react'
import { fetchScenarioVariables, saveScenarioVariables } from '../../lib/scenarioManagerApi'

interface Props {
  scenarioId: string
  onClose: () => void
  onSaved?: (warnings: string[]) => void
}

const DEFAULT_TEMPLATE = `version: 1
definitions:
  - name: exampleGlobal
    scope: global
    type: string
    required: false
  - name: exampleSut
    scope: sut
    type: string
    required: false

profiles:
  - id: default
    name: Default

values:
  global:
    default:
      exampleGlobal: "hello"
  sut:
    default:
      sut-A:
        exampleSut: "value-for-sut-A"
`

export default function ScenarioVariablesModal({ scenarioId, onClose, onSaved }: Props) {
  const [raw, setRaw] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])

  const title = useMemo(() => `variables.yaml — ${scenarioId}`, [scenarioId])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    setWarnings([])
    setMissing(false)

    const load = async () => {
      try {
        const text = await fetchScenarioVariables(scenarioId)
        if (cancelled) return
        if (text == null) {
          setMissing(true)
          setRaw(DEFAULT_TEMPLATE)
        } else {
          setRaw(text)
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load variables.yaml')
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
  }, [scenarioId])

  const handleSave = async () => {
    if (saving) return
    setSaving(true)
    setError(null)
    try {
      const result = await saveScenarioVariables(scenarioId, raw)
      setWarnings(result.warnings)
      setMissing(false)
      onSaved?.(result.warnings)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save variables.yaml')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4">
      <div className="w-[1050px] max-w-[98vw] h-[85vh] max-h-[85vh] rounded-lg border border-white/10 bg-[#1a1d24] shadow-xl flex flex-col">
        <div className="flex items-center justify-between gap-3 px-4 py-3 border-b border-white/10">
          <div className="min-w-0">
            <div className="text-sm font-semibold text-white/90 truncate">{title}</div>
            <div className="text-[11px] text-white/60">
              {missing ? 'File does not exist yet; saving will create it in the bundle.' : 'Edit and save scenario variables.'}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-50"
              disabled={loading || saving}
              onClick={handleSave}
            >
              {saving ? 'Saving…' : 'Save'}
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

        {error && <div className="px-4 py-2 text-[11px] text-red-400 border-b border-white/10">{error}</div>}
        {!error && warnings.length > 0 && (
          <div className="px-4 py-2 text-[11px] text-amber-300 border-b border-white/10">
            <div className="font-semibold text-amber-200">Warnings</div>
            <ul className="list-disc pl-4">
              {warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </div>
        )}

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
            }}
          />
        </div>
      </div>
    </div>
  )
}

