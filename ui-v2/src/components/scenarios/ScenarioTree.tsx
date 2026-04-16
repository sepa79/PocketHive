import { useEffect, useMemo, useRef, useState } from 'react'
import { Tree, type NodeRendererProps, type TreeApi } from 'react-arborist'

type ScenarioTreeEntry = {
  bundleKey: string
  bundlePath: string
  folderPath: string | null
  id: string | null
  name: string
  description: string | null
  defunct: boolean
  defunctReason: string | null
}

type FolderNode<T extends ScenarioTreeEntry> = {
  id: string
  name: string
  kind: 'folder'
  path: string
  itemCount: number
  children: TreeNode<T>[]
}

type BundleNode<T extends ScenarioTreeEntry> = {
  id: string
  name: string
  kind: 'bundle'
  item: T
}

type TreeNode<T extends ScenarioTreeEntry> = FolderNode<T> | BundleNode<T>

type MutableFolderNode<T extends ScenarioTreeEntry> = {
  name: string
  path: string
  children: Map<string, MutableFolderNode<T>>
  bundles: T[]
}

const MIN_TREE_HEIGHT = 180

function countBundles<T extends ScenarioTreeEntry>(node: FolderNode<T>): number {
  return node.itemCount
}

function buildTreeData<T extends ScenarioTreeEntry>(items: readonly T[]): TreeNode<T>[] {
  const root: MutableFolderNode<T> = { name: '', path: '', children: new Map(), bundles: [] }

  const ensureFolder = (parent: MutableFolderNode<T>, name: string, path: string) => {
    const existing = parent.children.get(name)
    if (existing) return existing
    const created: MutableFolderNode<T> = { name, path, children: new Map(), bundles: [] }
    parent.children.set(name, created)
    return created
  }

  for (const item of items) {
    const folderPath = item.folderPath?.trim() ?? ''
    if (!folderPath) {
      root.bundles.push(item)
      continue
    }
    const segments = folderPath
      .split('/')
      .map((segment) => segment.trim())
      .filter((segment) => segment.length > 0)
    if (segments.length === 0) {
      root.bundles.push(item)
      continue
    }
    let current = root
    let currentPath = ''
    for (const segment of segments) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      current = ensureFolder(current, segment, currentPath)
    }
    current.bundles.push(item)
  }

  const finalizeFolder = (node: MutableFolderNode<T>): FolderNode<T> => {
    const childFolders = Array.from(node.children.values())
      .map((child) => finalizeFolder(child))
      .sort((a, b) => a.name.localeCompare(b.name))
    const childBundles = [...node.bundles]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(
        (item): BundleNode<T> => ({
          id: `bundle:${item.bundleKey}`,
          name: item.name,
          kind: 'bundle',
          item,
        }),
      )
    const children = [...childFolders, ...childBundles]
    const itemCount =
      node.bundles.length + childFolders.reduce((sum, child) => sum + countBundles(child), 0)
    return {
      id: `folder:${node.path}`,
      name: node.name,
      kind: 'folder',
      path: node.path,
      itemCount,
      children,
    }
  }

  const folders = Array.from(root.children.values())
    .map((child) => finalizeFolder(child))
    .sort((a, b) => a.name.localeCompare(b.name))
  const bundles = [...root.bundles]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(
      (item): BundleNode<T> => ({
        id: `bundle:${item.bundleKey}`,
        name: item.name,
        kind: 'bundle',
        item,
      }),
    )
  return [...folders, ...bundles]
}

function collectFolderIds<T extends ScenarioTreeEntry>(nodes: readonly TreeNode<T>[]): string[] {
  const ids: string[] = []
  for (const node of nodes) {
    if (node.kind !== 'folder') continue
    ids.push(node.id)
    ids.push(...collectFolderIds(node.children))
  }
  return ids
}

