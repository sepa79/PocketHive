import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  getRabbitTopology,
  getRuntimeLogs,
  getRuntimeVersion,
  inspectRuntime,
  listRuntimeResources,
  type RabbitTopologySnapshot,
  type RuntimeInspectResponse,
  type RuntimeLogsResponse,
  type RuntimeResource,
  type RuntimeResourceListResponse,
  type RuntimeVersionResponse,
} from '../../lib/runtimeDebugApi'

type RuntimeInspectorProps = {
  swarmId: string
  runId: string | null
}

type InspectorAction = 'logs' | 'version' | 'inspect'

function runtimeKey(resource: RuntimeResource): string {
  return [
    resource.runtimeType ?? 'runtime',
    resource.runtimeId ?? resource.name ?? 'unknown',
    resource.resourceKind ?? 'resource',
    resource.instance ?? resource.role ?? '',
  ].join(':')
}

function runtimeLabel(resource: RuntimeResource): string {
  return resource.instance ?? resource.logicalName ?? resource.name ?? resource.runtimeId ?? 'runtime'
}

function runtimeSubtitle(resource: RuntimeResource): string {
  const parts = [
    resource.resourceKind ?? 'resource',
    resource.role,
    resource.runtimeType,
    resource.state,
  ].filter((part): part is string => typeof part === 'string' && part.trim().length > 0)
  return parts.join(' · ')
}

function safeJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function formatBool(value: boolean | null | undefined): string {
  if (value === true) return 'true'
  if (value === false) return 'false'
  return '—'
}

function formatNumber(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '—'
}

