import type { ErrorObject } from 'ajv'
import { getSchemaState, getControlPlaneValidator } from './schemaRegistry'
import type {
  ControlPlaneDecoderError,
  ControlPlaneEnvelope,
  ControlPlaneSource,
} from './types'

type DecodeArgs = {
  source: ControlPlaneSource
  routingKey?: string
  payload: string
  receivedAt?: string
}

type DecodeSuccess = {
  ok: true
  envelope: ControlPlaneEnvelope
}

type DecodeFailure = {
  ok: false
  error: ControlPlaneDecoderError
}

export type DecodeResult = DecodeSuccess | DecodeFailure

const SNIPPET_LIMIT = 512

export function decodeControlPlaneEnvelope(args: DecodeArgs): DecodeResult {
  const receivedAt = args.receivedAt ?? new Date().toISOString()
  const schemaState = getSchemaState()
  const validator = getControlPlaneValidator()
  if (!validator) {
    return {
      ok: false,
      error: {
        receivedAt,
        source: args.source,
        routingKey: args.routingKey,
        errorCode: schemaState.status === 'error' ? 'schema-invalid' : 'schema-missing',
        message: schemaState.error ?? 'Control-plane schema is not loaded',
        payloadSnippet: snippet(args.payload),
      },
    }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(args.payload)
  } catch (error) {
    return {
      ok: false,
      error: {
        receivedAt,
        source: args.source,
        routingKey: args.routingKey,
        errorCode: 'decode-failed',
        message: error instanceof Error ? error.message : 'Failed to parse JSON payload',
        payloadSnippet: snippet(args.payload),
      },
    }
  }
  const valid = validator(parsed)
  if (!valid) {
    const detail = firstError(validator.errors ?? [])
    return {
      ok: false,
      error: {
        receivedAt,
        source: args.source,
        routingKey: args.routingKey,
        errorCode: 'schema-violation',
        message: detail.message ?? 'Schema validation failed',
        schemaPath: detail.schemaPath,
        dataPath: detail.dataPath,
        payloadSnippet: snippet(args.payload),
      },
    }
  }
  const envelope = parsed as ControlPlaneEnvelope
  const routingError = validateRouting(args.routingKey, envelope)
  if (routingError) {
    return {
      ok: false,
      error: {
        receivedAt,
        source: args.source,
        routingKey: args.routingKey,
        errorCode: 'routing-invalid',
        message: routingError,
        payloadSnippet: snippet(args.payload),
      },
    }
  }
  return { ok: true, envelope }
}

function firstError(errors: ErrorObject[]) {
  if (errors.length === 0) {
    return { dataPath: undefined, schemaPath: undefined, message: undefined }
  }
  const error = errors[0]
  return {
    dataPath: error.instancePath,
    schemaPath: error.schemaPath,
    message: error.message,
  }
}

function validateRouting(routingKey: string | undefined, envelope: ControlPlaneEnvelope) {
  if (!routingKey) {
    return null
  }
  const expectedPrefix = routingPrefix(envelope)
  if (!expectedPrefix) {
    return null
  }
  const normalized = stripDestinationPrefix(routingKey)
  if (normalized.startsWith(expectedPrefix)) {
    return null
  }
  return `Routing key does not match envelope kind/type (${expectedPrefix}*)`
}

function routingPrefix(envelope: ControlPlaneEnvelope) {
  if (envelope.kind === 'signal') {
    return `signal.${envelope.type}.`
  }
  if (envelope.kind === 'outcome') {
    return `event.outcome.${envelope.type}.`
  }
  if (envelope.kind === 'metric') {
    return `event.metric.${envelope.type}.`
  }
  if (envelope.kind === 'event') {
    return `event.alert.${envelope.type}.`
  }
  return null
}

function stripDestinationPrefix(routingKey: string) {
  const prefix = '/exchange/ph.control/'
  if (routingKey.startsWith(prefix)) {
    return routingKey.slice(prefix.length)
  }
  return routingKey
}

function snippet(value: string) {
  if (value.length <= SNIPPET_LIMIT) {
    return value
  }
  return `${value.slice(0, SNIPPET_LIMIT)}...`
}
