import type {
  CapabilityAction,
  CapabilityActionParameter,
  CapabilityConfigEntry,
  CapabilityManifest,
  CapabilityPanel,
  CapabilityShape,
  CapabilityUi,
} from '../types/capabilities'

export interface ManifestIndex {
  byDigest: Map<string, CapabilityManifest>
  byNameAndTag: Map<string, CapabilityManifest>
}

export interface RoleAppearance {
  role: string
  label?: string
  color?: string
  shape?: CapabilityShape
  abbreviation?: string
}

export type RoleAppearanceMap = Record<string, RoleAppearance>

export function normalizeManifests(data: unknown): CapabilityManifest[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => normalizeManifest(entry))
    .filter((entry): entry is CapabilityManifest => entry !== null)
}

function normalizeManifest(entry: unknown): CapabilityManifest | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>

  if (typeof value.schemaVersion !== 'string' || typeof value.capabilitiesVersion !== 'string') {
    return null
  }
  if (!value.image || typeof value.image !== 'object') return null

  const imageValue = value.image as Record<string, unknown>
  const image = {
    name: typeof imageValue.name === 'string' ? imageValue.name : null,
    tag: typeof imageValue.tag === 'string' ? imageValue.tag : null,
    digest: typeof imageValue.digest === 'string' ? imageValue.digest : null,
  }

  const config = Array.isArray(value.config)
    ? value.config
        .map((item) => normalizeConfigEntry(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizeConfigEntry>> => item !== null)
    : []

  const actions = Array.isArray(value.actions)
    ? value.actions
        .map((item) => normalizeAction(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizeAction>> => item !== null)
    : []

  const panels = Array.isArray(value.panels)
    ? value.panels
        .map((item) => normalizePanel(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizePanel>> => item !== null)
    : []

  const role = typeof value.role === 'string' ? value.role : ''
  const ui = normalizeUi(value.ui)

  return {
    schemaVersion: value.schemaVersion,
    capabilitiesVersion: value.capabilitiesVersion,
    image,
    role,
    config,
    actions,
    panels,
    ui,
  }
}

function normalizeConfigEntry(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.name !== 'string' || typeof value.type !== 'string') return null
  const options =
    Array.isArray(value.options) && value.options.length > 0 ? [...value.options] : undefined
  return {
    name: value.name,
    type: value.type,
    default: value.default,
    min: typeof value.min === 'number' ? value.min : undefined,
    max: typeof value.max === 'number' ? value.max : undefined,
    multiline: typeof value.multiline === 'boolean' ? value.multiline : undefined,
    ui: value.ui,
    when: typeof value.when === 'object' && value.when !== null ? (value.when as Record<string, unknown>) : undefined,
    options,
  } satisfies CapabilityConfigEntry
}

function normalizeAction(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.id !== 'string' || typeof value.label !== 'string') return null
  const params = Array.isArray(value.params)
    ? value.params
        .map((item) => normalizeActionParam(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizeActionParam>> => item !== null)
    : []
  return { id: value.id, label: value.label, params } satisfies CapabilityAction
}

function normalizeActionParam(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.name !== 'string' || typeof value.type !== 'string') return null
  return {
    name: value.name,
    type: value.type,
    default: value.default,
    required: typeof value.required === 'boolean' ? value.required : undefined,
    ui: value.ui,
  } satisfies CapabilityActionParameter
}

function normalizePanel(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.id !== 'string') return null
  return { id: value.id, options: value.options } satisfies CapabilityPanel
}

function normalizeUi(entry: unknown): CapabilityUi | undefined {
  if (!entry || typeof entry !== 'object') return undefined
  const value = entry as Record<string, unknown>
  const label = typeof value.label === 'string' && value.label.trim().length > 0 ? value.label.trim() : undefined
  const abbreviation =
    typeof value.abbreviation === 'string' && value.abbreviation.trim().length > 0
      ? value.abbreviation.trim()
      : undefined
  const color = sanitizeColor(value.color)
  const shapeValue = typeof value.shape === 'string' ? value.shape.trim().toLowerCase() : undefined
  const shape = isCapabilityShape(shapeValue) ? shapeValue : undefined
  const ui: CapabilityUi = { label, abbreviation, color, shape }
  // Preserve any additional UI fields (e.g. ioType) for advanced use cases.
  for (const [key, raw] of Object.entries(value)) {
    if (key === 'label' || key === 'abbreviation' || key === 'color' || key === 'shape') {
      continue
    }
    ui[key] = raw
  }
  return ui
}

function sanitizeColor(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  if (!trimmed) return undefined
  return trimmed
}

function isCapabilityShape(value: unknown): value is CapabilityShape {
  if (typeof value !== 'string') return false
  switch (value) {
    case 'circle':
    case 'square':
    case 'triangle':
    case 'diamond':
    case 'pentagon':
    case 'hexagon':
    case 'star':
      return true
    default:
      return false
  }
}

export function buildManifestIndex(list: CapabilityManifest[]): ManifestIndex {
  const byDigest = new Map<string, CapabilityManifest>()
  const byNameAndTag = new Map<string, CapabilityManifest>()

  list.forEach((manifest) => {
    const digest = manifest.image?.digest?.trim().toLowerCase()
    if (digest) {
      byDigest.set(digest, manifest)
    }

    const name = manifest.image?.name?.trim().toLowerCase()
    const tag = manifest.image?.tag?.trim()
    if (name && tag) {
      byNameAndTag.set(`${name}:::${tag}`, manifest)
    }
  })

  return { byDigest, byNameAndTag }
}

