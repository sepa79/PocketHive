export type CapabilityImage = {
  name?: string | null
  tag?: string | null
  digest?: string | null
}

export type CapabilityManifest = {
  schemaVersion: string
  capabilitiesVersion: string
  image: CapabilityImage
  role: string
  config: unknown[]
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
  const config = Array.isArray(value.config) ? [...value.config] : []
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
