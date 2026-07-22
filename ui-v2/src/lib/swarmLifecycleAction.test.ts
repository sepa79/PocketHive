import { describe, expect, it } from 'vitest'
import type { ControlPlaneEnvelope } from './controlPlane/types'
import type { WireLogEntry } from './controlPlane/wireLogStore'
import {
  parseSwarmLifecycleControlResponse,
  pendingSwarmLifecycleFeedback,
  resolveSwarmLifecycleFeedback,
} from './swarmLifecycleAction'

const ACCEPTED_AT = Date.parse('2026-07-13T12:00:00Z')

describe('swarm lifecycle action feedback', () => {
  it('requires the explicit correlation and watch contract', () => {
    expect(
      parseSwarmLifecycleControlResponse({
        correlationId: 'corr-1',
        idempotencyKey: 'idem-1',
        timeoutMs: 90_000,
        watch: { successTopic: 'event.outcome.swarm-stop.demo.swarm-controller.sc-1', errorTopics: ['event.alert.alert.demo.swarm-controller.sc-1'] },
      }),
    ).toMatchObject({ correlationId: 'corr-1', timeoutMs: 90_000 })

    expect(() =>
      parseSwarmLifecycleControlResponse({
        correlationId: 'corr-1',
        idempotencyKey: 'idem-1',
        timeoutMs: 90_000,
        watch: { successTopic: 'event.outcome.swarm-stop.demo.swarm-controller.sc-1' },
      }),
    ).toThrow('watch.errorTopics')
  })

  it('surfaces a human-readable alert message', () => {
    const feedback = pending('start')
    const resolved = resolveSwarmLifecycleFeedback(
      feedback,
      [entry(envelope('event', 'alert', { level: 'error', code: 'runtime.exception', message: 'Worker configuration was rejected.' }))],
      ACCEPTED_AT + 1000,
    )

    expect(resolved).toMatchObject({
      status: 'error',
      code: 'runtime.exception',
      message: "Could not start swarm 'demo': Worker configuration was rejected.",
    })
  })

  it('explains a NotReady outcome using its structured context', () => {
    const feedback = pending('start')
    const resolved = resolveSwarmLifecycleFeedback(
      feedback,
      [entry(envelope('outcome', 'swarm-start', { status: 'NotReady', context: { initialized: false, ready: false, pendingConfigUpdates: true } }))],
      ACCEPTED_AT + 1000,
    )

    expect(resolved.status).toBe('error')
    expect(resolved.message).toContain('initialization is incomplete')
    expect(resolved.message).toContain('workers are not ready')
    expect(resolved.message).toContain('configuration updates are still pending')
  })

  it('surfaces timeout alerts emitted by the orchestrator', () => {
    const feedback = pending('stop')
    const resolved = resolveSwarmLifecycleFeedback(
      feedback,
      [entry(envelope('event', 'alert', { level: 'error', code: 'timeout', message: 'stop confirmation timed out' }, 'orchestrator'))],
      ACCEPTED_AT + 1000,
    )

    expect(resolved).toMatchObject({
      status: 'error',
      code: 'timeout',
      message: "Could not stop swarm 'demo': stop confirmation timed out",
    })
  })

  it('replaces an early success with a later correlated finalization error', () => {
    const accepted = pending('stop')
    const successful = resolveSwarmLifecycleFeedback(
      accepted,
      [entry(envelope('outcome', 'swarm-stop', { status: 'Stopped' }))],
      ACCEPTED_AT + 1000,
    )
    const resolved = resolveSwarmLifecycleFeedback(
      successful,
      [entry(envelope('event', 'alert', { level: 'error', code: 'runtime.exception', message: 'Container shutdown failed.' }, 'orchestrator'))],
      ACCEPTED_AT + 2000,
    )

    expect(resolved).toMatchObject({
      status: 'error',
      code: 'runtime.exception',
      message: "Could not stop swarm 'demo': Container shutdown failed.",
    })
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
      timeoutMs: 90_000,
      watch: {
        successTopic: `event.outcome.swarm-${action}.demo.swarm-controller.sc-1`,
        errorTopics: [
          'event.alert.alert.demo.swarm-controller.sc-1',
          'event.alert.alert.demo.orchestrator.orch-1',
        ],
      },
    },
    ACCEPTED_AT,
  )
}

function envelope(
  kind: string,
  type: string,
  data: Record<string, unknown>,
  role = 'swarm-controller',
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
  const routingKey =
    controlEnvelope.type === 'alert'
      ? `event.alert.alert.demo.${controlEnvelope.scope.role}.${controlEnvelope.scope.instance}`
      : `event.outcome.${controlEnvelope.type}.demo.${controlEnvelope.scope.role}.${controlEnvelope.scope.instance}`
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
