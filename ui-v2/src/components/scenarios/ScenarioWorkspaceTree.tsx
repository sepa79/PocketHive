import { useEffect, useMemo, useRef } from 'react'
import { Tree, type NodeRendererProps, type TreeApi } from 'react-arborist'
import type { BundleTemplateEntry, BundleTreeNode } from '../../lib/scenariosApi'

type WorkspaceFolderNode = {
  id: string
  kind: 'folder'
  path: string
  name: string
  children: WorkspaceNode[]
}

type WorkspaceDirectoryNode = {
  id: string
  kind: 'directory'
  bundleKey: string
  path: string
  name: string
  children: WorkspaceNode[]
}

type WorkspaceBundleNode = {
  id: string
  kind: 'bundle'
  bundleKey: string
  name: string
  item: BundleTemplateEntry
  children: WorkspaceNode[]
}

type WorkspaceFileNode = {
  id: string
  kind: 'file'
  bundleKey: string
  path: string
  name: string
  item: BundleTreeNode
}

type WorkspaceNode = WorkspaceFolderNode | WorkspaceDirectoryNode | WorkspaceBundleNode | WorkspaceFileNode

export type ScenarioWorkspaceSelection =
  | { kind: 'bundle'; bundleKey: string }
  | { kind: 'directory'; bundleKey: string; path: string }
  | { kind: 'file'; bundleKey: string; path: string }

type MutableFolder = {
  path: string
  name: string
  folders: Map<string, MutableFolder>
  bundles: BundleTemplateEntry[]
}

type MutableFileFolder = {
  path: string
  name: string
  folders: Map<string, MutableFileFolder>
  files: BundleTreeNode[]
}

function ensureFolder(parent: MutableFolder, name: string, path: string) {
  const existing = parent.folders.get(name)
  if (existing) return existing
  const created: MutableFolder = { path, name, folders: new Map(), bundles: [] }
  parent.folders.set(name, created)
  return created
}

function ensureFileFolder(parent: MutableFileFolder, name: string, path: string) {
  const existing = parent.folders.get(name)
  if (existing) return existing
  const created: MutableFileFolder = { path, name, folders: new Map(), files: [] }
  parent.folders.set(name, created)
  return created
}

function buildFileNodes(bundleKey: string, nodes: readonly BundleTreeNode[]): WorkspaceNode[] {
  const root: MutableFileFolder = { path: '', name: '', folders: new Map(), files: [] }

  for (const item of nodes) {
    const segments = item.path
      .split('/')
      .map((segment) => segment.trim())
      .filter(Boolean)
    if (segments.length === 0) continue

    let current = root
    let currentPath = ''
    for (const segment of segments.slice(0, -1)) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      current = ensureFileFolder(current, segment, currentPath)
    }

    if (item.nodeType === 'directory') {
      ensureFileFolder(current, segments[segments.length - 1], item.path)
    } else {
      current.files.push(item)
    }
  }

  const finalize = (folder: MutableFileFolder): WorkspaceDirectoryNode => ({
    id: `filedir:${bundleKey}:${folder.path}`,
    kind: 'directory',
    bundleKey,
    path: folder.path,
    name: folder.name,
    children: [
      ...Array.from(folder.folders.values())
        .map(finalize)
        .sort((a, b) => a.name.localeCompare(b.name)),
      ...folder.files
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((item): WorkspaceFileNode => ({
          id: `file:${bundleKey}:${item.path}`,
          kind: 'file',
          bundleKey,
          path: item.path,
          name: item.name,
          item,
        })),
    ],
  })

  return finalize(root).children
}

