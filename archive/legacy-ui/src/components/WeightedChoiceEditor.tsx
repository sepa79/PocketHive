import { useEffect, useMemo, useRef, useState } from 'react'
import type * as React from 'react'

type WeightedTemplateKind = 'pickWeighted' | 'pickWeightedSeeded'
type WeightedTemplateWrapper = 'mustache' | 'bare'

export type WeightedTemplateModel = {
  kind: WeightedTemplateKind
  wrapper: WeightedTemplateWrapper
  label?: string
  seed?: string
  options: Array<{
    value: string
    weight: number
  }>
}

const MUSTACHE_RE = /^\s*\{\{\s*([\s\S]*?)\s*\}\}\s*$/

function unescapeStringLiteral(text: string): string {
  let out = ''
  let escaped = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (escaped) {
      out += ch
      escaped = false
      continue
    }
    if (ch === '\\') {
      escaped = true
      continue
    }
    out += ch
  }
  return out
}

function parseStringLiteral(token: string): string | null {
  const trimmed = token.trim()
  if (trimmed.length < 2) return null
  const quote = trimmed[0]
  if (quote !== "'" && quote !== '"') return null
  if (trimmed[trimmed.length - 1] !== quote) return null
  const inner = trimmed.slice(1, -1)
  return unescapeStringLiteral(inner)
}

