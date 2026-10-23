import { useEffect, useMemo, useRef } from 'react'
import { Tree, type NodeRendererProps, type TreeApi } from 'react-arborist'
import type { BundleTreeNode } from '../../lib/scenariosApi'
import { useMeasuredTreeHeight } from './useMeasuredTreeHeight'

type DirectoryNode = {
  id: string
  kind: 'directory'
  path: string
  name: string
  children: FileTreeNode[]
}

type FileNode = {
  id: string
  kind: 'file'
  path: string
  name: string
  item: BundleTreeNode
}

type FileTreeNode = DirectoryNode | FileNode

type MutableDirectory = {
  path: string
  name: string
  children: Map<string, MutableDirectory>
  files: BundleTreeNode[]
}

function ensureDirectory(parent: MutableDirectory, name: string, path: string) {
  const existing = parent.children.get(name)
  if (existing) return existing
  const created: MutableDirectory = { path, name, children: new Map(), files: [] }
  parent.children.set(name, created)
  return created
}

function buildTree(nodes: readonly BundleTreeNode[]): FileTreeNode[] {
  const root: MutableDirectory = { path: '', name: '', children: new Map(), files: [] }

  for (const item of nodes) {
    const segments = item.path
      .split('/')
      .map((segment) => segment.trim())
      .filter((segment) => segment.length > 0)
    if (segments.length === 0) continue

    let current = root
    let currentPath = ''
    for (const segment of segments.slice(0, -1)) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      current = ensureDirectory(current, segment, currentPath)
    }

    if (item.nodeType === 'directory') {
      ensureDirectory(current, segments[segments.length - 1], item.path)
    } else {
      current.files.push(item)
    }
  }

  const finalize = (directory: MutableDirectory): DirectoryNode => ({
    id: `dir:${directory.path}`,
    kind: 'directory',
    path: directory.path,
    name: directory.name,
    children: [
      ...Array.from(directory.children.values())
        .map((child) => finalize(child))
        .sort((a, b) => a.name.localeCompare(b.name)),
      ...directory.files
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(
          (item): FileNode => ({
            id: `file:${item.path}`,
            kind: 'file',
            path: item.path,
            name: item.name,
            item,
          }),
        ),
    ],
  })

  return finalize(root).children
}

function collectDirectoryIds(nodes: readonly FileTreeNode[]): string[] {
  const ids: string[] = []
  for (const node of nodes) {
    if (node.kind !== 'directory') continue
    ids.push(node.id)
    ids.push(...collectDirectoryIds(node.children))
  }
  return ids
}

function Renderer({ node, style, dragHandle }: NodeRendererProps<FileTreeNode>) {
  const data = node.data
  const isDirectory = data.kind === 'directory'

  return (
    <div style={style} className="scenarioTreeRowWrap">
      <div
        ref={dragHandle}
        className={[
          'scenarioTreeRow',
          node.isSelected ? 'scenarioTreeRowSelected' : '',
          isDirectory ? 'scenarioTreeRowFolder' : 'scenarioTreeRowBundle',
        ].filter(Boolean).join(' ')}
      >
        <button
          type="button"
          className="scenarioTreeToggle"
          onClick={(event) => {
            event.stopPropagation()
            if (isDirectory) node.toggle()
          }}
          aria-label={isDirectory ? `${node.isOpen ? 'Collapse' : 'Expand'} folder ${data.name}` : `File ${data.name}`}
          disabled={!isDirectory}
        >
          {isDirectory ? (node.isOpen ? 'v' : '>') : ''}
        </button>
        <span
          className={
            isDirectory
              ? `scenarioTreeGlyph scenarioTreeGlyphFolder ${node.isOpen ? 'scenarioTreeGlyphFolderOpen' : ''}`
              : 'scenarioTreeGlyph scenarioTreeGlyphFile'
          }
          aria-hidden="true"
        >
          {isDirectory ? '' : 'F'}
        </span>
        <div className="scenarioTreeText">
          <div className="scenarioTreeTitleRow">
            <span className="scenarioTreeTitle" title={data.path}>{data.name}</span>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Responsibility: render and select paths from a supplied bundle-file projection.
 * Must not: fetch files, edit their contents or authorize bundle access.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access (presentation only).
 */
export function BundleFileTree({
  nodes,
  selectedPath,
  onSelectFile,
  height,
}: {
  nodes: readonly BundleTreeNode[]
  selectedPath: string | null
  onSelectFile: (path: string) => void
  height: number | 'container'
}) {
  const measured = useMeasuredTreeHeight(180)
  const treeRef = useRef<TreeApi<FileTreeNode> | null>(null)
  const data = useMemo(() => buildTree(nodes), [nodes])
  const selectedNodeId = selectedPath ? `file:${selectedPath}` : undefined
  const initialOpenState = useMemo(
    () => Object.fromEntries(collectDirectoryIds(data).map((id) => [id, true])),
    [data],
  )

  useEffect(() => {
    if (!selectedNodeId) return
    treeRef.current?.openParents(selectedNodeId)
  }, [selectedNodeId, data])

  return (
    <div ref={measured.ref} className="scenarioTreeShell scenarioFileTreeShell">
      {data.length === 0 ? <div className="muted">No files found.</div> : (
        <Tree<FileTreeNode>
          ref={treeRef}
          data={data}
          width="100%"
          height={height === 'container' ? measured.height : height}
          rowHeight={36}
          indent={18}
          paddingTop={4}
          paddingBottom={4}
          overscanCount={6}
          openByDefault={false}
          initialOpenState={initialOpenState}
          selection={selectedNodeId}
          disableDrag
          disableDrop
          onActivate={(node) => {
            if (node.data.kind === 'directory') {
              node.toggle()
              return
            }
            onSelectFile(node.data.path)
          }}
        >
          {Renderer}
        </Tree>
      )}
    </div>
  )
}
