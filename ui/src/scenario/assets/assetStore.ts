import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import type { StateStorage } from 'zustand/middleware'

import { emptyCollections, sortByName } from './assets'
import type {
  AssetCollections,
  DatasetAsset,
  SwarmTemplateAsset,
  SutAsset,
} from './assets'

const STORAGE_KEY = 'ph.scenario.assets'

const ensureUnique = <T extends SutAsset | DatasetAsset | SwarmTemplateAsset>(
  collection: T[],
  asset: T,
): T[] => {
  const next = collection.some((item) => item.id === asset.id)
    ? collection.map((item) => (item.id === asset.id ? asset : item))
    : [...collection, asset]
  return sortByName(next)
}

interface AssetState {
  sutAssets: SutAsset[]
  datasetAssets: DatasetAsset[]
  swarmTemplates: SwarmTemplateAsset[]
  upsertSut: (asset: SutAsset) => void
  removeSut: (id: string) => void
  upsertDataset: (asset: DatasetAsset) => void
  removeDataset: (id: string) => void
  upsertSwarmTemplate: (asset: SwarmTemplateAsset) => void
  removeSwarmTemplate: (id: string) => void
  reset: () => void
  hydrate: (collections: AssetCollections) => void
}
const memoryStorage: StateStorage = {
  getItem: () => null,
  setItem: () => undefined,
  removeItem: () => undefined,
}

const storage = createJSONStorage(() => {
  if (typeof window === 'undefined' || !window.localStorage) {
    return memoryStorage
  }
  return window.localStorage
})

export const useAssetStore = create<AssetState>()(
  persist(
    (set) => ({
      ...emptyCollections(),
      upsertSut: (asset: SutAsset) =>
        set((state) => ({
          sutAssets: ensureUnique(state.sutAssets, asset),
        })),
      removeSut: (id: string) =>
        set((state) => ({
          sutAssets: state.sutAssets.filter((asset) => asset.id !== id),
          swarmTemplates: state.swarmTemplates.filter((template) => template.sutId !== id),
        })),
      upsertDataset: (asset: DatasetAsset) =>
        set((state) => ({
          datasetAssets: ensureUnique(state.datasetAssets, asset),
        })),
      removeDataset: (id: string) =>
        set((state) => ({
          datasetAssets: state.datasetAssets.filter((asset) => asset.id !== id),
          swarmTemplates: state.swarmTemplates.filter((template) => template.datasetId !== id),
        })),
      upsertSwarmTemplate: (asset: SwarmTemplateAsset) =>
        set((state) => ({
          swarmTemplates: ensureUnique(state.swarmTemplates, {
            ...asset,
            swarmSize: Math.max(1, asset.swarmSize),
          }),
        })),
      removeSwarmTemplate: (id: string) =>
        set((state) => ({
          swarmTemplates: state.swarmTemplates.filter((asset) => asset.id !== id),
        })),
      reset: () => set(() => emptyCollections()),
      hydrate: (collections: AssetCollections) =>
        set(() => ({
          sutAssets: sortByName(collections.sutAssets),
          datasetAssets: sortByName(collections.datasetAssets),
          swarmTemplates: sortByName(
            collections.swarmTemplates.map((template) => ({
              ...template,
              swarmSize: Math.max(1, template.swarmSize),
            })),
          ),
        })),
    }),
    {
      name: STORAGE_KEY,
      storage,
      partialize: (state) => ({
        sutAssets: state.sutAssets,
        datasetAssets: state.datasetAssets,
        swarmTemplates: state.swarmTemplates,
      }),
    },
  ),
)

export type { AssetState }
