import { logIn, logOut } from './logs'

export interface ApiFetchInit extends RequestInit {
  omitCorrelationId?: boolean
}

function normaliseHeaders(headers?: HeadersInit): Record<string, string> {
  if (!headers) {
    return {}
  }
  if (headers instanceof Headers) {
    return Object.fromEntries(headers.entries())
  }
  if (Array.isArray(headers)) {
    return Object.fromEntries(headers)
  }
  return { ...headers }
}

export async function apiFetch(input: RequestInfo, init: ApiFetchInit = {}): Promise<Response> {
  const { omitCorrelationId, headers: initHeaders, body, ...rest } = init
  const correlationId = crypto.randomUUID()
  const headers = normaliseHeaders(initHeaders)
  if (!omitCorrelationId) {
    headers['x-correlation-id'] = correlationId
  }
  const requestBody = body ? (typeof body === 'string' ? body : String(body)) : ''
  if (typeof input === 'string' || input instanceof URL) {
    logOut(input.toString(), requestBody, 'ui', 'rest', correlationId)
  } else {
    logOut(input.url, requestBody, 'ui', 'rest', correlationId)
  }
  const response = await fetch(input, { ...rest, headers, body })
  let text = ''
  try {
    text = await response.clone().text()
  } catch {
    // ignore
  }
  if (typeof input === 'string' || input instanceof URL) {
    logIn(input.toString(), text, 'hive', 'rest', correlationId)
  } else {
    logIn(input.url, text, 'hive', 'rest', correlationId)
  }
  return response
}