export function findManifestForImage(image: string, index: ManifestIndex): CapabilityManifest | null {
  const reference = parseImageReference(image)
  if (!reference) return null

  if (reference.digest) {
    const manifest = index.byDigest.get(reference.digest)
    if (manifest) return manifest
  }

  if (reference.name && reference.tag) {
    const name = reference.name
    const tag = reference.tag
    // First try the full image name as-is.
    const directKey = `${name}:::${tag}`
    const direct = index.byNameAndTag.get(directKey)
    if (direct) return direct

    // If the image name includes a registry/repository prefix, also try the last path
    // segment so manifests can declare short names like "generator" while runtime
    // images use fully-qualified references such as "ghcr.io/org/generator".
    const lastSlash = name.lastIndexOf('/')
    if (lastSlash >= 0 && lastSlash < name.length - 1) {
      const simpleName = name.slice(lastSlash + 1)
      const simpleKey = `${simpleName}:::${tag}`
      const simple = index.byNameAndTag.get(simpleKey)
      if (simple) return simple
    }
  }

  return null
}

interface ImageReference {
  name: string | null
  tag: string | null
  digest: string | null
}

function parseImageReference(image: string): ImageReference | null {
  if (!image || typeof image !== 'string') return null
  const trimmed = image.trim()
  if (!trimmed) return null

  let digest: string | null = null
  let remainder = trimmed

  const digestIndex = trimmed.indexOf('@')
  if (digestIndex >= 0) {
    digest = trimmed
      .slice(digestIndex + 1)
      .trim()
      .toLowerCase()
    remainder = trimmed.slice(0, digestIndex)
  }

  remainder = remainder.trim()
  let namePart = remainder
  let tag: string | null = null

  if (remainder) {
    const lastColon = remainder.lastIndexOf(':')
    const lastSlash = remainder.lastIndexOf('/')
    if (lastColon > lastSlash) {
      tag = remainder.slice(lastColon + 1).trim() || null
      namePart = remainder.slice(0, lastColon)
    }
  }

  const name = namePart ? namePart.trim().toLowerCase() : null

  return { name, tag, digest }
}

export function formatCapabilityValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return ''
  }
}

export function inferCapabilityInputType(type: string | undefined): 'number' | 'text' {
  const normalized = type?.trim().toLowerCase() ?? ''
  if (
    normalized === 'int' ||
    normalized === 'integer' ||
    normalized === 'number' ||
    normalized === 'double' ||
    normalized === 'float'
  ) {
    return 'number'
  }
  return 'text'
}

export function matchesCapabilityWhen(
  when: Record<string, unknown> | undefined,
  resolveValue: (path: string) => unknown,
): boolean {
  if (!when || typeof when !== 'object') {
    return true
  }
  for (const [path, expected] of Object.entries(when)) {
    const actual = resolveValue(path)
    if (!matchesExpected(actual, expected)) {
      return false
    }
  }
  return true
}

function matchesExpected(actual: unknown, expected: unknown): boolean {
  if (Array.isArray(expected)) {
    return expected.some((value) => matchesExpected(actual, value))
  }
  if (actual === undefined || actual === null) {
    return false
  }
  if (typeof expected === 'string') {
    const expectedText = expected.trim()
    if (!expectedText) {
      return false
    }
    if (typeof actual === 'string') {
      return actual.trim().toLowerCase() === expectedText.toLowerCase()
    }
    return String(actual).trim().toLowerCase() === expectedText.toLowerCase()
  }
  if (typeof expected === 'boolean') {
    if (typeof actual === 'boolean') return actual === expected
    if (typeof actual === 'string') {
      const normalized = actual.trim().toLowerCase()
      if (normalized === 'true') return expected === true
      if (normalized === 'false') return expected === false
    }
    return false
  }
  if (typeof expected === 'number') {
    if (typeof actual === 'number') return actual === expected
    if (typeof actual === 'string') {
      const parsed = Number(actual)
      return Number.isFinite(parsed) && parsed === expected
    }
    return false
  }
  return Object.is(actual, expected)
}

export function capabilityEntryUiString(entry: CapabilityConfigEntry, key: string): string | undefined {
  const ui = entry.ui
  if (!ui || typeof ui !== 'object') return undefined
  const value = (ui as Record<string, unknown>)[key]
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed ? trimmed : undefined
}

export type CapabilityConfigGroup = { id: string; label: string; entries: CapabilityConfigEntry[] }

export function groupCapabilityConfigEntries(entries: CapabilityConfigEntry[]): CapabilityConfigGroup[] {
  const groups = new Map<string, CapabilityConfigGroup>()
  for (const entry of entries) {
    const group = capabilityEntryUiString(entry, 'group') ?? 'General'
    const id = group.trim() || 'General'
    const existing = groups.get(id)
    if (existing) {
      existing.entries.push(entry)
    } else {
      groups.set(id, { id, label: id, entries: [entry] })
    }
  }
  return Array.from(groups.values())
}

export function buildRoleAppearanceMap(manifests: CapabilityManifest[]): RoleAppearanceMap {
  const map: RoleAppearanceMap = {}
  manifests.forEach((manifest) => {
    const role = typeof manifest.role === 'string' ? manifest.role.trim().toLowerCase() : ''
    if (!role || map[role]) return
    const ui = manifest.ui
    const entry: RoleAppearance = {
      role,
      label: ui?.label,
      color: ui?.color,
      shape: ui?.shape,
      abbreviation: ui?.abbreviation,
    }
    map[role] = entry
  })
  return map
}
