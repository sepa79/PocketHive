import { beforeEach, describe, expect, it } from 'vitest'

import { useAssetStore } from './assetStore'

const resetStore = () => {
  useAssetStore.getState().reset()
  if (typeof window !== 'undefined' && window.localStorage) {
    window.localStorage.removeItem('ph.scenario.assets')
  }
}

describe('asset store', () => {
  beforeEach(() => {
    resetStore()
  })

  it('creates and updates systems under test', () => {
    const { upsertSut } = useAssetStore.getState()

    upsertSut({ id: 'sut-1', name: 'Primary', description: 'demo', entrypoint: 'image:latest', version: 'v1' })
    upsertSut({ id: 'sut-2', name: 'Secondary', description: 'demo 2', entrypoint: 'image:v2', version: 'v2' })

    expect(useAssetStore.getState().sutAssets).toHaveLength(2)
    expect(useAssetStore.getState().sutAssets[0]?.id).toBe('sut-1')

    upsertSut({ id: 'sut-1', name: 'Primary updated', entrypoint: 'image:v3', version: 'v3' })
    expect(useAssetStore.getState().sutAssets.find((sut) => sut.id === 'sut-1')).toMatchObject({
      name: 'Primary updated',
      entrypoint: 'image:v3',
      version: 'v3',
    })
  })

  it('creates and updates datasets', () => {
    const { upsertDataset } = useAssetStore.getState()

    upsertDataset({ id: 'dataset-1', name: 'Dataset', description: 'docs', uri: 's3://bucket', format: 'json' })
    upsertDataset({ id: 'dataset-2', name: 'Another', description: 'docs', uri: 'file://local', format: 'csv' })

    expect(useAssetStore.getState().datasetAssets).toHaveLength(2)

    upsertDataset({ id: 'dataset-2', name: 'Updated', uri: 'file://new', format: 'csv' })
    expect(useAssetStore.getState().datasetAssets.find((dataset) => dataset.id === 'dataset-2')).toMatchObject({
      name: 'Updated',
      uri: 'file://new',
    })
  })

  it('removes dependent templates when removing assets', () => {
    const {
      upsertSut,
      upsertDataset,
      upsertSwarmTemplate,
      removeSut,
      removeDataset,
    } = useAssetStore.getState()

    upsertSut({ id: 'sut-1', name: 'Primary', entrypoint: 'image', version: 'v1' })
    upsertDataset({ id: 'dataset-1', name: 'Dataset', uri: 's3://bucket', format: 'json' })
    upsertSwarmTemplate({
      id: 'template-1',
      name: 'Template',
      sutId: 'sut-1',
      datasetId: 'dataset-1',
      swarmSize: 3,
    })

    expect(useAssetStore.getState().swarmTemplates).toHaveLength(1)

    removeSut('sut-1')
    expect(useAssetStore.getState().swarmTemplates).toHaveLength(0)

    upsertSut({ id: 'sut-1', name: 'Primary', entrypoint: 'image', version: 'v1' })
    upsertDataset({ id: 'dataset-1', name: 'Dataset', uri: 's3://bucket', format: 'json' })
    upsertSwarmTemplate({
      id: 'template-1',
      name: 'Template',
      sutId: 'sut-1',
      datasetId: 'dataset-1',
      swarmSize: 3,
    })

    removeDataset('dataset-1')
    expect(useAssetStore.getState().swarmTemplates).toHaveLength(0)
  })

  it('normalises swarm size and updates templates', () => {
    const { upsertSut, upsertDataset, upsertSwarmTemplate } = useAssetStore.getState()

    upsertSut({ id: 'sut-1', name: 'Primary', entrypoint: 'image', version: 'v1' })
    upsertDataset({ id: 'dataset-1', name: 'Dataset', uri: 's3://bucket', format: 'json' })

    upsertSwarmTemplate({
      id: 'template-1',
      name: 'Template',
      sutId: 'sut-1',
      datasetId: 'dataset-1',
      swarmSize: 0,
    })

    expect(useAssetStore.getState().swarmTemplates[0]?.swarmSize).toBe(1)

    upsertSwarmTemplate({
      id: 'template-1',
      name: 'Template updated',
      sutId: 'sut-1',
      datasetId: 'dataset-1',
      swarmSize: 5,
    })

    expect(useAssetStore.getState().swarmTemplates[0]).toMatchObject({
      name: 'Template updated',
      swarmSize: 5,
    })
  })

  it('hydrates state from server payloads', () => {
    const { hydrate } = useAssetStore.getState()

    hydrate({
      sutAssets: [
        { id: 'sut-b', name: 'Beta', entrypoint: 'img:b', version: '1.0' },
        { id: 'sut-a', name: 'Alpha', entrypoint: 'img:a', version: '1.0' },
      ],
      datasetAssets: [
        { id: 'data-b', name: 'B dataset', uri: 's3://b', format: 'json' },
        { id: 'data-a', name: 'A dataset', uri: 's3://a', format: 'csv' },
      ],
      swarmTemplates: [
        {
          id: 'template-b',
          name: 'Template B',
          sutId: 'sut-b',
          datasetId: 'data-b',
          swarmSize: 0,
        },
        {
          id: 'template-a',
          name: 'Template A',
          sutId: 'sut-a',
          datasetId: 'data-a',
          swarmSize: 2,
        },
      ],
    })

    const state = useAssetStore.getState()
    expect(state.sutAssets.map((sut) => sut.name)).toEqual(['Alpha', 'Beta'])
    expect(state.datasetAssets.map((dataset) => dataset.name)).toEqual(['A dataset', 'B dataset'])
    expect(state.swarmTemplates[0]).toMatchObject({ name: 'Template A', swarmSize: 2 })
    expect(state.swarmTemplates[1]).toMatchObject({ name: 'Template B', swarmSize: 1 })
  })
})
