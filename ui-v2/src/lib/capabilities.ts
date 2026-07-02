export type CapabilityImage = {
  name?: string | null
  tag?: string | null
  digest?: string | null
}

export type CapabilityConfigEntry = {
  name: string
  type: string
  default?: unknown
  required?: boolean
  allowBlank?: boolean
  min?: number
  max?: number
  options?: unknown[]
  multiline?: boolean
  ui?: unknown
  when?: Record<string, unknown>
}

export type CapabilityManifest = {
  schemaVersion: string
  capabilitiesVersion: string
  image: CapabilityImage
  role: string
  config: CapabilityConfigEntry[]
  actions: unknown[]
  panels: unknown[]
  ui?: Record<string, unknown>
}

type ManifestIndex = {
  byDigest: Map<string, CapabilityManifest>
  byImageName: Map<string, CapabilityManifest>
}

export type ManifestResolution = {
  manifest: CapabilityManifest | null
  kind: 'exact' | 'none'
  requestedTag: string | null
  resolvedTag: string | null
}

const IO_SELECTOR_PATHS = {
  INPUT: 'inputs.type',
  OUTPUT: 'outputs.type',
} as const

type IoScope = keyof typeof IO_SELECTOR_PATHS

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
  const image: CapabilityImage = {
    name: typeof imageValue.name === 'string' ? imageValue.name : null,
    tag: typeof imageValue.tag === 'string' ? imageValue.tag : null,
    digest: typeof imageValue.digest === 'string' ? imageValue.digest : null,
  }

  const role = typeof value.role === 'string' ? value.role : ''
  const config = Array.isArray(value.config)
    ? value.config
        .map((item) => normalizeConfigEntry(item))
        .filter((item): item is CapabilityConfigEntry => item !== null)
    : []
  const actions = Array.isArray(value.actions) ? [...value.actions] : []
  const panels = Array.isArray(value.panels) ? [...value.panels] : []
  const ui = value.ui && typeof value.ui === 'object' ? (value.ui as Record<string, unknown>) : undefined

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

function normalizeConfigEntry(entry: unknown): CapabilityConfigEntry | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.name !== 'string' || value.name.trim().length === 0) return null
  if (typeof value.type !== 'string' || value.type.trim().length === 0) return null
  const options = Array.isArray(value.options) && value.options.length > 0 ? [...value.options] : undefined
  return {
    name: value.name.trim(),
    type: value.type.trim(),
    default: value.default,
    required: typeof value.required === 'boolean' ? value.required : undefined,
    allowBlank: typeof value.allowBlank === 'boolean' ? value.allowBlank : undefined,
    min: typeof value.min === 'number' ? value.min : undefined,
    max: typeof value.max === 'number' ? value.max : undefined,
    options,
    multiline: typeof value.multiline === 'boolean' ? value.multiline : undefined,
    ui: value.ui,
    when: value.when && typeof value.when === 'object' ? (value.when as Record<string, unknown>) : undefined,
  }
}

export function buildManifestIndex(list: CapabilityManifest[]): ManifestIndex {
  const byDigest = new Map<string, CapabilityManifest>()
  const byImageName = new Map<string, CapabilityManifest>()

  list.forEach((manifest) => {
    const digest = manifest.image?.digest?.trim().toLowerCase()
    if (digest) {
      byDigest.set(digest, manifest)
    }

    const name = manifest.image?.name?.trim().toLowerCase()
    const canonicalName = canonicalImageName(name)
    if (canonicalName) {
      byImageName.set(canonicalName, manifest)
    }
  })

  return { byDigest, byImageName }
}

export function findManifestForImage(image: string, index: ManifestIndex): CapabilityManifest | null {
  return resolveManifestForImage(image, index).manifest
}

export function composeCapabilityConfigEntries(
  workerManifest: CapabilityManifest,
  catalogue: CapabilityManifest[],
  currentConfig: Record<string, unknown> | null | undefined,
): CapabilityConfigEntry[] {
  // TODO(capabilities): Share this IO config composition with the VSCode scenario editor
  // instead of maintaining the same ui.ioScope/ui.ioType merge algorithm in two UI clients.
  const entries: CapabilityConfigEntry[] = []
  const names = new Set<string>()
  appendConfigEntries(entries, names, workerManifest.config)

  for (const scope of Object.keys(IO_SELECTOR_PATHS) as IoScope[]) {
    const selectedType = explicitIoType(currentConfig, scope)
    if (!selectedType) continue
    for (const manifest of catalogue) {
      if (ioManifestScope(manifest) !== scope) continue
      if (ioManifestType(manifest) !== selectedType) continue
      appendConfigEntries(entries, names, manifest.config)
    }
  }

  return entries
}

