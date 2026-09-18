import type { WireLogEntry } from './controlPlane/wireLogStore'
import { normalizeControlPlaneRoutingKey } from './controlPlane/decoder'
import {
  parseControlResponse,
  parseOperationState,
  parseTerminalStatus,
  type ControlResponse,
} from '@pockethive/swarm-lifecycle-contract'

export type SwarmLifecycleAction = 'start' | 'stop'
export type SwarmLifecycleFeedbackStatus = 'pending' | 'success' | 'error'

export type SwarmLifecycleControlResponse = Readonly<ControlResponse>

export type SwarmLifecycleFeedback = {
  swarmId: string
  action: SwarmLifecycleAction
  correlationId: string
  deadlineAt: number
  timeoutMs: number
  operationUrl: string
  outcomeTopic: string
  status: SwarmLifecycleFeedbackStatus
  message: string
  code: string | null
}

export function parseSwarmLifecycleControlResponse(value: unknown): SwarmLifecycleControlResponse {
  return parseControlResponse(value)
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
    operationUrl: response.operationUrl,
    outcomeTopic: response.outcomeTopic,
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
  if (feedback.status === 'success') return feedback

  const outcome = [...correlated]
    .reverse()
    .find(
      (entry) =>
        entry.routingKey !== undefined &&
        normalizeControlPlaneRoutingKey(entry.routingKey) === feedback.outcomeTopic &&
        entry.envelope?.kind === 'outcome',
    )?.envelope
  if (outcome) {
    const outcomeStatus = parseTerminalStatus(outcome.data.status)
    if (outcomeStatus === 'Succeeded') {
      return terminalFeedback(
        feedback,
        'success',
        `Swarm '${feedback.swarmId}' ${feedback.action === 'start' ? 'started' : 'stopped'}.`,
        null,
      )
    }
    if (outcomeStatus === 'Rejected') {
      return terminalFeedback(feedback, 'error', notReadyMessage(feedback, outcome?.data.context), 'Rejected')
    }
    if (outcomeStatus === 'Failed' || outcomeStatus === 'TimedOut') {
      return terminalFeedback(
        feedback,
        'error',
        `Could not ${feedback.action} swarm '${feedback.swarmId}': the control plane reported a failed outcome.`,
        outcomeStatus,
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

export function resolveSwarmLifecycleOperationFeedback(
  feedback: SwarmLifecycleFeedback,
  operationValue: unknown,
): SwarmLifecycleFeedback {
  if (feedback.status !== 'pending') return feedback
  if (!isRecord(operationValue)) throw new Error('Orchestrator returned an invalid operation resource.')
  const correlationId = requiredString(operationValue.correlationId, 'operation.correlationId')
  if (correlationId !== feedback.correlationId) {
    throw new Error('Orchestrator operation correlationId does not match the accepted request.')
  }
  const state = parseOperationState(operationValue.state)
  if (state === 'SUCCEEDED') {
    return terminalFeedback(
      feedback,
      'success',
      `Swarm '${feedback.swarmId}' ${feedback.action === 'start' ? 'started' : 'stopped'}.`,
      null,
    )
  }
  if (state === 'REJECTED' || state === 'FAILED' || state === 'TIMED_OUT') {
    return terminalFeedback(
      feedback,
      'error',
      `Could not ${feedback.action} swarm '${feedback.swarmId}': operation ended in ${state}.`,
      state,
    )
  }
  if (state !== 'ACCEPTED' && state !== 'DISPATCHED') {
    throw new Error(`Orchestrator returned unsupported operation state '${state}'.`)
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
