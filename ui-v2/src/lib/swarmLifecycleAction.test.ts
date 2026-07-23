import { describe, expect, it } from 'vitest'
import type { ControlPlaneEnvelope } from './controlPlane/types'
import type { WireLogEntry } from './controlPlane/wireLogStore'
import {
  parseSwarmLifecycleControlResponse,
  pendingSwarmLifecycleFeedback,
  resolveSwarmLifecycleFeedback,
  resolveSwarmLifecycleOperationFeedback,
} from './swarmLifecycleAction'

const ACCEPTED_AT = Date.parse('2026-07-13T12:00:00Z')

describe('swarm lifecycle action feedback', () => {
  it('completes from the authoritative operation resource', () => {
    const feedback = pending('start')
    const resolved = resolveSwarmLifecycleOperationFeedback(feedback, {
      correlationId: feedback.correlationId,
      state: 'SUCCEEDED',
    })
    expect(resolved.status).toBe('success')
  })

  it('rejects an operation resource for another correlation', () => {
    expect(() => resolveSwarmLifecycleOperationFeedback(pending('stop'), {
      correlationId: 'another-operation',
      state: 'SUCCEEDED',
    })).toThrow('correlationId')
  })

  it('requires the canonical operation and outcome contract', () => {
    expect(
      parseSwarmLifecycleControlResponse({
        correlationId: 'corr-1',
        idempotencyKey: 'idem-1',
        operationUrl: '/api/swarms/demo/operations/corr-1',
        outcomeTopic: 'event.outcome.swarm-stop.demo.orchestrator.orch-1',
        timeoutMs: 90_000,
      }),
    ).toMatchObject({ correlationId: 'corr-1', timeoutMs: 90_000 })

    expect(() =>
      parseSwarmLifecycleControlResponse({
        correlationId: 'corr-1',
        idempotencyKey: 'idem-1',
        timeoutMs: 90_000,
        outcomeTopic: 'event.outcome.swarm-stop.demo.orchestrator.orch-1',
      }),
    ).toThrow('operationUrl')
  })

  it('explains a NotReady outcome using its structured context', () => {
    const feedback = pending('start')
    const resolved = resolveSwarmLifecycleFeedback(
      feedback,
      [entry(envelope('outcome', 'swarm-start', { status: 'Rejected', context: { initialized: false, ready: false, pendingConfigUpdates: true } }))],
      ACCEPTED_AT + 1000,
    )

    expect(resolved.status).toBe('error')
    expect(resolved.message).toContain('initialization is incomplete')
    expect(resolved.message).toContain('workers are not ready')
    expect(resolved.message).toContain('configuration updates are still pending')
  })

  it('surfaces terminal timeout outcomes emitted by the orchestrator', () => {
    const feedback = pending('stop')
    const resolved = resolveSwarmLifecycleFeedback(
      feedback,
      [entry(envelope('outcome', 'swarm-stop', { status: 'TimedOut' }, 'orchestrator'))],
      ACCEPTED_AT + 1000,
    )

    expect(resolved).toMatchObject({
      status: 'error',
      code: 'TimedOut',
      message: "Could not stop swarm 'demo': the control plane reported a failed outcome.",
    })
  })

  it('accepts only the orchestrator terminal outcome as success', () => {
    const resolved = resolveSwarmLifecycleFeedback(
      pending('stop'),
      [entry(envelope('outcome', 'swarm-stop', { status: 'Succeeded' }, 'orchestrator'))],
      ACCEPTED_AT + 1000,
    )
    expect(resolved.status).toBe('success')
  })

  it('reports local expiry when no terminal event arrives', () => {
    const feedback = pending('stop')
    const resolved = resolveSwarmLifecycleFeedback(feedback, [], feedback.deadlineAt)

    expect(resolved.status).toBe('error')
    expect(resolved.message).toContain('within 90 seconds')
  })
})

function pending(action: 'start' | 'stop') {
  return pendingSwarmLifecycleFeedback(
    'demo',
    action,
    {
      correlationId: 'corr-1',
      idempotencyKey: 'idem-1',
      operationUrl: '/api/swarms/demo/operations/corr-1',
      outcomeTopic: `event.outcome.swarm-${action}.demo.orchestrator.orch-1`,
      timeoutMs: 90_000,
    },
    ACCEPTED_AT,
  )
}

function envelope(
  kind: string,
  type: string,
  data: Record<string, unknown>,
  role = 'orchestrator',
): ControlPlaneEnvelope {
  return {
    timestamp: '2026-07-13T12:00:01Z',
    version: '1',
    kind,
    type,
    origin: role === 'orchestrator' ? 'orch-1' : 'sc-1',
    scope: { swarmId: 'demo', role, instance: role === 'orchestrator' ? 'orch-1' : 'sc-1' },
    correlationId: 'corr-1',
    idempotencyKey: 'idem-1',
    data,
  }
}

function entry(controlEnvelope: ControlPlaneEnvelope): WireLogEntry {
  const routingKey = `event.outcome.${controlEnvelope.type}.demo.${controlEnvelope.scope.role}.${controlEnvelope.scope.instance}`
  return {
    id: `${controlEnvelope.kind}-${controlEnvelope.type}`,
    receivedAt: controlEnvelope.timestamp,
    source: 'stomp',
    routingKey: `/exchange/ph.control/${routingKey}`,
    payload: JSON.stringify(controlEnvelope),
    envelope: controlEnvelope,
    errors: [],
  }
}
