import { describe, expect, it } from 'vitest'
import { buildGraph } from './TopologyBuilder'

describe('buildGraph', () => {
  it('centers orchestrator vertically against swarm controllers in fallback layout', () => {
    const graph = buildGraph({
      nodes: [
        { id: 'hive-orchestrator', type: 'orchestrator', swarmId: 'hive' },
        { id: 'sw1-controller', type: 'swarm-controller', swarmId: 'sw1' },
        { id: 'sw2-controller', type: 'swarm-controller', swarmId: 'sw2' },
        { id: 'sw1-generator', type: 'generator', swarmId: 'sw1' },
        { id: 'sw1-processor', type: 'processor', swarmId: 'sw1' },
        { id: 'sw2-generator', type: 'generator', swarmId: 'sw2' },
        { id: 'sw2-processor', type: 'processor', swarmId: 'sw2' },
      ],
      edges: [
        { from: 'hive-orchestrator', to: 'sw1-controller', queue: 'swarm-control' },
        { from: 'hive-orchestrator', to: 'sw2-controller', queue: 'swarm-control' },
        { from: 'sw1-generator', to: 'sw1-processor', queue: 'q1' },
        { from: 'sw2-generator', to: 'sw2-processor', queue: 'q2' },
      ],
    })

    const orchestrator = graph.nodes.find((node) => node.id === 'hive-orchestrator')
    const controllers = graph.nodes.filter((node) => node.type === 'swarm-controller')
    expect(orchestrator).toBeTruthy()
    expect(controllers.length).toBeGreaterThan(0)

    const controllerCenterY =
      controllers.reduce((sum, node) => sum + (node.y ?? 0), 0) / controllers.length

    expect(orchestrator?.y).toBeCloseTo(controllerCenterY, 6)
  })
})