function quoteSingle(text: string): string {
  const escaped = text.replace(/\\/g, '\\\\').replace(/'/g, "\\'")
  return `'${escaped}'`
}

function splitArgs(args: string): string[] {
  const parts: string[] = []
  let current = ''
  let quote: "'" | '"' | null = null
  let escaped = false
  for (let i = 0; i < args.length; i++) {
    const ch = args[i]
    if (escaped) {
      current += ch
      escaped = false
      continue
    }
    if (ch === '\\') {
      current += ch
      escaped = true
      continue
    }
    if (quote) {
      current += ch
      if (ch === quote) {
        quote = null
      }
      continue
    }
    if (ch === "'" || ch === '"') {
      current += ch
      quote = ch
      continue
    }
    if (ch === ',') {
      parts.push(current.trim())
      current = ''
      continue
    }
    current += ch
  }
  if (current.trim() !== '') {
    parts.push(current.trim())
  }
  return parts
}

function findCall(input: string, kind: WeightedTemplateKind): { args: string } | null {
  const startMatch = input.match(new RegExp(`^\\s*${kind}\\s*\\(`))
  if (!startMatch) return null
  const openIndex = startMatch[0].lastIndexOf('(')
  let quote: "'" | '"' | null = null
  let escaped = false
  let depth = 1
  for (let i = openIndex + 1; i < input.length; i++) {
    const ch = input[i]
    if (escaped) {
      escaped = false
      continue
    }
    if (ch === '\\') {
      escaped = true
      continue
    }
    if (quote) {
      if (ch === quote) quote = null
      continue
    }
    if (ch === "'" || ch === '"') {
      quote = ch
      continue
    }
    if (ch === '(') {
      depth += 1
      continue
    }
    if (ch === ')') {
      depth -= 1
      if (depth === 0) {
        const tail = input.slice(i + 1).trim()
        if (tail !== '') return null
        return { args: input.slice(openIndex + 1, i) }
      }
    }
  }
  return null
}

export function parseWeightedTemplate(value: string): WeightedTemplateModel | null {
  const trimmed = value.trim()
  const mustacheMatch = trimmed.match(MUSTACHE_RE)
  const wrapper: WeightedTemplateWrapper = mustacheMatch ? 'mustache' : 'bare'
  const inner = (mustacheMatch ? mustacheMatch[1] : trimmed).trim()

  const seededCall = findCall(inner, 'pickWeightedSeeded')
  if (seededCall) {
    const tokens = splitArgs(seededCall.args)
    if (tokens.length < 4 || (tokens.length - 2) % 2 !== 0) return null
    const label = parseStringLiteral(tokens[0])
    const seed = parseStringLiteral(tokens[1])
    if (label === null || seed === null) return null
    const options: WeightedTemplateModel['options'] = []
    for (let i = 2; i < tokens.length; i += 2) {
      const v = parseStringLiteral(tokens[i])
      if (v === null) return null
      const weightToken = tokens[i + 1]
      if (!/^-?\d+$/.test(weightToken.trim())) return null
      const weight = Number.parseInt(weightToken.trim(), 10)
      if (!Number.isFinite(weight) || weight < 0) return null
      options.push({ value: v, weight })
    }
    return { kind: 'pickWeightedSeeded', wrapper, label, seed, options }
  }

  const call = findCall(inner, 'pickWeighted')
  if (!call) return null
  const tokens = splitArgs(call.args)
  if (tokens.length < 2 || tokens.length % 2 !== 0) return null
  const options: WeightedTemplateModel['options'] = []
  for (let i = 0; i < tokens.length; i += 2) {
    const v = parseStringLiteral(tokens[i])
    if (v === null) return null
    const weightToken = tokens[i + 1]
    if (!/^-?\d+$/.test(weightToken.trim())) return null
    const weight = Number.parseInt(weightToken.trim(), 10)
    if (!Number.isFinite(weight) || weight < 0) return null
    options.push({ value: v, weight })
  }
  return { kind: 'pickWeighted', wrapper, options }
}

export function stringifyWeightedTemplate(model: WeightedTemplateModel): string {
  const args: string[] = []
  if (model.kind === 'pickWeightedSeeded') {
    args.push(quoteSingle(model.label ?? ''))
    args.push(quoteSingle(model.seed ?? ''))
  }
  for (const opt of model.options) {
    args.push(quoteSingle(opt.value))
    args.push(String(Math.max(0, Math.trunc(opt.weight))))
  }
  const call = `${model.kind}(${args.join(', ')})`
  return model.wrapper === 'mustache' ? `{{ ${call} }}` : call
}

type DraftOption = { value: string; weight: number; locked: boolean }

function clampInt(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  return Math.min(max, Math.max(min, Math.trunc(value)))
}

function distributeToTotal(values: number[], total: number): number[] {
  const count = values.length
  if (count === 0) return []
  if (total <= 0) return values.map(() => 0)

  const sum = values.reduce((acc, v) => acc + Math.max(0, Math.trunc(v)), 0)
  if (sum <= 0) {
    const base = Math.floor(total / count)
    let remainder = total - base * count
    return values.map(() => {
      const next = base + (remainder > 0 ? 1 : 0)
      remainder = Math.max(0, remainder - 1)
      return next
    })
  }

  const floors: number[] = new Array(count).fill(0)
  const remainders: Array<{ index: number; frac: number }> = []
  let floorSum = 0
  for (let i = 0; i < count; i++) {
    const raw = Math.max(0, Math.trunc(values[i]))
    const scaled = (raw / sum) * total
    const floor = Math.floor(scaled)
    floors[i] = floor
    floorSum += floor
    remainders.push({ index: i, frac: scaled - floor })
  }
  let remaining = total - floorSum
  remainders.sort((a, b) => b.frac - a.frac)
  for (let i = 0; i < remainders.length && remaining > 0; i++) {
    floors[remainders[i].index] += 1
    remaining -= 1
  }
  return floors
}

function recomputeAfterTotalChange(options: DraftOption[], total: number): DraftOption[] {
  const locked = options.filter((o) => o.locked)
  const unlocked = options.filter((o) => !o.locked)
  const lockedSum = locked.reduce((acc, o) => acc + o.weight, 0)
  const usableTotal = Math.max(0, total - lockedSum)
  const scaledUnlocked = distributeToTotal(unlocked.map((o) => o.weight), usableTotal)

  let unlockedIndex = 0
  return options.map((opt) => {
    if (opt.locked) return { ...opt, weight: clampInt(opt.weight, 0, total) }
    const nextWeight = scaledUnlocked[unlockedIndex] ?? 0
    unlockedIndex += 1
    return { ...opt, weight: nextWeight }
  })
}

function applyLockedRedistribution(
  options: DraftOption[],
  changedIndex: number,
  nextWeight: number,
  total: number,
): DraftOption[] {
  const clampedTotal = Math.max(0, Math.trunc(total))
  const lockedSum = options.reduce((acc, opt, idx) => {
    if (idx === changedIndex) return acc
    return opt.locked ? acc + opt.weight : acc
  }, 0)
  const maxForChanged = Math.max(0, clampedTotal - lockedSum)
  const adjustedChanged = clampInt(nextWeight, 0, maxForChanged)

  const unlockedIndices = options
    .map((opt, idx) => ({ opt, idx }))
    .filter(({ opt, idx }) => idx !== changedIndex && !opt.locked)
    .map(({ idx }) => idx)

  const availableForOthers = Math.max(0, clampedTotal - lockedSum - adjustedChanged)
  const redistributed = distributeToTotal(
    unlockedIndices.map((idx) => options[idx].weight),
    availableForOthers,
  )

  const next = options.map((opt, idx) => {
    if (idx === changedIndex) return { ...opt, weight: adjustedChanged }
    return { ...opt }
  })
  for (let i = 0; i < unlockedIndices.length; i++) {
    const idx = unlockedIndices[i]
    next[idx] = { ...next[idx], weight: redistributed[i] ?? 0 }
  }
  return next
}

export function WeightedChoiceEditor({
  value,
  model,
  onChange,
  disabled,
}: {
  value: string
  model: WeightedTemplateModel
  onChange: (next: string) => void
  disabled?: boolean
}): React.ReactElement {
  const initialDraft = useMemo(() => {
    return model.options.map((opt) => ({ ...opt, locked: false })) satisfies DraftOption[]
  }, [model.options])
  const [mode, setMode] = useState<'editor' | 'raw'>('editor')
  const [label, setLabel] = useState(model.label ?? '')
  const [seed, setSeed] = useState(model.seed ?? '')
  const [total, setTotal] = useState(() => {
    const sum = model.options.reduce((acc, opt) => acc + opt.weight, 0)
    return sum > 0 ? sum : 100
  })
  const [options, setOptions] = useState<DraftOption[]>(initialDraft)
  const lastSeenValueRef = useRef<string>(value)

  useEffect(() => {
    if (value === lastSeenValueRef.current) return
    lastSeenValueRef.current = value
    setMode('editor')
    setLabel(model.label ?? '')
    setSeed(model.seed ?? '')
    setOptions(model.options.map((opt) => ({ ...opt, locked: false })))
    const sum = model.options.reduce((acc, opt) => acc + opt.weight, 0)
    setTotal(sum > 0 ? sum : 100)
  }, [model.label, model.options, model.seed, value])

  const currentSum = options.reduce((acc, opt) => acc + opt.weight, 0)
  const normalizedTotal = Math.max(0, Math.trunc(total))
  const lockedSum = options.reduce((acc, opt) => (opt.locked ? acc + opt.weight : acc), 0)
  const sumWarning = currentSum !== normalizedTotal
  const zeroWarning = currentSum <= 0

  const pushDraft = (
    nextOptions: DraftOption[],
    nextTotal = normalizedTotal,
    overrides?: { label?: string; seed?: string },
  ) => {
    const nextLabel = overrides?.label ?? label
    const nextSeed = overrides?.seed ?? seed
    const nextModel: WeightedTemplateModel = {
      ...model,
      label: model.kind === 'pickWeightedSeeded' ? nextLabel : undefined,
      seed: model.kind === 'pickWeightedSeeded' ? nextSeed : undefined,
      options: nextOptions.map((opt) => ({ value: opt.value, weight: opt.weight })),
    }
    lastSeenValueRef.current = stringifyWeightedTemplate(nextModel)
    onChange(lastSeenValueRef.current)
    setOptions(nextOptions)
    setTotal(nextTotal)
  }

  const updateTotal = (next: number) => {
    const nextTotal = Math.max(1, Math.trunc(next))
    const minTotal = Math.max(nextTotal, lockedSum + 1)
    const reweighted = recomputeAfterTotalChange(options, minTotal)
    pushDraft(reweighted, minTotal)
  }

  const updateOptionWeight = (index: number, nextWeight: number) => {
    const nextOptions = applyLockedRedistribution(options, index, nextWeight, normalizedTotal)
    pushDraft(nextOptions)
  }

  const updateOptionValue = (index: number, nextValue: string) => {
    const nextOptions = options.map((opt, idx) => (idx === index ? { ...opt, value: nextValue } : opt))
    pushDraft(nextOptions)
  }

  const toggleLock = (index: number) => {
    const nextOptions = options.map((opt, idx) => (idx === index ? { ...opt, locked: !opt.locked } : opt))
    pushDraft(nextOptions)
  }

  const addOption = () => {
    const base = 'OptionName'
    let candidate = base
    let suffix = 2
    while (options.some((opt) => opt.value === candidate)) {
      candidate = `${base}${suffix}`
      suffix += 1
    }
    const nextOptions = [...options, { value: candidate, weight: 0, locked: false }]
    pushDraft(nextOptions)
  }

  const removeOption = (index: number) => {
    if (options.length <= 2) return
    const nextOptions = options.filter((_, idx) => idx !== index)
    const reweighted = recomputeAfterTotalChange(nextOptions, normalizedTotal)
    pushDraft(reweighted)
  }

  const quickScale = (target: number) => {
    updateTotal(target)
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div className="text-[11px] text-white/60">
          {model.kind === 'pickWeightedSeeded' ? 'Weighted (seeded)' : 'Weighted'}
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            className={
              mode === 'editor'
                ? 'rounded border border-white/20 bg-white/10 px-2 py-0.5 text-[10px] text-white'
                : 'rounded border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/70 hover:bg-white/10'
            }
            onClick={() => setMode('editor')}
            disabled={disabled}
          >
            Editor
          </button>
          <button
            type="button"
            className={
              mode === 'raw'
                ? 'rounded border border-white/20 bg-white/10 px-2 py-0.5 text-[10px] text-white'
                : 'rounded border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/70 hover:bg-white/10'
            }
            onClick={() => setMode('raw')}
            disabled={disabled}
          >
            Raw
          </button>
        </div>
      </div>

      {mode === 'raw' ? (
        <textarea
          className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs font-mono"
          rows={3}
          value={value}
          onChange={(event) => {
            const next = event.target.value
            lastSeenValueRef.current = next
            onChange(next)
          }}
          disabled={disabled}
        />
      ) : (
        <div className="space-y-2">
          {model.kind === 'pickWeightedSeeded' && (
            <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
              <div>
                <div className="text-[11px] text-white/50 mb-1">Label</div>
                <input
                  className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs"
                  value={label}
                  onChange={(event) => {
                    const next = event.target.value
                    setLabel(next)
                    pushDraft(options, normalizedTotal, { label: next })
                  }}
                  disabled={disabled}
                />
              </div>
              <div>
                <div className="text-[11px] text-white/50 mb-1">Seed</div>
                <input
                  className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs"
                  value={seed}
                  onChange={(event) => {
                    const next = event.target.value
                    setSeed(next)
                    pushDraft(options, normalizedTotal, { seed: next })
                  }}
                  disabled={disabled}
                />
              </div>
            </div>
          )}

          <div className="flex flex-wrap items-end justify-between gap-2">
            <div className="flex items-end gap-2">
              <div>
                <div className="text-[11px] text-white/50 mb-1">Total</div>
                <input
                  type="number"
                  min={Math.max(1, lockedSum + 1)}
                  className="w-28 rounded bg-white/10 px-2 py-1 text-white text-xs"
                  value={normalizedTotal}
                  onChange={(event) => updateTotal(Number(event.target.value))}
                  disabled={disabled}
                />
              </div>
              <div className="flex items-center gap-1 pb-1">
                <button
                  type="button"
                  className="rounded border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/70 hover:bg-white/10"
                  onClick={() => quickScale(100)}
                  disabled={disabled}
                >
                  Scale 100
                </button>
                <button
                  type="button"
                  className="rounded border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/70 hover:bg-white/10"
                  onClick={() => quickScale(1000)}
                  disabled={disabled}
                >
                  Scale 1000
                </button>
              </div>
            </div>
            <div className="flex items-center gap-2 pb-1">
              <button
                type="button"
                className="rounded border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/70 hover:bg-white/10"
                onClick={addOption}
                disabled={disabled}
              >
                Add option
              </button>
            </div>
          </div>

          {(sumWarning || zeroWarning) && (
            <div className="text-[10px] text-amber-300/90">
              {zeroWarning ? 'Sum of weights is 0 (template will fail).' : `Sum (${currentSum}) differs from total (${normalizedTotal}).`}
            </div>
          )}

          <div className="space-y-2">
            {options.map((opt, idx) => {
              const percent = normalizedTotal > 0 ? Math.round((opt.weight / normalizedTotal) * 1000) / 10 : 0
              const maxWeight = Math.max(0, normalizedTotal - (lockedSum - (opt.locked ? opt.weight : 0)))
              return (
                <div key={idx} className="rounded border border-white/10 bg-black/20 p-2 space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex-1 min-w-0">
                      <input
                        className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs font-mono"
                        value={opt.value}
                        onChange={(event) => updateOptionValue(idx, event.target.value)}
                        placeholder="value"
                        disabled={disabled}
                      />
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <label className="inline-flex items-center" title="Lock">
                        <input
                          type="checkbox"
                          className="h-3.5 w-3.5 accent-blue-500"
                          checked={opt.locked}
                          onChange={() => toggleLock(idx)}
                          disabled={disabled}
                        />
                      </label>
                      <div className="text-[10px] text-white/60 w-12 text-right tabular-nums">{percent}%</div>
                      <button
                        type="button"
                        title="Remove"
                        className={
                          options.length > 2
                            ? 'rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-[10px] text-white/70 hover:bg-white/10'
                            : 'rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-[10px] text-white/40 opacity-40 cursor-not-allowed'
                        }
                        onClick={() => removeOption(idx)}
                        disabled={disabled || options.length <= 2}
                      >
                        ×
                      </button>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <input
                      type="range"
                      min={0}
                      max={maxWeight}
                      step={1}
                      className="flex-1"
                      value={opt.weight}
                      onChange={(event) => updateOptionWeight(idx, Number(event.target.value))}
                      disabled={disabled}
                    />
                    <input
                      type="number"
                      min={0}
                      max={maxWeight}
                      className="w-20 rounded bg-white/10 px-2 py-1 text-white text-xs tabular-nums"
                      value={opt.weight}
                      onChange={(event) => updateOptionWeight(idx, Number(event.target.value))}
                      disabled={disabled}
                    />
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
