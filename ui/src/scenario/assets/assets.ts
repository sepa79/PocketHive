export interface AssetBase {
  id: string
  name: string
  description?: string
}

export interface SutAsset extends AssetBase {
  entrypoint: string
  version: string
}

export interface DatasetAsset extends AssetBase {
  uri: string
  format: string
}

export interface SwarmTemplateAsset extends AssetBase {
  sutId: string
  datasetId: string
  swarmSize: number
}

export type AssetKind = 'sut' | 'dataset' | 'template'

export interface AssetCollections {
  sutAssets: SutAsset[]
  datasetAssets: DatasetAsset[]
  swarmTemplates: SwarmTemplateAsset[]
}

export const emptyCollections = (): AssetCollections => ({
  sutAssets: [],
  datasetAssets: [],
  swarmTemplates: [],
})

export const sortByName = <T extends AssetBase>(items: T[]): T[] =>
  [...items].sort((a, b) => a.name.localeCompare(b.name))
