import type { ControlPlaneEnvelope } from './controlPlane/types'
import type { WireLogEntry } from './controlPlane/wireLogStore'
import { normalizeControlPlaneRoutingKey } from './controlPlane/decoder'

export type SwarmLifecycleAction = 'start' | 'stop'
export type SwarmLifecycleFeedbackStatus = 'pending' | 'success' | 'error'

export type SwarmLifecycleControlResponse = {
  correlationId: string
  idempotencyKey: string
  timeoutMs: number
  watch: {
    successTopic: string
    errorTopics: string[]
  }
}

export type SwarmLifecycleFeedback = {
  swarmId: string
  action: SwarmLifecycleAction
  correlationId: string
  deadlineAt: number
  timeoutMs: number
  successTopic: string
  errorTopics: string[]
  status: SwarmLifecycleFeedbackStatus
  message: string
  code: string | null
}

export function parseSwarmLifecycleControlResponse(value: unknown): SwarmLifecycleControlResponse {
  if (!isRecord(value)) {
    throw new Error('Orchestrator returned an invalid lifecycle response.')
  }
  const correlationId = requiredString(value.correlationId, 'correlationId')
  const idempotencyKey = requiredString(value.idempotencyKey, 'idempotencyKey')
  const timeoutMs = value.timeoutMs
  if (typeof timeoutMs !== 'number' || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error('Orchestrator lifecycle response is missing a valid timeoutMs.')
  }
  if (!isRecord(value.watch)) {
    throw new Error('Orchestrator lifecycle response is missing watch topics.')
  }
  const successTopic = requiredString(value.watch.successTopic, 'watch.successTopic')
  if (!Array.isArray(value.watch.errorTopics)) {
    throw new Error('Orchestrator lifecycle response is missing watch.errorTopics.')
  }
  const errorTopics = value.watch.errorTopics.map((topic, index) =>
    requiredString(topic, `watch.errorTopics[${index}]`),
  )
  if (errorTopics.length === 0) {
    throw new Error('Orchestrator lifecycle response has no error topics.')
  }
  return { correlationId, idempotencyKey, timeoutMs, watch: { successTopic, errorTopics } }
}

export function pendingSwarmLifecycleFeedback(
  swarmId: string,
  action: SwarmLifecycleAction,
  response: SwarmLifecycleControlResponse,
  acceptedAt = Date.now(),
): SwarmLifecycleFeedback {
  return {
    swarmId,
    action,
    correlationId: response.correlationId,
    deadlineAt: acceptedAt + response.timeoutMs,
    timeoutMs: response.timeoutMs,
    successTopic: response.watch.successTopic,
    errorTopics: response.watch.errorTopics,
    status: 'pending',
    message: `${action === 'start' ? 'Starting' : 'Stopping'} swarm '${swarmId}'…`,
    code: null,
  }
}

export function resolveSwarmLifecycleFeedback(
  feedback: SwarmLifecycleFeedback,
  entries: WireLogEntry[],
  now = Date.now(),
): SwarmLifecycleFeedback {
  if (feedback.status === 'error') return feedback
  if (feedback.status === 'success' && now >= feedback.deadlineAt) return feedback

  const correlated = entries.filter((entry) => entry.envelope?.correlationId === feedback.correlationId)
  const alert = [...correlated]
    .reverse()
    .find(
      (entry) =>
        entry.routingKey !== undefined &&
        feedback.errorTopics.includes(normalizeControlPlaneRoutingKey(entry.routingKey)) &&
        isErrorAlert(entry.envelope),
    )
    ?.envelope
  if (alert) {
    const detail = optionalString(alert.data.message) ?? 'The control plane reported an error.'
    return terminalFeedback(feedback, 'error', `Could not ${feedback.action} swarm '${feedback.swarmId}': ${detail}`, optionalString(alert.data.code))
  }

  if (feedback.status === 'success') return feedback

  const outcome = [...correlated]
    .reverse()
    .find(
      (entry) =>
        entry.routingKey !== undefined &&
        normalizeControlPlaneRoutingKey(entry.routingKey) === feedback.successTopic &&
        entry.envelope?.kind === 'outcome',
    )?.envelope
  const outcomeStatus = outcome ? optionalString(outcome.data.status) : null
  if (outcomeStatus) {
    const normalized = outcomeStatus.toUpperCase()
    const expected = feedback.action === 'start' ? 'RUNNING' : 'STOPPED'
    if (normalized === expected) {
      return terminalFeedback(
        feedback,
        'success',
        `Swarm '${feedback.swarmId}' ${feedback.action === 'start' ? 'started' : 'stopped'}.`,
        null,
      )
    }
    if (normalized === 'NOTREADY') {
      return terminalFeedback(feedback, 'error', notReadyMessage(feedback, outcome?.data.context), 'NotReady')
    }
    if (normalized === 'FAILED') {
      return terminalFeedback(
        feedback,
        'error',
        `Could not ${feedback.action} swarm '${feedback.swarmId}': the control plane reported a failed outcome.`,
        'Failed',
      )
    }
  }

  if (now >= feedback.deadlineAt) {
    const seconds = Math.ceil(feedback.timeoutMs / 1000)
    return terminalFeedback(
      feedback,
      'error',
      `Could not ${feedback.action} swarm '${feedback.swarmId}': no final result was received within ${seconds} seconds.`,
      'timeout',
    )
  }
  return feedback
}

function notReadyMessage(feedback: SwarmLifecycleFeedback, contextValue: unknown): string {
  const context = isRecord(contextValue) ? contextValue : null
  const reasons: string[] = []
  if (context?.initialized === false) reasons.push('initialization is incomplete')
  if (context?.ready === false) reasons.push('workers are not ready')
  if (context?.pendingConfigUpdates === true) reasons.push('configuration updates are still pending')
  const status = context ? optionalString(context.status) : null
  if (status) reasons.push(`current status is ${status}`)
  const detail = reasons.length > 0 ? reasons.join('; ') : 'start/stop preconditions are not satisfied'
  return `Could not ${feedback.action} swarm '${feedback.swarmId}': ${detail}.`
}

function terminalFeedback(
  feedback: SwarmLifecycleFeedback,
  status: Exclude<SwarmLifecycleFeedbackStatus, 'pending'>,
  message: string,
  code: string | null,
): SwarmLifecycleFeedback {
  return { ...feedback, status, message, code }
}

function isErrorAlert(envelope: ControlPlaneEnvelope | undefined): boolean {
  if (!envelope || envelope.kind !== 'event' || envelope.type !== 'alert') return false
  return optionalString(envelope.data.level)?.toLowerCase() === 'error'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function requiredString(value: unknown, field: string): string {
  const text = optionalString(value)
  if (!text) throw new Error(`Orchestrator lifecycle response is missing ${field}.`)
  return text
}

function optionalString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}
