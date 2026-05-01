type ApiError = Error & { status?: number }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

async function ensureOk(response: Response, fallback: string) {
  if (response.ok) return
  let message = ''
  try {
    const text = await response.text()
    message = text ? text : message
  } catch {
    // ignore
  }
  const error: ApiError = new Error(message || fallback)
  error.status = response.status
  throw error
}

export type ScenarioSummary = {
  id: string
  name: string
  folderPath: string | null
}

export type BundleBeeSummary = {
  role: string
  image: string | null
}

export type BundleTemplateEntry = {
  bundleKey: string
  bundlePath: string
  folderPath: string | null
  id: string | null
  name: string
  description: string | null
  controllerImage: string | null
  bees: BundleBeeSummary[]
  defunct: boolean
  defunctReason: string | null
}

export type BundleDownload = {
  blob: Blob
  fileName: string
}

export type BundleTreeNodeType = 'directory' | 'file'
export type BundleEditorKind = 'text' | 'yaml' | 'json' | 'markdown' | 'unsupported'

export type BundleTreeNode = {
  bundleKey: string
  path: string
  name: string
  nodeType: BundleTreeNodeType
  mediaType: string | null
  editorKind: BundleEditorKind
  writable: boolean
  size: number | null
}

export type BundleTree = {
  bundleKey: string
  nodes: BundleTreeNode[]
}

export type BundleFilePayload = {
  bundleKey: string
  path: string
  name: string
  mediaType: string
  editorKind: BundleEditorKind
  writable: boolean
  size: number
  revision: string
  content: string | null
}

function normalizeScenarioSummary(input: unknown): ScenarioSummary | null {
  if (!isRecord(input)) return null
  const id = asString(input['id'])
  if (!id) return null
  const name = asString(input['name']) ?? id
  const folderPath = asString(input['folderPath'])
  return { id, name, folderPath }
}

function normalizeBundleTemplateEntry(input: unknown): BundleTemplateEntry | null {
  if (!isRecord(input)) return null
  const bundleKey = asString(input['bundleKey'])
  const bundlePath = asString(input['bundlePath'])
  const name = asString(input['name'])
  if (!bundleKey || !bundlePath || !name) return null
  const folderPath = asString(input['folderPath'])
  const id = asString(input['id'])
  const description = asString(input['description'])
  const controllerImage = asString(input['controllerImage'])
  const bees: BundleBeeSummary[] = Array.isArray(input['bees'])
    ? input['bees']
        .map((bee) => {
          if (!isRecord(bee)) return null
          const role = asString(bee['role'])
          if (!role) return null
          return { role, image: asString(bee['image']) }
        })
        .filter((bee): bee is BundleBeeSummary => bee !== null)
    : []
  const defunct = input['defunct'] === true
  const defunctReason = asString(input['defunctReason'])
  return { bundleKey, bundlePath, folderPath, id, name, description, controllerImage, bees, defunct, defunctReason }
}

function normalizeEditorKind(value: unknown): BundleEditorKind {
  return value === 'text' || value === 'yaml' || value === 'json' || value === 'markdown' || value === 'unsupported'
    ? value
    : 'unsupported'
}

function normalizeNodeType(value: unknown): BundleTreeNodeType {
  return value === 'directory' ? 'directory' : 'file'
}

function normalizeBundleTreeNode(input: unknown): BundleTreeNode | null {
  if (!isRecord(input)) return null
  const bundleKey = asString(input['bundleKey'])
  const path = asString(input['path'])
  const name = asString(input['name'])
  if (!bundleKey || !path || !name) return null
  const size = typeof input['size'] === 'number' && Number.isFinite(input['size']) ? input['size'] : null
  return {
    bundleKey,
    path,
    name,
    nodeType: normalizeNodeType(input['nodeType']),
    mediaType: asString(input['mediaType']),
    editorKind: normalizeEditorKind(input['editorKind']),
    writable: input['writable'] === true,
    size,
  }
}

function normalizeBundleTree(input: unknown): BundleTree {
  if (!isRecord(input)) return { bundleKey: '', nodes: [] }
  const bundleKey = asString(input['bundleKey']) ?? ''
  const nodes = Array.isArray(input['nodes'])
    ? input['nodes']
        .map((entry) => normalizeBundleTreeNode(entry))
        .filter((entry): entry is BundleTreeNode => entry !== null)
    : []
  return { bundleKey, nodes }
}

function normalizeBundleFile(input: unknown): BundleFilePayload | null {
  if (!isRecord(input)) return null
  const bundleKey = asString(input['bundleKey'])
  const path = asString(input['path'])
  const name = asString(input['name'])
  const mediaType = asString(input['mediaType'])
  const revision = asString(input['revision'])
  if (!bundleKey || !path || !name || !mediaType || !revision) return null
  return {
    bundleKey,
    path,
    name,
    mediaType,
    editorKind: normalizeEditorKind(input['editorKind']),
    writable: input['writable'] === true,
    size: typeof input['size'] === 'number' && Number.isFinite(input['size']) ? input['size'] : 0,
    revision,
    content: typeof input['content'] === 'string' ? input['content'] : null,
  }
}

