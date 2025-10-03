import { useMutation, useQuery, useQueryClient, type UseMutationOptions, type UseQueryOptions } from '@tanstack/react-query'

import { apiFetch } from '../../lib/api'
import type {
  AssetCollections,
  DatasetAsset,
  SwarmTemplateAsset,
  SutAsset,
} from '../assets/assets'

const BASE_PATH = '/scenario-manager/scenarios'

type JsonRecord = Record<string, unknown>

const isJsonRecord = (value: unknown): value is JsonRecord =>
  typeof value === 'object' && value !== null

export interface ScenarioSummary {
  id: string
  name: string
  description?: string
}

export interface ScenarioDocument extends AssetCollections {
  id: string
  name: string
  description?: string
}

export interface ScenarioDraft extends AssetCollections {
  id?: string
  requestedId?: string
  name: string
  description?: string
}

const readJson = async <T>(response: Response, fallbackMessage: string): Promise<T> => {
  let payload: unknown = null
  try {
    payload = await response.json()
  } catch {
    payload = null
  }

  if (!response.ok) {
    let message = fallbackMessage
    if (isJsonRecord(payload)) {
      if (typeof payload.message === 'string') {
        message = payload.message
      } else if (Array.isArray(payload.errors) && payload.errors.length > 0) {
        const first = payload.errors[0]
        if (typeof first === 'string') {
          message = first
        } else if (isJsonRecord(first) && typeof first.message === 'string') {
          message = first.message
        }
      }
    }
    throw new Error(message)
  }

  return payload as T
}

const listScenarios = async (): Promise<ScenarioSummary[]> => {
  const response = await apiFetch(`${BASE_PATH}`, {
    headers: { Accept: 'application/json' },
  })
  const data = await readJson<unknown>(response, 'Failed to load scenarios')
  if (!Array.isArray(data)) {
    return []
  }
  return data.filter((item): item is ScenarioSummary => isJsonRecord(item) && typeof item.id === 'string' && typeof item.name === 'string')
}

const getScenario = async (id: string): Promise<ScenarioDocument> => {
  const response = await apiFetch(`${BASE_PATH}/${encodeURIComponent(id)}`, {
    headers: { Accept: 'application/json' },
  })
  const data = await readJson<unknown>(response, 'Failed to load scenario')
  if (!isJsonRecord(data) || typeof data.id !== 'string' || typeof data.name !== 'string') {
    throw new Error('Scenario payload malformed')
  }

  const toAssetArray = <T extends SutAsset | DatasetAsset | SwarmTemplateAsset>(value: unknown): T[] => {
    if (!Array.isArray(value)) {
      return []
    }
    return value.filter((item): item is T => isJsonRecord(item) && typeof item.id === 'string' && typeof item.name === 'string')
  }

  return {
    id: data.id,
    name: data.name,
    description: typeof data.description === 'string' ? data.description : undefined,
    sutAssets: toAssetArray<SutAsset>(data.sutAssets),
    datasetAssets: toAssetArray<DatasetAsset>(data.datasetAssets),
    swarmTemplates: toAssetArray<SwarmTemplateAsset>(data.swarmTemplates),
  }
}

const upsertScenario = async (draft: ScenarioDraft): Promise<ScenarioDocument> => {
  const { id, requestedId, ...rest } = draft
  const method = id ? 'PUT' : 'POST'
  const url = id ? `${BASE_PATH}/${encodeURIComponent(id)}` : BASE_PATH
  const response = await apiFetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify({ id: id ?? requestedId, ...rest }),
  })
  return readJson<ScenarioDocument>(response, 'Failed to save scenario')
}

export const useScenarioList = (
  options?: Omit<UseQueryOptions<ScenarioSummary[], Error>, 'queryKey' | 'queryFn'>,
) =>
  useQuery({
    queryKey: ['scenarios'],
    queryFn: listScenarios,
    staleTime: 30_000,
    ...options,
  })

export const useScenario = (
  id: string | undefined,
  options?: Omit<UseQueryOptions<ScenarioDocument, Error>, 'queryKey' | 'queryFn'>,
) => {
  const { enabled, ...rest } = options ?? {}
  return useQuery({
    queryKey: ['scenarios', id],
    queryFn: () => {
      if (!id) {
        throw new Error('Scenario id required')
      }
      return getScenario(id)
    },
    enabled: Boolean(id) && ((enabled as boolean | undefined) ?? true),
    ...rest,
  })
}

export const useUpsertScenario = (
  options?: Omit<UseMutationOptions<ScenarioDocument, Error, ScenarioDraft>, 'mutationFn'>,
) => {
  const queryClient = useQueryClient()
  const { onSuccess, ...rest } = options ?? {}

  return useMutation({
    mutationFn: upsertScenario,
    onSuccess: async (scenario, ...restArgs) => {
      queryClient.setQueryData(['scenarios', scenario.id], scenario)
      await queryClient.invalidateQueries({ queryKey: ['scenarios'] })
      await onSuccess?.(scenario, ...restArgs)
    },
    ...rest,
  })
}