export function resolveManifestForImage(
  image: string,
  index: ManifestIndex,
): ManifestResolution {
  const reference = parseImageReference(image)
  if (!reference) {
    return { manifest: null, kind: 'none', requestedTag: null, resolvedTag: null }
  }

  if (reference.name) {
    const exact = lookupManifestByImageName(reference.name, index)
    if (exact) {
      return {
        manifest: exact,
        kind: 'exact',
        requestedTag: reference.tag,
        resolvedTag: exact.image?.tag?.trim() ?? null,
      }
    }
  }

  return {
    manifest: null,
    kind: 'none',
    requestedTag: reference.tag,
    resolvedTag: null,
  }
}

type ImageReference = {
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
      tag = remainder.slice(lastColon + 1).trim()
      namePart = remainder.slice(0, lastColon)
    }
  }

  const name = namePart.trim().toLowerCase()
  if (!name) return null

  return {
    name,
    tag: tag && tag.trim() ? tag.trim() : null,
    digest: digest && digest.trim() ? digest.trim() : null,
  }
}

function canonicalImageName(name: string | null | undefined): string | null {
  if (!name) return null
  let normalized = name.trim().toLowerCase()
  if (!normalized) return null
  const digestIndex = normalized.indexOf('@')
  if (digestIndex >= 0) {
    normalized = normalized.slice(0, digestIndex)
  }
  const lastColon = normalized.lastIndexOf(':')
  const lastSlash = normalized.lastIndexOf('/')
  if (lastColon > lastSlash) {
    normalized = normalized.slice(0, lastColon)
  }
  const normalizedLastSlash = normalized.lastIndexOf('/')
  if (normalizedLastSlash >= 0 && normalizedLastSlash < normalized.length - 1) {
    return normalized.slice(normalizedLastSlash + 1)
  }
  return normalized
}

function lookupManifestByImageName(
  name: string,
  index: ManifestIndex,
): CapabilityManifest | null {
  const key = canonicalImageName(name)
  return key ? index.byImageName.get(key) ?? null : null
}

function appendConfigEntries(
  target: CapabilityConfigEntry[],
  names: Set<string>,
  entries: CapabilityConfigEntry[],
) {
  for (const entry of entries) {
    if (names.has(entry.name)) continue
    names.add(entry.name)
    target.push(entry)
  }
}

function explicitIoType(config: Record<string, unknown> | null | undefined, scope: IoScope): string | null {
  const raw = valueAtPath(config, IO_SELECTOR_PATHS[scope])
  if (typeof raw !== 'string') return null
  const normalized = raw.trim().toUpperCase()
  return normalized.length > 0 ? normalized : null
}

function ioManifestScope(manifest: CapabilityManifest): IoScope | null {
  const raw = manifestUiString(manifest, 'ioScope')
  if (!raw) return null
  const normalized = raw.trim().toUpperCase()
  return normalized === 'INPUT' || normalized === 'OUTPUT' ? normalized : null
}

function ioManifestType(manifest: CapabilityManifest): string | null {
  const raw = manifestUiString(manifest, 'ioType')
  if (!raw) return null
  const normalized = raw.trim().toUpperCase()
  return normalized.length > 0 ? normalized : null
}

function manifestUiString(manifest: CapabilityManifest, key: string): string | null {
  const value = manifest.ui?.[key]
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function valueAtPath(config: Record<string, unknown> | null | undefined, path: string): unknown {
  if (!config || !path) return undefined
  const segments = path.split('.').filter((segment) => segment.length > 0)
  let current: unknown = config
  for (const segment of segments) {
    if (!current || typeof current !== 'object' || Array.isArray(current)) {
      return undefined
    }
    current = (current as Record<string, unknown>)[segment]
  }
  return current
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
    if (!expectedText) return false
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
  return trimmed.length > 0 ? trimmed : undefined
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
