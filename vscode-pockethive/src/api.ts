export async function requestJson<T>(
  baseUrl: string,
  authToken: string | undefined,
  method: 'GET' | 'POST',
  path: string,
  body?: Record<string, unknown>
): Promise<T> {
  const url = `${baseUrl}${path}`;
  const headers: Record<string, string> = {
    Accept: 'application/json'
  };

  if (body) {
    headers['Content-Type'] = 'application/json';
  }

  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
  }

  const response = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  });

  const text = await response.text();

  if (!response.ok) {
    const statusLine = `${response.status} ${response.statusText}`.trim();
    throw new Error(text ? `${statusLine}: ${text}` : statusLine);
  }

  if (!text) {
    return undefined as T;
  }

  try {
    return JSON.parse(text) as T;
  } catch (error) {
    throw new Error(`Invalid JSON response from ${path}.`);
  }
}

export async function requestText(
  baseUrl: string,
  authToken: string | undefined,
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  body?: string
): Promise<string> {
  const url = `${baseUrl}${path}`;
  const headers: Record<string, string> = {
    Accept: 'text/plain'
  };

  if (body) {
    headers['Content-Type'] = 'text/plain';
  }

  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
  }

  const response = await fetch(url, {
    method,
    headers,
    body
  });

  const text = await response.text();

  if (!response.ok) {
    const statusLine = `${response.status} ${response.statusText}`.trim();
    throw new Error(text ? `${statusLine}: ${text}` : statusLine);
  }

  return text;
}
