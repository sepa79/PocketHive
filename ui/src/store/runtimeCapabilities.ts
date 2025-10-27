import { create } from 'zustand'
import type {
  CapabilityManifest,
  RuntimeCapabilitiesByRole,
  RuntimeCapabilitiesByVersion,
  RuntimeCapabilitiesCatalogue,
  RuntimeCapabilityEntry,
} from '../types/capabilities'

interface RuntimeCapabilitiesState {
  catalogue: RuntimeCapabilitiesCatalogue
  replaceCatalogue: (catalogue: RuntimeCapabilitiesCatalogue | null | undefined) => void
  applyControllerSnapshot: (swarmId: string, snapshot: unknown) => void
}

export const useRuntimeCapabilitiesStore = create<RuntimeCapabilitiesState>((set) => ({
  catalogue: {},
  replaceCatalogue: (catalogue) => {
    set({ catalogue: normalizeCatalogue(catalogue) })
  },
  applyControllerSnapshot: (swarmId, snapshot) => {
    if (!swarmId || !swarmId.trim()) return
    const normalized = normalizeRoles(snapshot)
    set((state) => {
      const next = { ...state.catalogue }
      if (!normalized || Object.keys(normalized).length === 0) {
        delete next[swarmId]
        return { catalogue: next }
      }
      next[swarmId] = normalized
      return { catalogue: next }
    })
  },
}))

export function useRuntimeCapabilitiesForSwarm(swarmId: string | null | undefined) {
  return useRuntimeCapabilitiesStore((state) => (swarmId ? state.catalogue[swarmId] : undefined))
}

function normalizeCatalogue(catalogue: RuntimeCapabilitiesCatalogue | null | undefined): RuntimeCapabilitiesCatalogue {
  if (!catalogue || typeof catalogue !== 'object') {
    return {}
  }
  const result: RuntimeCapabilitiesCatalogue = {}
  Object.entries(catalogue).forEach(([swarmId, roles]) => {
    if (!swarmId || !swarmId.trim()) {
      return
    }
    const normalized = normalizeRoles(roles)
    if (normalized && Object.keys(normalized).length > 0) {
      result[swarmId] = normalized
    }
  })
  return result
}

function normalizeRoles(input: unknown): RuntimeCapabilitiesByRole | undefined {
  if (!isRecord(input)) return undefined
  const roleResult: RuntimeCapabilitiesByRole = {}
  Object.entries(input).forEach(([role, versions]) => {
    if (!role || !role.trim()) {
      return
    }
    const normalized = normalizeVersions(versions)
    if (normalized && Object.keys(normalized).length > 0) {
      roleResult[role] = normalized
    }
  })
  return Object.keys(roleResult).length > 0 ? roleResult : undefined
}

function normalizeVersions(input: unknown): RuntimeCapabilitiesByVersion | undefined {
  if (!isRecord(input)) return undefined
  const versionResult: RuntimeCapabilitiesByVersion = {}
  Object.entries(input).forEach(([version, entry]) => {
    if (!version || !version.trim()) {
      return
    }
    const normalized = normalizeEntry(entry)
    if (normalized) {
      versionResult[version] = normalized
    }
  })
  return Object.keys(versionResult).length > 0 ? versionResult : undefined
}

function normalizeEntry(input: unknown): RuntimeCapabilityEntry | undefined {
  if (!isRecord(input)) return undefined
  const manifest = input.manifest
  if (!isRecord(manifest)) {
    return undefined
  }
  const instancesRaw = input.instances
  const instances: string[] = Array.isArray(instancesRaw)
    ? instancesRaw.map((value) => (typeof value === 'string' ? value.trim() : ''))
    : []
  const filteredInstances = instances.filter((value) => value.length > 0)
  const entry: RuntimeCapabilityEntry = {
    manifest: { ...(manifest as CapabilityManifest) },
    instances: filteredInstances,
  }
  const updatedAt = input.updatedAt
  if (typeof updatedAt === 'string' && updatedAt.trim()) {
    entry.updatedAt = updatedAt
  }
  return entry
}

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
