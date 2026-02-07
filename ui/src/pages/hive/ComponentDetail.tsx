import { useEffect, useMemo, useRef, useState } from 'react'
import type { Component } from '../../types/hive'
import { refreshControlPlane, sendConfigUpdate } from '../../lib/orchestratorApi'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'
import WiremockPanel from './WiremockPanel'
import { useCapabilities } from '../../contexts/CapabilitiesContext'
import { Link } from 'react-router-dom'
import type { CapabilityConfigEntry } from '../../types/capabilities'
import { ConfigUpdatePatchModal } from '../../components/ConfigUpdatePatchModal'
import {
  capabilityEntryUiString,
  formatCapabilityValue,
  groupCapabilityConfigEntries,
  matchesCapabilityWhen,
} from '../../lib/capabilities'
import { useSwarmMetadata } from '../../contexts/SwarmMetadataContext'
import { apiFetch } from '../../lib/api'

interface Props {
  component: Component
  onClose: () => void
}

type ConfigFormValue = string | boolean | undefined

const HTTP_WORKER_ROLES = new Set(['processor'])

export default function ComponentDetail({ component, onClose }: Props) {
  const [toast, setToast] = useState<string | null>(null)
  const [configPatchModalOpen, setConfigPatchModalOpen] = useState(false)
  const [showRefreshTooltip, setShowRefreshTooltip] = useState(false)
  const [sutLookup, setSutLookup] = useState<Record<string, { name: string; type: string | null }>>({})
  const [scenarioRunsInput, setScenarioRunsInput] = useState('')
  const { ensureCapabilities, getManifestForImage, manifests } = useCapabilities()
  const { ensureSwarms, refreshSwarms, getBeeImage, getControllerImage, findSwarm } =
    useSwarmMetadata()
  const resolvedImage = useMemo(() => {
    if (component.image) {
      return component.image
    }
    const swarmKey = component.swarmId?.trim()
    if (!swarmKey) {
      return null
    }
    const normalizedRole = component.role?.trim().toLowerCase()
    if (normalizedRole === 'swarm-controller') {
      return getControllerImage(swarmKey)
    }
    return getBeeImage(swarmKey, component.role)
  }, [
    component.image,
    component.role,
    component.swarmId,
    getBeeImage,
    getControllerImage,
  ])
  const roleRaw = component.role ?? ''
  const role = roleRaw.trim() || '—'
  const normalizedRole = roleRaw.trim().toLowerCase()
  const isWiremock = normalizedRole === 'wiremock'
  const componentConfig =
    component.config && typeof component.config === 'object'
      ? (component.config as Record<string, unknown>)
      : undefined
  const scenarioStatus =
    componentConfig && componentConfig.scenario && typeof componentConfig.scenario === 'object'
      ? (componentConfig.scenario as Record<string, unknown>)
      : undefined

  const manifest = useMemo(
    () => getManifestForImage(resolvedImage),
    [resolvedImage, getManifestForImage],
  )
  const refreshTooltipTimer = useRef<number | null>(null)

  useEffect(() => {
    void ensureCapabilities()
  }, [ensureCapabilities])

  useEffect(() => {
    void ensureSwarms()
  }, [ensureSwarms])

  useEffect(() => {
    if (normalizedRole !== 'swarm-controller') {
      setScenarioRunsInput('')
      return
    }
    const totalRuns = getNumber(scenarioStatus?.totalRuns)
    if (totalRuns !== undefined && Number.isFinite(totalRuns)) {
      setScenarioRunsInput(String(Math.max(1, Math.floor(totalRuns))))
    } else {
      setScenarioRunsInput('')
    }
  }, [normalizedRole, scenarioStatus])

  useEffect(() => {
    if (normalizedRole !== 'swarm-controller') {
      return
    }
    if (Object.keys(sutLookup).length > 0) {
      return
    }
    let cancelled = false
    const load = async () => {
      try {
        const response = await apiFetch('/scenario-manager/sut-environments', {
          headers: { Accept: 'application/json' },
        })
        if (!response.ok || cancelled) {
          return
        }
        const data = (await response.json()) as unknown
        if (!Array.isArray(data)) {
          return
        }
        const map: Record<string, { name: string; type: string | null }> = {}
        for (const entry of data) {
          if (!entry || typeof entry !== 'object') continue
          const value = entry as Record<string, unknown>
          const id = typeof value.id === 'string' ? value.id.trim() : ''
          const name = typeof value.name === 'string' ? value.name.trim() : ''
          if (!id || !name) continue
          const type =
            typeof value.type === 'string' && value.type.trim().length > 0
              ? value.type.trim()
              : null
          map[id] = { name, type }
        }
        if (!cancelled) {
          setSutLookup(map)
        }
      } catch {
        // ignore; SUT display is best-effort only
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [normalizedRole, sutLookup])

  const handleRefreshMouseEnter = () => {
    if (refreshTooltipTimer.current != null) {
      return
    }
    refreshTooltipTimer.current = window.setTimeout(() => {
      setShowRefreshTooltip(true)
    }, 5000)
  }

  const handleRefreshMouseLeave = () => {
    if (refreshTooltipTimer.current != null) {
      window.clearTimeout(refreshTooltipTimer.current)
      refreshTooltipTimer.current = null
    }
    setShowRefreshTooltip(false)
  }

  const handleSendConfigPatch = async (patch: Record<string, unknown> | undefined) => {
    setConfigPatchModalOpen(false)
    if (!patch || Object.keys(patch).length === 0) {
      displayToast(setToast, 'No changes to apply')
      return
    }
    try {
      await sendConfigUpdate(component, patch)
      displayToast(setToast, 'Config update sent')
    } catch {
      displayToast(setToast, 'Config update failed')
    }
  }

  const handleScenarioReset = async () => {
    const scenarioPatch: Record<string, unknown> = { reset: true }
    const trimmedRuns = scenarioRunsInput.trim()
    if (trimmedRuns) {
      const parsed = Number(trimmedRuns)
      if (!Number.isInteger(parsed) || parsed < 1) {
        displayToast(setToast, 'Runs must be a positive integer')
        return
      }
      scenarioPatch.runs = parsed
    }
    const payload: Record<string, unknown> = { scenario: scenarioPatch }
    try {
      await sendConfigUpdate(component, payload)
      displayToast(setToast, 'Scenario reset sent')
    } catch {
      displayToast(setToast, 'Scenario reset failed')
    }
  }

  const health = heartbeatHealth(component.lastHeartbeat)

  const runtimeEntries = useMemo(() => {
    const cfg = componentConfig
    if (!cfg) return [] as { label: string; value: string }[]
    const entries: { label: string; value: string }[] = []
    // Orchestrator-specific runtime metadata
    if (normalizedRole === 'orchestrator') {
      const adapter = getString(cfg.computeAdapter)
      if (adapter) {
        entries.push({
          label: 'Compute adapter',
          value: adapter,
        })
      }
    }
    // Swarm-controller specific metadata based on swarm summaries + guard diagnostics
    if (normalizedRole === 'swarm-controller') {
      const swarmSummary = findSwarm(component.swarmId ?? null)
      if (swarmSummary?.templateId) {
        entries.push({
          label: 'Scenario template',
          value: swarmSummary.templateId,
        })
      }
      if (swarmSummary?.sutId) {
        const sutInfo = sutLookup[swarmSummary.sutId]
        if (sutInfo) {
          const labelParts: string[] = []
          if (sutInfo.name) {
            labelParts.push(sutInfo.name)
          }
          if (sutInfo.type) {
            labelParts.push(sutInfo.type)
          }
          if (labelParts.length > 0) {
            entries.push({
              label: 'System under test',
              value: labelParts.join(' • '),
            })
          }
        }
      }
      if (swarmSummary?.stackName) {
        entries.push({
          label: 'Swarm stack',
          value: swarmSummary.stackName,
        })
      }
      const scenario = scenarioStatus
      if (scenario) {
        const elapsedMs = getNumber(scenario.elapsedMillis)
        const lastStepId = getString(scenario.lastStepId)
        const lastStepName = getString(scenario.lastStepName)
        const nextStepId = getString(scenario.nextStepId)
        const nextStepName = getString(scenario.nextStepName)
        const nextDueMs = getNumber(scenario.nextDueMillis)
        const totalRuns = getNumber(scenario.totalRuns)
        const runsRemaining = getNumber(scenario.runsRemaining)
        if (elapsedMs !== undefined) {
          entries.push({
            label: 'Scenario elapsed',
            value: `${(elapsedMs / 1000).toFixed(elapsedMs >= 10_000 ? 0 : 1)}s`,
          })
        }
        if (lastStepId || lastStepName) {
          entries.push({
            label: 'Last step',
            value: `${lastStepId ?? '—'}${lastStepName ? ` (${lastStepName})` : ''}`,
          })
        }
        if (nextStepId || nextStepName) {
          let countdown: string | undefined
          if (elapsedMs !== undefined && nextDueMs !== undefined && nextDueMs > elapsedMs) {
            const remainingSec = (nextDueMs - elapsedMs) / 1000
            countdown = `${remainingSec.toFixed(remainingSec >= 10 ? 0 : 1)}s`
          }
          entries.push({
            label: 'Next step',
            value: `${nextStepId ?? '—'}${nextStepName ? ` (${nextStepName})` : ''}${
              countdown ? ` in ${countdown}` : ''
            }`,
          })
        }
        if (totalRuns !== undefined) {
          entries.push({
            label: 'Scenario runs',
            value: totalRuns.toString(),
          })
        }
        if (runsRemaining !== undefined) {
          entries.push({
            label: 'Runs remaining',
            value: runsRemaining.toString(),
          })
        }
      }
      const guardStatus =
        cfg && cfg.bufferGuard && typeof cfg.bufferGuard === 'object'
          ? (cfg.bufferGuard as Record<string, unknown>)
          : undefined
      const trafficPolicyRoot =
        cfg && cfg.trafficPolicy && typeof cfg.trafficPolicy === 'object'
          ? (cfg.trafficPolicy as Record<string, unknown>)
          : undefined
      const guardConfig =
        trafficPolicyRoot &&
        trafficPolicyRoot.bufferGuard &&
        typeof trafficPolicyRoot.bufferGuard === 'object'
          ? (trafficPolicyRoot.bufferGuard as Record<string, unknown>)
          : undefined
      if (guardStatus || guardConfig) {
        const guardActive = guardStatus ? getBoolean(guardStatus.active) : undefined
        const guardProblem = guardStatus ? getString(guardStatus.problem) : undefined
        if (guardActive !== undefined) {
          entries.push({
            label: 'Buffer guard',
            value: guardActive ? 'active' : 'inactive',
          })
        }
        if (guardConfig) {
          const queueAlias = getString(guardConfig.queueAlias)
          const targetDepth = getNumber(guardConfig.targetDepth)
          const minDepth = getNumber(guardConfig.minDepth)
          const maxDepth = getNumber(guardConfig.maxDepth)
          const adjust =
            guardConfig.adjust && typeof guardConfig.adjust === 'object'
              ? (guardConfig.adjust as Record<string, unknown>)
              : undefined
          const minRate = adjust ? getNumber(adjust.minRatePerSec) : undefined
          const maxRate = adjust ? getNumber(adjust.maxRatePerSec) : undefined
          const backpressureCfg =
            guardConfig.backpressure && typeof guardConfig.backpressure === 'object'
              ? (guardConfig.backpressure as Record<string, unknown>)
              : undefined
          const backpressureQueue = backpressureCfg ? getString(backpressureCfg.queueAlias) : undefined
          const highDepth = backpressureCfg ? getNumber(backpressureCfg.highDepth) : undefined
          const recoveryDepth = backpressureCfg ? getNumber(backpressureCfg.recoveryDepth) : undefined

          if (queueAlias) {
            entries.push({
              label: 'Guarded queue',
              value: queueAlias,
            })
          }
          if (minDepth !== undefined || maxDepth !== undefined || targetDepth !== undefined) {
            const parts: string[] = []
            if (minDepth !== undefined && maxDepth !== undefined) {
              parts.push(`depth ${minDepth}..${maxDepth}`)
            }
            if (targetDepth !== undefined) {
              parts.push(`target ${targetDepth}`)
            }
            entries.push({
              label: 'Guard depth',
              value: parts.join(' '),
            })
          }
          if (minRate !== undefined || maxRate !== undefined) {
            const range =
              minRate !== undefined && maxRate !== undefined
                ? `${minRate}..${maxRate}`
                : minRate !== undefined
                ? `${minRate}`
                : `${maxRate}`
            entries.push({
              label: 'Guard rate (msg/s)',
              value: range,
            })
          }
          if (backpressureQueue || highDepth !== undefined || recoveryDepth !== undefined) {
            const parts: string[] = []
            if (backpressureQueue) {
              parts.push(`queue ${backpressureQueue}`)
            }
            if (highDepth !== undefined) {
              parts.push(`high ${highDepth}`)
            }
            if (recoveryDepth !== undefined) {
              parts.push(`recovery ${recoveryDepth}`)
            }
            entries.push({
              label: 'Guard backpressure',
              value: parts.join(' '),
            })
          }
        }
        if (guardProblem) {
          entries.push({
            label: 'Guard problem',
            value: guardProblem,
          })
        }
      }
    }
    // HTTP workers: show SUT binding if present
    if (HTTP_WORKER_ROLES.has(normalizedRole)) {
      const swarmSummary = findSwarm(component.swarmId ?? null)
      if (swarmSummary?.sutId) {
        entries.push({
          label: 'System under test',
          value: swarmSummary.sutId,
        })
      }
    }
    const tps = getNumber(cfg.tps)
    const intervalSeconds = getNumber(cfg.intervalSeconds)
    const transactions = getNumber(cfg.transactions)
    const successRatio = getNumber(cfg.successRatio)
    const avgLatencyMs = getNumber(cfg.avgLatencyMs)
    const httpMode = getString(cfg.httpMode)
    const httpThreadCount = getNumber(cfg.httpThreadCount)
    const httpMaxConnections = getNumber(cfg.httpMaxConnections)
    if (tps !== undefined) {
      entries.push({ label: 'TPS', value: Math.round(tps).toString() })
    }
    if (intervalSeconds !== undefined && intervalSeconds > 0) {
      entries.push({
        label: 'Interval (s)',
        value: intervalSeconds.toFixed(intervalSeconds >= 10 ? 0 : 1),
      })
    }
    if (transactions !== undefined) {
      entries.push({ label: 'Transactions', value: transactions.toString() })
    }
    if (successRatio !== undefined) {
      entries.push({
        label: 'Success ratio',
        value: successRatio.toFixed(3),
      })
    }
    if (avgLatencyMs !== undefined) {
      entries.push({
        label: 'Avg latency (ms)',
        value: avgLatencyMs.toFixed(1),
      })
    }
    if (httpMode) {
      entries.push({ label: 'HTTP mode', value: httpMode })
    }
    if (httpThreadCount !== undefined) {
      entries.push({
        label: 'HTTP thread count',
        value: httpThreadCount.toString(),
      })
    }
    if (httpMaxConnections !== undefined) {
      entries.push({
        label: 'HTTP max connections',
        value: httpMaxConnections.toString(),
      })
    }
    // Scheduler finite-run/runtime config (if present)
    const inputs =
      cfg && cfg.inputs && typeof cfg.inputs === 'object'
        ? (cfg.inputs as Record<string, unknown>)
        : undefined
    if (inputs) {
      const inputType = getString(inputs.type)
      if (inputType) {
        entries.push({
          label: 'Input type',
          value: inputType,
        })
      }
      const outputs =
        cfg && cfg.outputs && typeof cfg.outputs === 'object'
          ? (cfg.outputs as Record<string, unknown>)
          : undefined
      const outputType = outputs ? getString(outputs.type) : undefined
      if (outputType) {
        entries.push({
          label: 'Output type',
          value: outputType,
        })
      }
    }
    const scheduler =
      inputs && inputs.scheduler && typeof inputs.scheduler === 'object'
        ? (inputs.scheduler as Record<string, unknown>)
        : undefined
    const schedRate = scheduler ? getNumber(scheduler.ratePerSec) : undefined
    const schedMax = scheduler ? getNumber(scheduler.maxMessages) : undefined
    if (schedRate !== undefined) {
      entries.push({
        label: 'Scheduler rate (msg/s)',
        value: schedRate.toString(),
      })
    }
    if (schedMax !== undefined && schedMax > 0) {
      entries.push({
        label: 'Scheduler max messages',
        value: schedMax.toString(),
      })
    }
    // Scheduler runtime diagnostics (if present in status data)
    const schedulerDiag =
      cfg && cfg.scheduler && typeof cfg.scheduler === 'object'
        ? (cfg.scheduler as Record<string, unknown>)
        : undefined
    if (schedulerDiag) {
      const dispatched = getNumber(schedulerDiag.dispatched)
      const remaining = getNumber(schedulerDiag.remaining)
      const exhausted = getBoolean(schedulerDiag.exhausted)
      if (dispatched !== undefined) {
        entries.push({
          label: 'Scheduler dispatched',
          value: dispatched.toString(),
        })
      }
      if (remaining !== undefined && remaining >= 0) {
        entries.push({
          label: 'Scheduler remaining',
          value: remaining.toString(),
        })
      }
      if (exhausted !== undefined) {
        entries.push({
          label: 'Scheduler exhausted',
          value: exhausted ? 'true' : 'false',
        })
      }
    }
    // Redis dataset runtime diagnostics (if present in status data)
    const redisDiag =
      cfg && cfg.redisDataset && typeof cfg.redisDataset === 'object'
        ? (cfg.redisDataset as Record<string, unknown>)
        : undefined
    if (redisDiag) {
      const listName = getString(redisDiag.listName)
      const rate = getNumber(redisDiag.ratePerSec)
      const dispatched = getNumber(redisDiag.dispatched)
      const lastPopAt = getString(redisDiag.lastPopAt)
      const lastEmptyAt = getString(redisDiag.lastEmptyAt)
      const lastErrorAt = getString(redisDiag.lastErrorAt)
      const lastErrorMessage = getString(redisDiag.lastErrorMessage)
      if (listName) {
        entries.push({
          label: 'Redis list',
          value: listName,
        })
      }
      if (rate !== undefined) {
        entries.push({
          label: 'Redis rate (msg/s)',
          value: rate.toString(),
        })
      }
      if (dispatched !== undefined) {
        entries.push({
          label: 'Redis dispatched',
          value: dispatched.toString(),
        })
      }
      if (lastPopAt) {
        entries.push({
          label: 'Last Redis pop at',
          value: lastPopAt,
        })
      }
      if (lastEmptyAt) {
        entries.push({
          label: 'Last Redis empty at',
          value: lastEmptyAt,
        })
      }
      if (lastErrorAt) {
        entries.push({
          label: 'Last Redis error at',
          value: lastErrorAt,
        })
      }
      if (lastErrorMessage) {
        entries.push({
          label: 'Last Redis error',
          value: lastErrorMessage,
        })
      }
    }
    // CSV dataset runtime diagnostics (if present in status data)
    const csvDiag =
      cfg && cfg.csvDataset && typeof cfg.csvDataset === 'object'
        ? (cfg.csvDataset as Record<string, unknown>)
        : undefined
    if (csvDiag) {
      const filePath = getString(csvDiag.filePath)
      const rate = getNumber(csvDiag.ratePerSec)
      const dispatched = getNumber(csvDiag.dispatched)
      const remaining = getNumber(csvDiag.remaining)
      const exhausted = getBoolean(csvDiag.exhausted)
      const rotate = getBoolean(csvDiag.rotate)
      const rowCount = getNumber(csvDiag.rowCount)
      if (filePath) {
        entries.push({
          label: 'CSV file',
          value: filePath,
        })
      }
      if (rate !== undefined) {
        entries.push({
          label: 'CSV rate (msg/s)',
          value: rate.toString(),
        })
      }
      if (rowCount !== undefined) {
        entries.push({
          label: 'CSV rows',
          value: rowCount.toString(),
        })
      }
      if (dispatched !== undefined) {
        entries.push({
          label: 'CSV dispatched',
          value: dispatched.toString(),
        })
      }
      if (remaining !== undefined && remaining >= 0) {
        entries.push({
          label: 'CSV remaining',
          value: remaining.toString(),
        })
      }
      if (rotate !== undefined) {
        entries.push({
          label: 'CSV rotate',
          value: rotate ? 'true' : 'false',
        })
      }
      if (exhausted !== undefined) {
        entries.push({
          label: 'CSV exhausted',
          value: exhausted ? 'true' : 'false',
        })
      }
    }
    return entries
  }, [component.config, normalizedRole, findSwarm, component.swarmId, sutLookup, scenarioStatus])

  const effectiveConfigEntries: CapabilityConfigEntry[] = useMemo(() => {
    if (!manifest) return []
    const baseEntries = manifest.config ?? []

    const cfg = componentConfig
    const inputs =
      cfg && cfg.inputs && typeof cfg.inputs === 'object'
        ? (cfg.inputs as Record<string, unknown>)
        : undefined
    const inputTypeRaw = typeof inputs?.type === 'string' ? inputs.type.trim().toUpperCase() : undefined
    const inferredIoType =
      inputs && !inputTypeRaw
        ? inputs.scheduler && typeof inputs.scheduler === 'object'
          ? 'SCHEDULER'
          : inputs.redis && typeof inputs.redis === 'object'
            ? 'REDIS_DATASET'
            : undefined
        : undefined
    const inputType = inputTypeRaw ?? inferredIoType

    const allEntries: CapabilityConfigEntry[] = [...baseEntries]

    // Merge IO capabilities for the current input type, if any
    if (inputType) {
      const ioEntries: CapabilityConfigEntry[] = []
      for (const m of manifests) {
        const ui = m.ui as Record<string, unknown> | undefined
        const ioTypeRaw = ui && typeof ui.ioType === 'string' ? ui.ioType : undefined
        const ioType = ioTypeRaw ? ioTypeRaw.trim().toUpperCase() : undefined
        if (ioType && ioType === inputType && Array.isArray(m.config)) {
          ioEntries.push(...m.config)
        }
      }
      allEntries.push(...ioEntries)
    }

    // De-duplicate by config name (first entry wins)
    const byName = new Map<string, CapabilityConfigEntry>()
    for (const entry of allEntries) {
      if (!byName.has(entry.name)) {
        byName.set(entry.name, entry)
      }
    }

    return Array.from(byName.values())
  }, [manifest, manifests, componentConfig])

  const displayForm = useMemo(() => {
    const cfg = isRecord(componentConfig) ? componentConfig : undefined
    const next: Record<string, ConfigFormValue> = {}
    effectiveConfigEntries.forEach((entry) => {
      next[entry.name] = computeInitialValue(entry, cfg)
    })
    return next
  }, [componentConfig, effectiveConfigEntries])

  const visibleConfigEntries = useMemo(() => {
    const cfg = isRecord(componentConfig) ? componentConfig : undefined
    const manifestUi = manifest?.ui as Record<string, unknown> | undefined
    const hideIo = manifestUi?.hideIo === true
    const resolveWhenValue = (path: string): unknown => {
      if (path in displayForm) {
        return displayForm[path]
      }
      return getValueForPath(cfg, path)
    }
    return effectiveConfigEntries
      .filter((entry) => matchesCapabilityWhen(entry.when, resolveWhenValue))
      .filter((entry) => {
        if (!hideIo) {
          return true
        }
        return entry.name !== 'inputs.type' && entry.name !== 'outputs.type'
      })
  }, [effectiveConfigEntries, componentConfig, displayForm, manifest])

  const groupedConfigEntries = useMemo(
    () => groupCapabilityConfigEntries(visibleConfigEntries),
    [visibleConfigEntries],
  )
  const [activeConfigGroup, setActiveConfigGroup] = useState<string>('General')
  useEffect(() => {
    if (groupedConfigEntries.length === 0) {
      return
    }
    if (!groupedConfigEntries.some((group) => group.id === activeConfigGroup)) {
      setActiveConfigGroup(groupedConfigEntries[0]!.id)
    }
  }, [groupedConfigEntries, activeConfigGroup])

  const renderedContent = isWiremock ? (
    <WiremockPanel component={component} />
  ) : manifest ? (
    <div className="space-y-2">
      {visibleConfigEntries.length === 0 ? (
        <div className="text-white/50">No configurable options</div>
      ) : (
        <>
          {groupedConfigEntries.length > 1 && (
            <div className="flex flex-wrap gap-2 pb-2">
              {groupedConfigEntries.map((group) => (
                <button
                  key={group.id}
                  type="button"
                  className={
                    group.id === activeConfigGroup
                      ? 'rounded border border-white/30 bg-white/10 px-2 py-1 text-xs text-white'
                      : 'rounded border border-white/10 px-2 py-1 text-xs text-white/70 hover:bg-white/5'
                  }
                  onClick={() => setActiveConfigGroup(group.id)}
                >
                  {group.label}
                </button>
              ))}
            </div>
          )}
          {groupedConfigEntries
            .filter((group) => groupedConfigEntries.length === 1 || group.id === activeConfigGroup)
            .map((group) => (
              <div key={group.id} className="space-y-2">
                {groupedConfigEntries.length === 1 && group.label !== 'General' && (
                  <div className="text-xs text-white/70">{group.label}</div>
                )}
                {group.entries.map((entry) => (
                  <ReadOnlyConfigEntryRow
                    key={entry.name}
                    entry={entry}
                    value={displayForm[entry.name]}
                  />
                ))}
              </div>
            ))}
        </>
      )}
    </div>
  ) : (
    <div className="text-white/60">Capability manifest not available for this component.</div>
  )
  const containerClass = isWiremock
    ? 'mb-4'
    : 'p-4 border border-white/10 rounded mb-4 text-sm text-white/60 space-y-2'

  return (
    <div className="flex-1 p-4 overflow-y-auto relative">
      <button
        className="absolute top-2 right-2"
        onClick={() => {
          setConfigPatchModalOpen(false)
          onClose()
        }}
      >
        ×
      </button>
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-xl flex items-center gap-2">
          {component.id}
          <span className={`h-3 w-3 rounded-full ${colorForHealth(health)}`} />
        </h2>
        {normalizedRole === 'orchestrator' && (
          <div className="relative">
            <button
              className="rounded border border-white/20 px-2 py-0.5 text-xs text-white/80 hover:bg-white/10"
              onClick={async () => {
                try {
                  await refreshControlPlane()
                  await refreshSwarms()
                  displayToast(setToast, 'Control plane refresh requested')
                } catch {
                  displayToast(setToast, 'Failed to refresh control plane')
                } finally {
                  handleRefreshMouseLeave()
                }
              }}
              onMouseEnter={handleRefreshMouseEnter}
              onMouseLeave={handleRefreshMouseLeave}
            >
              Refresh swarms
            </button>
            {showRefreshTooltip && (
              <div className="absolute right-0 mt-1 w-64 rounded border border-white/20 bg-black/80 p-2 text-[11px] text-white/90 z-10">
                Requests a fresh round of `status-full` snapshots from control-plane components.
              </div>
            )}
          </div>
        )}
      </div>
      <div className="text-sm text-white/60 mb-3">{role}</div>
      <div className="space-y-1 text-sm mb-4">
        <div>Version: {component.version ?? '—'}</div>
        <div>
          Started:{' '}
          {typeof component.startedAt === 'number'
            ? new Date(component.startedAt).toLocaleString()
            : '—'}
        </div>
        <div>Last heartbeat: {timeAgo(component.lastHeartbeat)}</div>
        <div>
          Enabled:{' '}
          {component.config?.enabled === false
            ? 'false'
            : component.config?.enabled === true
            ? 'true'
            : '—'}
        </div>
        {normalizedRole === 'swarm-controller' && (() => {
          const swarm = findSwarm(component.swarmId ?? null)
          if (!swarm?.sutId) return null
          const sut = sutLookup[swarm.sutId]
          const labelParts: string[] = []
          if (sut?.name) labelParts.push(sut.name)
          if (sut?.type) labelParts.push(sut.type)
          const label = labelParts.join(' • ')
          if (!label) return null
          return (
            <div className="pt-1 text-xs">
              SUT:{' '}
              <Link
                to={`/sut?sutId=${encodeURIComponent(swarm.sutId)}`}
                className="text-sky-300 hover:text-sky-200 underline-offset-2 hover:underline"
              >
                {label}
              </Link>
            </div>
          )
        })()}
      </div>
      {runtimeEntries.length > 0 && (
        <div className="space-y-1 text-sm mb-4">
          <div className="text-white/70 font-semibold">Runtime</div>
          {runtimeEntries.map((entry) => (
            <div key={entry.label} className="flex justify-between">
              <span className="text-white/60">{entry.label}</span>
              <span className="text-white/90">{entry.value}</span>
            </div>
          ))}
        </div>
      )}
      {normalizedRole === 'swarm-controller' && (
        <div className="space-y-2 mb-4 text-sm">
          <div className="text-white/70 font-semibold">Scenario controls</div>
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-white/70">
              <span>Runs</span>
              <input
                className="w-20 rounded bg-white/10 px-2 py-1 text-white"
                type="number"
                min={1}
                step={1}
                value={scenarioRunsInput}
                onChange={(event) => setScenarioRunsInput(event.target.value)}
              />
            </label>
            <button
              className="rounded bg-blue-600 px-3 py-1 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
              onClick={handleScenarioReset}
            >
              Reset plan
            </button>
          </div>
        </div>
      )}
      {!isWiremock && effectiveConfigEntries.length > 0 && (
        <div className="mb-2 flex items-center justify-between text-xs text-white/60">
          <span className="uppercase tracking-wide text-white/50">Configuration</span>
          <button
            type="button"
            className="rounded border border-white/20 bg-white/5 px-2 py-1 text-xs text-white/80 hover:bg-white/10"
            onClick={() => {
              if (!manifest) {
                displayToast(setToast, 'Capability manifest not available for this component')
                return
              }
              setConfigPatchModalOpen(true)
            }}
          >
            Edit patch…
          </button>
        </div>
      )}
      <div className={containerClass}>{renderedContent}</div>
      {configPatchModalOpen && manifest && (
        <ConfigUpdatePatchModal
          open
          imageLabel={resolvedImage ?? component.image ?? '(unknown)'}
          entries={effectiveConfigEntries.filter((entry) => {
            const manifestUi = manifest.ui as Record<string, unknown> | undefined
            const hideIo = manifestUi?.hideIo === true
            if (!hideIo) return true
            return entry.name !== 'inputs.type' && entry.name !== 'outputs.type'
          })}
          baseConfig={componentConfig}
          existingPatch={undefined}
          onClose={() => setConfigPatchModalOpen(false)}
          onApply={(patch) => {
            void handleSendConfigPatch(patch)
          }}
        />
      )}
      {toast && (
        <div className="fixed bottom-4 right-4 bg-black/80 text-white px-4 py-2 rounded">
          {toast}
        </div>
      )}
      {!isWiremock && (
        <>
          <h3 className="text-lg mb-2">Queues</h3>
          <QueuesPanel queues={component.queues} />
        </>
      )}
    </div>
  )
}

interface ConfigEntryRowProps {
  entry: CapabilityConfigEntry
  value: ConfigFormValue
}

function formatReadOnlyValue(value: ConfigFormValue): string {
  if (value === undefined || value === null) return '—'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (value.trim() === '') return '—'
  return value
}

function ReadOnlyConfigEntryRow({ entry, value }: ConfigEntryRowProps) {
  const unit = extractUnit(entry)
  const typeLabel = entry.type || 'string'
  const labelSuffix = `${typeLabel}${unit ? ` • ${unit}` : ''}`
  const label = capabilityEntryUiString(entry, 'label') ?? entry.name
  const help = capabilityEntryUiString(entry, 'help')
  const showPath = label !== entry.name
  const normalizedType = (entry.type || '').toLowerCase()
  const isMultiLine = entry.multiline || normalizedType === 'text' || normalizedType === 'json'
  const displayValue = formatReadOnlyValue(value)
  return (
    <div className="block space-y-1 rounded border border-white/10 bg-white/5 px-3 py-2">
      <div className="space-y-0.5">
        <div className="flex items-baseline justify-between gap-3">
          <span className="text-white/85 font-medium">{label}</span>
          <span className="text-[11px] text-white/45">{labelSuffix}</span>
        </div>
        {showPath && <div className="text-[11px] text-white/40">{entry.name}</div>}
        {help && <div className="text-[11px] text-white/50">{help}</div>}
      </div>
      <div
        className={
          isMultiLine
            ? 'w-full rounded bg-black/30 px-2 py-1 text-[11px] text-white/90 font-mono whitespace-pre-wrap break-words max-h-32 overflow-auto'
            : 'w-full rounded bg-black/30 px-2 py-1 text-[11px] text-white/90 font-mono whitespace-pre-wrap break-words'
        }
      >
        {displayValue}
      </div>
      {typeof entry.min === 'number' || typeof entry.max === 'number' ? (
        <span className="block text-[11px] text-white/40">
          {typeof entry.min === 'number' ? `min ${entry.min}` : ''}
          {typeof entry.min === 'number' && typeof entry.max === 'number' ? ' · ' : ''}
          {typeof entry.max === 'number' ? `max ${entry.max}` : ''}
        </span>
      ) : null}
      {((entry.type || '').toLowerCase() === 'json' || entry.multiline) && (
        <span className="block text-[11px] text-white/40">
          {((entry.type || '').toLowerCase() === 'json'
            ? 'Value must be valid JSON.'
            : 'Multiline input supported.')}
        </span>
      )}
    </div>
  )
}

function computeInitialValue(
  entry: CapabilityConfigEntry,
  config: Record<string, unknown> | undefined,
): ConfigFormValue {
  const existing = getValueForPath(config, entry.name)
  const source = existing !== undefined ? existing : entry.default
  return formatValueForInput(entry, source)
}

function getValueForPath(
  config: Record<string, unknown> | undefined,
  path: string,
): unknown {
  if (!config || !path) return undefined
  const segments = path.split('.').filter((segment) => segment.length > 0)
  let current: unknown = config
  for (const segment of segments) {
    if (!isRecord(current)) {
      return undefined
    }
    current = current[segment]
  }
  return current
}

function formatValueForInput(entry: CapabilityConfigEntry, value: unknown): ConfigFormValue {
  const normalizedType = (entry.type || '').toLowerCase()
  if (normalizedType === 'boolean' || normalizedType === 'bool') {
    return coerceBoolean(value)
  }
  if (normalizedType === 'json') {
    return formatJsonValue(value)
  }
  if (normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer') {
    if (typeof value === 'number') return Number.isFinite(value) ? String(value) : ''
    if (typeof value === 'string') return value
    return ''
  }
  return formatCapabilityValue(value)
}

function coerceBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return false
}

function formatJsonValue(value: unknown): string {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return ''
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return ''
  }
}

function getNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return undefined
}

function getString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

function getBoolean(value: unknown): boolean | undefined {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return undefined
}

function displayToast(setter: (value: string | null) => void, message: string) {
  setter(message)
  window.setTimeout(() => setter(null), 3000)
}

function extractUnit(entry: CapabilityConfigEntry): string | null {
  if (!entry.ui || typeof entry.ui !== 'object') return null
  const value = (entry.ui as Record<string, unknown>).unit
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function timeAgo(ts: number) {
  const diff = Math.floor((Date.now() - ts) / 1000)
  return `${diff}s ago`
}
