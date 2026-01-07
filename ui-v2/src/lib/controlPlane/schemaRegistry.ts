import Ajv from 'ajv/dist/2020'
import addFormats from 'ajv-formats'
import type { AnySchema, ValidateFunction } from 'ajv'
import { CONTROL_PLANE_SCHEMA_URL } from './config'

type SchemaStatus = 'idle' | 'loading' | 'ready' | 'error'

export type SchemaState = {
  status: SchemaStatus
  etag?: string
  error?: string
  validator?: ValidateFunction<unknown>
}

type SchemaListener = (state: SchemaState) => void

let state: SchemaState = { status: 'idle' }
let inflight: Promise<SchemaState> | null = null
const listeners = new Set<SchemaListener>()

export function getSchemaState() {
  return state
}

export function subscribeSchemaState(listener: SchemaListener) {
  listeners.add(listener)
  listener(state)
  return () => listeners.delete(listener)
}

export function getControlPlaneValidator() {
  return state.status === 'ready' ? state.validator ?? null : null
}

export async function loadControlPlaneSchema(signal?: AbortSignal) {
  if (inflight) {
    return inflight
  }
  state = { ...state, status: 'loading', error: undefined }
  notify()
  inflight = fetchSchema(signal)
    .then((next) => {
      state = next
      return state
    })
    .finally(() => {
      inflight = null
      notify()
    })
  return inflight
}

async function fetchSchema(signal?: AbortSignal): Promise<SchemaState> {
  const headers: HeadersInit = {}
  if (state.etag) {
    headers['If-None-Match'] = state.etag
  }
  let response: Response
  try {
    response = await fetch(CONTROL_PLANE_SCHEMA_URL, { headers, signal })
  } catch (error) {
    return {
      status: 'error',
      etag: state.etag,
      error: error instanceof Error ? error.message : 'Failed to reach schema endpoint',
    }
  }
  if (response.status === 304) {
    if (state.validator) {
      return { ...state, status: 'ready' }
    }
    return {
      status: 'error',
      etag: state.etag,
      error: 'Schema cache miss after 304 response',
    }
  }
  if (!response.ok) {
    return {
      status: 'error',
      etag: state.etag,
      error: `Schema endpoint returned ${response.status}`,
    }
  }
  let json: unknown
  try {
    json = await response.json()
  } catch (error) {
    return {
      status: 'error',
      etag: state.etag,
      error: error instanceof Error ? error.message : 'Failed to parse schema JSON',
    }
  }
  if (!json || typeof json !== 'object') {
    return {
      status: 'error',
      etag: state.etag,
      error: 'Schema JSON is not an object',
    }
  }
  let validator: ValidateFunction<unknown>
  try {
    const ajv = new Ajv({ allErrors: true, strict: true })
    addFormats(ajv)
    validator = ajv.compile(json as AnySchema)
  } catch (error) {
    return {
      status: 'error',
      etag: state.etag,
      error: error instanceof Error ? error.message : 'Failed to compile schema',
    }
  }
  return {
    status: 'ready',
    etag: response.headers.get('ETag') ?? state.etag,
    validator,
  }
}

function notify() {
  listeners.forEach((listener) => listener(state))
}