export function SwarmRuntimeInspector({ swarmId, runId }: RuntimeInspectorProps) {
  const [inventory, setInventory] = useState<RuntimeResourceListResponse | null>(null)
  const [rabbit, setRabbit] = useState<RabbitTopologySnapshot | null>(null)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [tailLines, setTailLines] = useState(200)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [actionBusy, setActionBusy] = useState<InspectorAction | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [logs, setLogs] = useState<RuntimeLogsResponse | null>(null)
  const [version, setVersion] = useState<RuntimeVersionResponse | null>(null)
  const [inspect, setInspect] = useState<RuntimeInspectResponse | null>(null)
  const loadSequence = useRef(0)
  const actionSequence = useRef(0)
  const lastSwarmId = useRef<string | null>(null)

  const resources = useMemo(() => {
    const managers = inventory?.managers ?? []
    const workers = inventory?.workers ?? []
    return [...managers, ...workers]
  }, [inventory])

  const selectedResource = useMemo(() => {
    if (!resources.length) return null
    if (selectedKey) {
      const match = resources.find((resource) => runtimeKey(resource) === selectedKey)
      if (match) return match
    }
    return resources[0] ?? null
  }, [resources, selectedKey])

  const selectedRuntimeId = selectedResource?.runtimeId?.trim() ?? ''
  const selectedResourceKind = selectedResource?.resourceKind ?? null
  const selectedResourceKey = selectedResource ? runtimeKey(selectedResource) : ''

  const loadInspector = useCallback(async () => {
    const sequence = loadSequence.current + 1
    loadSequence.current = sequence
    setRefreshing(true)
    setError(null)
    try {
      const [nextInventory, nextRabbit] = await Promise.all([
        listRuntimeResources(swarmId, runId),
        getRabbitTopology(swarmId, runId),
      ])
      if (loadSequence.current !== sequence) return
      setInventory(nextInventory)
      setRabbit(nextRabbit)
      const nextResources = [...(nextInventory.managers ?? []), ...(nextInventory.workers ?? [])]
      setSelectedKey((current) => {
        if (current && nextResources.some((resource) => runtimeKey(resource) === current)) {
          return current
        }
        return nextResources[0] ? runtimeKey(nextResources[0]) : null
      })
    } catch (err) {
      if (loadSequence.current !== sequence) return
      setError(err instanceof Error ? err.message : 'Failed to load runtime inspector data')
    } finally {
      if (loadSequence.current === sequence) {
        setRefreshing(false)
      }
    }
  }, [runId, swarmId])

  useEffect(() => {
    const swarmChanged = lastSwarmId.current !== swarmId
    lastSwarmId.current = swarmId
    if (swarmChanged) {
      setInventory(null)
      setRabbit(null)
      setSelectedKey(null)
      setLogs(null)
      setVersion(null)
      setInspect(null)
      setActionError(null)
    }
    void loadInspector()
  }, [loadInspector, swarmId])

  useEffect(() => {
    actionSequence.current += 1
    setActionBusy(null)
    setActionError(null)
    setLogs(null)
    setVersion(null)
    setInspect(null)
  }, [selectedResourceKey])

  const runAction = useCallback(
    async (action: InspectorAction) => {
      if (!selectedRuntimeId) return
      const sequence = actionSequence.current + 1
      actionSequence.current = sequence
      setActionBusy(action)
      setActionError(null)
      try {
        if (action === 'logs') {
          const nextLogs = await getRuntimeLogs({
            swarmId,
            runId,
            runtimeId: selectedRuntimeId,
            resourceKind: selectedResourceKind,
            tailLines,
          })
          if (actionSequence.current !== sequence) return
          setLogs(nextLogs)
        } else if (action === 'version') {
          const nextVersion = await getRuntimeVersion({
            swarmId,
            runId,
            runtimeId: selectedRuntimeId,
            resourceKind: selectedResourceKind,
          })
          if (actionSequence.current !== sequence) return
          setVersion(nextVersion)
        } else {
          const nextInspect = await inspectRuntime({
            swarmId,
            runId,
            runtimeId: selectedRuntimeId,
            resourceKind: selectedResourceKind,
          })
          if (actionSequence.current !== sequence) return
          setInspect(nextInspect)
        }
      } catch (err) {
        if (actionSequence.current !== sequence) return
        setActionError(err instanceof Error ? err.message : `Failed to run ${action}`)
      } finally {
        if (actionSequence.current === sequence) {
          setActionBusy(null)
        }
      }
    },
    [runId, selectedResourceKind, selectedRuntimeId, swarmId, tailLines],
  )

  return (
    <div className="card runtimeInspector" aria-busy={refreshing}>
      <div className="row between">
        <div>
          <div className="h2">Runtime inspector</div>
          <div className="muted">
            {inventory?.computeAdapter ?? rabbit?.computeAdapter ?? 'adapter: —'} · run {inventory?.runId ?? runId ?? '—'}
          </div>
        </div>
        <button
          type="button"
          className="actionButton actionButtonGhost"
          onClick={() => void loadInspector()}
          disabled={refreshing && inventory === null}
        >
          Refresh
        </button>
      </div>

      {error ? <div className="card swarmMessage">{error}</div> : null}

      <div className="kvGrid">
        <div className="kv">
          <div className="k">Workers</div>
          <div className="v">{inventory?.counts?.workers ?? inventory?.workers.length ?? '—'}</div>
        </div>
        <div className="kv">
          <div className="k">Managers</div>
          <div className="v">{inventory?.counts?.managers ?? inventory?.managers.length ?? '—'}</div>
        </div>
        <div className="kv">
          <div className="k">Blocked</div>
          <div className="v">{inventory?.counts?.blocked ?? inventory?.blocked.length ?? '—'}</div>
        </div>
        <div className="kv">
          <div className="k">Rabbit exact</div>
          <div className="v">{formatBool(rabbit?.exactOnly)}</div>
        </div>
      </div>

      <div className="runtimeInspectorLayout">
        <div className="runtimeResourceList" aria-label="Runtime resources">
          {resources.length ? (
            resources.map((resource) => {
              const key = runtimeKey(resource)
              const selected = selectedResource ? key === runtimeKey(selectedResource) : false
              return (
                <button
                  key={key}
                  type="button"
                  className={selected ? 'runtimeResourceItem runtimeResourceItemSelected' : 'runtimeResourceItem'}
                  onClick={() => setSelectedKey(key)}
                >
                  <span className="runtimeResourceTitle">
                    <span className={resource.running ? 'pill pillOk' : 'pill pillWarn'}>
                      {resource.running ? 'running' : 'stopped'}
                    </span>
                    <span>{runtimeLabel(resource)}</span>
                  </span>
                  <span className="runtimeResourceMeta">{runtimeSubtitle(resource) || '—'}</span>
                  <span className="runtimeResourceMeta">{resource.image ?? 'image: —'}</span>
                </button>
              )
            })
          ) : (
            <div className="runtimeResourceEmpty">No runtime resources.</div>
          )}
        </div>

        <div className="runtimeInspectorPanels">
          {selectedResource ? (
            <>
              <div className="kvGrid">
                <div className="kv">
                  <div className="k">Runtime ID</div>
                  <div className="v">{selectedResource.runtimeId ?? '—'}</div>
                </div>
                <div className="kv">
                  <div className="k">Kind</div>
                  <div className="v">{selectedResource.resourceKind ?? '—'}</div>
                </div>
                <div className="kv">
                  <div className="k">Role</div>
                  <div className="v">{selectedResource.role ?? '—'}</div>
                </div>
                <div className="kv">
                  <div className="k">Instance</div>
                  <div className="v">{selectedResource.instance ?? '—'}</div>
                </div>
                <div className="kv">
                  <div className="k">Declared version</div>
                  <div className="v">{selectedResource.declaredVersion ?? '—'}</div>
                </div>
                <div className="kv">
                  <div className="k">Reported version</div>
                  <div className="v">{selectedResource.reportedVersion ?? '—'}</div>
                </div>
              </div>

              <div className="runtimeInspectorToolbar">
                <label className="row" style={{ gap: 6, alignItems: 'center' }}>
                  <span className="muted">tail</span>
                  <input
                    className="textInput textInputCompact runtimeTailInput"
                    type="number"
                    min={10}
                    max={1000}
                    step={10}
                    value={tailLines}
                    onChange={(event) => {
                      const value = Number(event.currentTarget.value)
                      setTailLines(Number.isFinite(value) ? Math.max(10, Math.min(1000, value)) : 200)
                    }}
                  />
                </label>
                <button
                  type="button"
                  className="actionButton"
                  disabled={!selectedRuntimeId || actionBusy !== null}
                  onClick={() => void runAction('logs')}
                >
                  Logs
                </button>
                <button
                  type="button"
                  className="actionButton actionButtonGhost"
                  disabled={!selectedRuntimeId || actionBusy !== null}
                  onClick={() => void runAction('version')}
                >
                  Version
                </button>
                <button
                  type="button"
                  className="actionButton actionButtonGhost"
                  disabled={!selectedRuntimeId || actionBusy !== null}
                  onClick={() => void runAction('inspect')}
                >
                  Inspect
                </button>
                {actionBusy ? <span className="muted">Running {actionBusy}...</span> : null}
              </div>

              {actionError ? <div className="card swarmMessage">{actionError}</div> : null}

              <div className="runtimeInspectorOutput">
                <div>
                  <div className="fieldLabel">Logs</div>
                  <pre className="codePre runtimeCodePre">
                    {logs ? logs.logs || '(empty)' : '—'}
                  </pre>
                </div>
                <div>
                  <div className="fieldLabel">Version</div>
                  <pre className="codePre runtimeCodePre">
                    {version ? safeJson(version) : '—'}
                  </pre>
                </div>
                <div>
                  <div className="fieldLabel">Inspect</div>
                  <pre className="codePre runtimeCodePre">
                    {inspect ? safeJson(inspect) : '—'}
                  </pre>
                </div>
              </div>
            </>
          ) : (
            <div className="muted">Select a runtime resource.</div>
          )}
        </div>
      </div>

      <div className="runtimeRabbitGrid">
        <div className="swarmDetailSection">
          <div className="fieldLabel">Rabbit queues</div>
          {(rabbit?.queues ?? []).length ? (
            <div className="runtimeRabbitList">
              {(rabbit?.queues ?? []).map((queue) => (
                <div key={queue.name} className="runtimeRabbitItem">
                  <span>{queue.name}</span>
                  <span className="muted">
                    present {formatBool(queue.present)} · messages {formatNumber(queue.messages)} · consumers{' '}
                    {formatNumber(queue.consumers)}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <div className="muted">No exact queues.</div>
          )}
        </div>
        <div className="swarmDetailSection">
          <div className="fieldLabel">Rabbit exchanges</div>
          {(rabbit?.exchanges ?? []).length ? (
            <div className="runtimeRabbitList">
              {(rabbit?.exchanges ?? []).map((exchange) => (
                <div key={exchange.name} className="runtimeRabbitItem">
                  <span>{exchange.name}</span>
                  <span className="muted">
                    present {formatBool(exchange.present)} · type {exchange.type ?? '—'}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <div className="muted">No exact exchanges.</div>
          )}
        </div>
      </div>

      {inventory?.blocked.length ? (
        <div className="swarmDetailSection">
          <div className="fieldLabel">Blocked resources</div>
          <div className="runtimeRabbitList">
            {inventory.blocked.map((resource) => (
              <div key={`${resource.runtimeType}:${resource.runtimeId}:${resource.name}`} className="runtimeRabbitItem">
                <span>{resource.name ?? resource.runtimeId ?? 'blocked'}</span>
                <span className="muted">
                  {resource.state ?? 'state: —'} · {resource.reason ?? 'reason: —'}
                </span>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  )
}