function buildWorkspaceTree(
  bundles: readonly BundleTemplateEntry[],
  selectedBundleKey: string | null,
  bundleFiles: readonly BundleTreeNode[],
): WorkspaceNode[] {
  const root: MutableFolder = { path: '', name: '', folders: new Map(), bundles: [] }

  for (const bundle of bundles) {
    const folderPath = bundle.folderPath?.trim() ?? ''
    if (!folderPath) {
      root.bundles.push(bundle)
      continue
    }

    let current = root
    let currentPath = ''
    for (const segment of folderPath.split('/').map((part) => part.trim()).filter(Boolean)) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      current = ensureFolder(current, segment, currentPath)
    }
    current.bundles.push(bundle)
  }

  const finalizeFolder = (folder: MutableFolder): WorkspaceFolderNode => ({
    id: `folder:${folder.path}`,
    kind: 'folder',
    path: folder.path,
    name: folder.name,
    children: [
      ...Array.from(folder.folders.values())
        .map(finalizeFolder)
        .sort((a, b) => a.name.localeCompare(b.name)),
      ...folder.bundles
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((bundle): WorkspaceBundleNode => ({
          id: `bundle:${bundle.bundleKey}`,
          kind: 'bundle',
          bundleKey: bundle.bundleKey,
          name: bundle.name,
          item: bundle,
          children: bundle.bundleKey === selectedBundleKey ? buildFileNodes(bundle.bundleKey, bundleFiles) : [],
        })),
    ],
  })

  return finalizeFolder(root).children
}

function collectOpenIds(nodes: readonly WorkspaceNode[], selectedBundleKey: string | null): string[] {
  const result: string[] = []
  for (const node of nodes) {
    if (node.kind === 'folder') {
      const childIds = collectOpenIds(node.children, selectedBundleKey)
      if (childIds.length > 0 || node.children.some((child) => child.kind === 'bundle' && child.bundleKey === selectedBundleKey)) {
        result.push(node.id)
      }
      result.push(...childIds)
    } else if (node.kind === 'directory') {
      result.push(node.id)
      result.push(...collectOpenIds(node.children, selectedBundleKey))
    } else if (node.kind === 'bundle' && node.bundleKey === selectedBundleKey) {
      result.push(node.id)
    }
  }
  return result
}

function rowClassName(node: WorkspaceNode, selected: boolean) {
  return [
    'scenarioTreeRow',
    selected ? 'scenarioTreeRowSelected' : '',
    node.kind === 'folder' || node.kind === 'directory' ? 'scenarioTreeRowFolder' : '',
    node.kind === 'bundle' ? 'scenarioTreeRowBundle' : '',
    node.kind === 'bundle' && node.item.defunct ? 'scenarioTreeRowDefunct' : '',
  ].filter(Boolean).join(' ')
}

function Renderer({
  node,
  style,
  dragHandle,
  onToggleBundle,
  onSelectDirectory,
}: NodeRendererProps<WorkspaceNode> & {
  onToggleBundle: (bundleKey: string) => void
  onSelectDirectory: (bundleKey: string, path: string) => void
}) {
  const data = node.data
  const isBranch = data.kind !== 'file'
  const isOpen = data.kind === 'bundle' ? node.isOpen && data.children.length > 0 : node.isOpen

  return (
    <div style={style} className="scenarioTreeRowWrap">
      <div ref={dragHandle} className={rowClassName(data, node.isSelected)}>
        <button
          type="button"
          className="scenarioTreeToggle"
          onClick={(event) => {
            event.stopPropagation()
            if (data.kind === 'folder') {
              node.toggle()
              node.focus()
              return
            }
            if (data.kind === 'directory') {
              onSelectDirectory(data.bundleKey, data.path)
              node.toggle()
              node.focus()
              return
            }
            if (data.kind === 'bundle') {
              onToggleBundle(data.bundleKey)
              node.toggle()
              node.focus()
            }
          }}
          aria-label={isBranch ? `${isOpen ? 'Collapse' : 'Expand'} ${data.name}` : `File ${data.name}`}
          disabled={!isBranch}
        >
          {isBranch ? (isOpen ? 'v' : '>') : ''}
        </button>
        <span
          className={
            data.kind === 'folder'
              || data.kind === 'directory'
              ? `scenarioTreeGlyph scenarioTreeGlyphFolder ${node.isOpen ? 'scenarioTreeGlyphFolderOpen' : ''}`
              : data.kind === 'bundle'
                ? 'scenarioTreeGlyph scenarioTreeGlyphScenario'
                : 'scenarioTreeGlyph scenarioTreeGlyphFile'
          }
          aria-hidden="true"
        >
          {data.kind === 'folder' || data.kind === 'directory' ? '' : data.kind === 'bundle' ? 'S' : 'F'}
        </span>
        <div className="scenarioTreeText">
          <div className="scenarioTreeTitleRow">
            <span className={data.kind === 'bundle' && data.item.defunct ? 'scenarioTreeTitle scenarioTreeTitleDefunct' : 'scenarioTreeTitle'}>
              {data.name}
            </span>
            {data.kind === 'bundle' && data.item.defunct ? <span className="pill pillBad scenarioTreeDefunctPill">DEFUNCT</span> : null}
          </div>
        </div>
      </div>
    </div>
  )
}

