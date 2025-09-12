import { logIn, logOut } from './logs'

export async function apiFetch(input: RequestInfo, init: RequestInit = {}): Promise<Response> {
  const correlationId = crypto.randomUUID()
  const headers: Record<string, string> = {
    ...(init.headers as Record<string, string> | undefined),
    'x-correlation-id': correlationId,
  }
  const body = init.body ? (typeof init.body === 'string' ? init.body : String(init.body)) : ''
  if (typeof input === 'string' || input instanceof URL) {
    logOut(input.toString(), body, 'ui', 'rest', correlationId)
  } else {
    logOut(input.url, body, 'ui', 'rest', correlationId)
  }
  const response = await fetch(input, { ...init, headers })
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