export async function listScenarios(opts?: { includeDefunct?: boolean }): Promise<ScenarioSummary[]> {
  const includeDefunct = opts?.includeDefunct ?? true
  const params = new URLSearchParams({ includeDefunct: includeDefunct ? 'true' : 'false' })
  const response = await fetch(`/scenario-manager/scenarios?${params.toString()}`, {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load scenarios')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => normalizeScenarioSummary(entry))
      .filter((entry): entry is ScenarioSummary => Boolean(entry))
  } catch {
    return []
  }
}

export async function listBundleTemplates(): Promise<BundleTemplateEntry[]> {
  const response = await fetch('/scenario-manager/api/templates', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load bundle templates')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => normalizeBundleTemplateEntry(entry))
      .filter((entry): entry is BundleTemplateEntry => entry !== null)
  } catch {
    return []
  }
}

export async function listBundleWorkspaces(): Promise<BundleTemplateEntry[]> {
  const response = await fetch('/scenario-manager/scenarios/bundles/workspaces', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load scenario workspaces')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => normalizeBundleTemplateEntry(entry))
      .filter((entry): entry is BundleTemplateEntry => entry !== null)
  } catch {
    return []
  }
}

export async function readBundleTree(bundleKey: string): Promise<BundleTree> {
  const params = new URLSearchParams({ bundleKey })
  const response = await fetch(`/scenario-manager/scenarios/bundles/tree?${params.toString()}`, {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load bundle tree')
  const payload = (await response.json()) as unknown
  return normalizeBundleTree(payload)
}

export async function readBundleFile(bundleKey: string, path: string): Promise<BundleFilePayload> {
  const params = new URLSearchParams({ bundleKey, path })
  const response = await fetch(`/scenario-manager/scenarios/bundles/file?${params.toString()}`, {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load bundle file')
  const payload = (await response.json()) as unknown
  const normalized = normalizeBundleFile(payload)
  if (!normalized) {
    throw new Error('Invalid bundle file response')
  }
  return normalized
}

export async function listScenarioFolders(): Promise<string[]> {
  const response = await fetch('/scenario-manager/scenarios/folders', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load scenario folders')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => (typeof entry === 'string' ? entry.trim() : ''))
      .filter((entry) => entry.length > 0)
  } catch {
    return []
  }
}

export async function createScenarioFolder(path: string): Promise<void> {
  const response = await fetch('/scenario-manager/scenarios/folders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  })
  await ensureOk(response, 'Failed to create scenario folder')
}

export async function deleteScenarioFolder(path: string): Promise<void> {
  const params = new URLSearchParams({ path })
  const response = await fetch(`/scenario-manager/scenarios/folders?${params.toString()}`, {
    method: 'DELETE',
  })
  await ensureOk(response, 'Failed to delete scenario folder')
}

export async function moveScenarioToFolder(scenarioId: string, path: string | null): Promise<void> {
  const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  })
  await ensureOk(response, 'Failed to move scenario')
}

export async function moveBundleToFolder(bundleKey: string, path: string | null): Promise<void> {
  const response = await fetch('/scenario-manager/scenarios/bundles/move', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ bundleKey, path }),
  })
  await ensureOk(response, 'Failed to move bundle')
}

export async function uploadScenarioBundle(file: File | Blob): Promise<ScenarioSummary | null> {
  const response = await fetch('/scenario-manager/scenarios/bundles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/zip' },
    body: file,
  })
  await ensureOk(response, 'Failed to upload scenario bundle')
  try {
    const payload = (await response.json()) as unknown
    return normalizeScenarioSummary(payload)
  } catch {
    return null
  }
}

function parseDownloadFileName(response: Response, fallback: string): string {
  const contentDisposition = response.headers.get('content-disposition') ?? ''
  const encoded = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (encoded && encoded[1]) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      return encoded[1]
    }
  }
  const plain = contentDisposition.match(/filename="?([^"]+)"?/i)
  if (plain && plain[1]) return plain[1]
  return fallback
}

export async function downloadScenarioBundle(scenarioId: string): Promise<BundleDownload> {
  const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/bundle`, {
    headers: { Accept: 'application/zip' },
  })
  await ensureOk(response, 'Failed to download scenario bundle')
  return {
    blob: await response.blob(),
    fileName: parseDownloadFileName(response, `${scenarioId}-bundle.zip`),
  }
}

export async function downloadBundle(bundleKey: string): Promise<BundleDownload> {
  const params = new URLSearchParams({ bundleKey })
  const response = await fetch(`/scenario-manager/scenarios/bundles/download?${params.toString()}`, {
    headers: { Accept: 'application/zip' },
  })
  await ensureOk(response, 'Failed to download bundle')
  return {
    blob: await response.blob(),
    fileName: parseDownloadFileName(response, 'scenario-bundle.zip'),
  }
}

export async function deleteScenarioBundle(scenarioId: string): Promise<void> {
  const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}`, {
    method: 'DELETE',
  })
  await ensureOk(response, 'Failed to delete scenario bundle')
}

export async function deleteBundle(bundleKey: string): Promise<void> {
  const params = new URLSearchParams({ bundleKey })
  const response = await fetch(`/scenario-manager/scenarios/bundles?${params.toString()}`, {
    method: 'DELETE',
  })
  await ensureOk(response, 'Failed to delete bundle')
}