export function ScenarioWorkspaceTree({
  bundles,
  selectedBundleKey,
  selectedFilePath,
  selectedDirectoryPath,
  bundleFiles,
  onSelectBundle,
  onSelectFile,
  onSelectDirectory,
  height = 640,
}: {
  bundles: readonly BundleTemplateEntry[]
  selectedBundleKey: string | null
  selectedFilePath: string | null
  selectedDirectoryPath: string | null
  bundleFiles: readonly BundleTreeNode[]
  onSelectBundle: (bundleKey: string) => void
  onSelectFile: (path: string) => void
  onSelectDirectory: (path: string) => void
  height?: number
}) {
  const treeRef = useRef<TreeApi<WorkspaceNode> | null>(null)
  const data = useMemo(() => buildWorkspaceTree(bundles, selectedBundleKey, bundleFiles), [bundleFiles, bundles, selectedBundleKey])
  const selectedNodeId = selectedBundleKey && selectedFilePath
    ? `file:${selectedBundleKey}:${selectedFilePath}`
    : selectedBundleKey && selectedDirectoryPath
      ? `filedir:${selectedBundleKey}:${selectedDirectoryPath}`
      : selectedBundleKey
        ? `bundle:${selectedBundleKey}`
        : undefined
  const initialOpenState = useMemo(
    () => Object.fromEntries(collectOpenIds(data, selectedBundleKey).map((id) => [id, true])),
    [data, selectedBundleKey],
  )

  useEffect(() => {
    if (!selectedNodeId) return
    treeRef.current?.openParents(selectedNodeId)
    treeRef.current?.open(selectedNodeId)
  }, [selectedNodeId, data])

  if (data.length === 0) {
    return <div className="muted">No scenarios.</div>
  }

  return (
    <div className="scenarioTreeShell">
      <Tree<WorkspaceNode>
        ref={treeRef}
        data={data}
        width="100%"
        height={height}
        rowHeight={30}
        indent={18}
        paddingTop={4}
        paddingBottom={4}
        overscanCount={8}
        openByDefault={false}
        initialOpenState={initialOpenState}
        selection={selectedNodeId}
        disableDrag
        disableDrop
        onActivate={(node) => {
          if (node.data.kind === 'folder') {
            node.toggle()
            return
          }
          if (node.data.kind === 'directory') {
            onSelectBundle(node.data.bundleKey)
            onSelectDirectory(node.data.path)
            node.toggle()
            return
          }
          if (node.data.kind === 'bundle') {
            onSelectBundle(node.data.bundleKey)
            node.toggle()
            return
          }
          onSelectBundle(node.data.bundleKey)
          onSelectFile(node.data.path)
        }}
      >
        {(props) => <Renderer {...props} onToggleBundle={onSelectBundle} onSelectDirectory={onSelectDirectory} />}
      </Tree>
    </div>
  )
}