function useMeasuredHeight<T extends HTMLElement>() {
  const ref = useRef<T | null>(null)
  const [height, setHeight] = useState<number>(MIN_TREE_HEIGHT)

  useEffect(() => {
    const element = ref.current
    if (!element) return
    const updateHeight = (next: number) => {
      setHeight(Math.max(MIN_TREE_HEIGHT, Math.floor(next)))
    }
    updateHeight(element.getBoundingClientRect().height)
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (!entry) return
      updateHeight(entry.contentRect.height)
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  return { ref, height }
}

function ScenarioTreeNodeRenderer<T extends ScenarioTreeEntry>({ node, style, dragHandle }: NodeRendererProps<TreeNode<T>>) {
  const data = node.data
  const isFolder = data.kind === 'folder'
  const leaf = isFolder ? null : data.item

  return (
    <div style={style} className="scenarioTreeRowWrap">
      <div
        ref={dragHandle}
        className={[
          'scenarioTreeRow',
          node.isSelected ? 'scenarioTreeRowSelected' : '',
          !isFolder && leaf?.defunct ? 'scenarioTreeRowDefunct' : '',
          isFolder ? 'scenarioTreeRowFolder' : 'scenarioTreeRowBundle',
        ].filter(Boolean).join(' ')}
      >
        <button
          type="button"
          className="scenarioTreeToggle"
          onClick={(event) => {
            event.stopPropagation()
            if (isFolder) {
              node.toggle()
              node.focus()
            }
          }}
          aria-label={isFolder ? `${node.isOpen ? 'Collapse' : 'Expand'} folder ${data.name}` : `Bundle ${data.name}`}
          disabled={!isFolder}
        >
          {isFolder ? (node.isOpen ? 'v' : '>') : ''}
        </button>
        <span
          className={
            isFolder
              ? `scenarioTreeGlyph scenarioTreeGlyphFolder ${node.isOpen ? 'scenarioTreeGlyphFolderOpen' : ''}`
              : 'scenarioTreeGlyph scenarioTreeGlyphScenario'
          }
          aria-hidden="true"
        >
          {isFolder ? '' : 'S'}
        </span>
        <div className="scenarioTreeText">
          <div className="scenarioTreeTitleRow">
            <span className={!isFolder && leaf?.defunct ? 'scenarioTreeTitle scenarioTreeTitleDefunct' : 'scenarioTreeTitle'}>
              {data.name}
            </span>
            {!isFolder && leaf?.defunct ? <span className="pill pillBad">DEFUNCT</span> : null}
          </div>
        </div>
      </div>
    </div>
  )
}

export function ScenarioTree<T extends ScenarioTreeEntry>({
  items,
  selectedBundleKey,
  onSelectBundle,
  searchTerm,
  openPaths,
  rowHeight = 42,
  emptyMessage = 'No items.',
}: {
  items: readonly T[]
  selectedBundleKey: string | null
  onSelectBundle: (bundleKey: string) => void
  searchTerm?: string
  openPaths?: Set<string> | null
  rowHeight?: number
  emptyMessage?: string
}) {
  const { ref, height } = useMeasuredHeight<HTMLDivElement>()
  const treeRef = useRef<TreeApi<TreeNode<T>> | null>(null)

  const data = useMemo(() => buildTreeData(items), [items])
  const selectedNodeId = selectedBundleKey ? `bundle:${selectedBundleKey}` : undefined

  const initialOpenState = useMemo(() => {
    const ids =
      openPaths === null
        ? collectFolderIds(data)
        : Array.from(openPaths ?? [], (path) => `folder:${path}`)
    return Object.fromEntries(ids.map((id) => [id, true]))
  }, [data, openPaths])

  const Node = (props: NodeRendererProps<TreeNode<T>>) => <ScenarioTreeNodeRenderer {...props} />

  useEffect(() => {
    if (!selectedNodeId) return
    treeRef.current?.openParents(selectedNodeId)
  }, [selectedNodeId, data])

  return (
    <div ref={ref} className="scenarioTreeShell">
      {data.length === 0 ? (
        <div className="muted">{emptyMessage}</div>
      ) : (
        <Tree<TreeNode<T>>
          ref={treeRef}
          data={data}
          width="100%"
          height={height}
          rowHeight={rowHeight}
          indent={20}
          paddingTop={4}
          paddingBottom={4}
          overscanCount={6}
          openByDefault={false}
          initialOpenState={initialOpenState}
          searchTerm={searchTerm}
          searchMatch={(node, term) => {
            const needle = term.trim().toLowerCase()
            if (!needle) return true
            if (node.data.kind === 'folder') {
              return `${node.data.name} ${node.data.path}`.toLowerCase().includes(needle)
            }
            const item = node.data.item
            return `${item.folderPath ?? ''} ${item.bundlePath} ${item.id ?? ''} ${item.name} ${item.description ?? ''}`
              .toLowerCase()
              .includes(needle)
          }}
          selection={selectedNodeId}
          disableDrag
          disableDrop
          onActivate={(node) => {
            if (node.data.kind === 'folder') {
              node.toggle()
              return
            }
            onSelectBundle(node.data.item.bundleKey)
          }}
        >
          {Node}
        </Tree>
      )}
    </div>
  )
}
