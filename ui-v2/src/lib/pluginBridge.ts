/**
 * Plugin postMessage bridge — used only when __PLUGIN_MODE__ is true.
 *
 * Webview → Plugin:  sends { type:'api', id, method, path, body, headers }
 * Plugin → Webview:  receives { type:'api-response', id, status, body, headers }
 *                    or       { type:'mcp-response', id, payload, error }
 *                    or       { type:'config', payload: { baseUrl, swarmId, route, ... } }
 *                    or       { type:'data', key, payload }
 *
 * The extension host proxies all HTTP calls so the webview never talks to
 * PocketHive APIs directly (avoids CORS / CSP issues inside VS Code webviews).
 */

declare const __PLUGIN_MODE__: boolean;

type PendingRequest = { resolve: (r: Response) => void; reject: (e: Error) => void };
const pending = new Map<string, PendingRequest>();
let seq = 0;

// Called by the extension host when any message arrives from the plugin side.
// Attach to window so the extension host can call it via executeScript.
(window as any).__phPluginMessage = (msg: unknown) => {
  if (!msg || typeof msg !== 'object') return;
  const m = msg as Record<string, unknown>;

  if (m.type === 'api-response') {
    const p = pending.get(m.id as string);
    if (!p) return;
    pending.delete(m.id as string);
    p.resolve(
      new Response(m.body as string, {
        status: m.status as number,
        headers: m.headers as Record<string, string>,
      })
    );
  }
};

/**
 * Drop-in replacement for `fetch` in plugin mode.
 * Sends the request through the extension host postMessage bridge.
 */
export async function pluginApiFetch(path: string, init?: RequestInit): Promise<Response> {
  const id = `req-${++seq}`;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });

    window.parent.postMessage(
      {
        type: 'api',
        id,
        method: init?.method ?? 'GET',
        path,
        body: init?.body ?? null,
        headers: Object.fromEntries(new Headers(init?.headers ?? {}).entries()),
      },
      '*'
    );

    // Timeout after 30s — prevents leaked pending entries
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`Plugin API request timed out: ${path}`));
      }
    }, 30_000);
  });
}

/**
 * Derive the STOMP WebSocket URL from the injected baseUrl.
 * http://host:8088  →  ws://host:8088/stomp/websocket
 * https://host      →  wss://host/stomp/websocket
 */
export function resolveStompUrl(baseUrl: string): string {
  const url = new URL(baseUrl);
  const protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${url.host}/stomp/websocket`;
}

/**
 * Universal fetch wrapper.
 * In plugin mode: routes through the postMessage bridge.
 * In normal mode: delegates to the native fetch.
 */
export function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  if (typeof __PLUGIN_MODE__ !== 'undefined' && __PLUGIN_MODE__) {
    return pluginApiFetch(path, init);
  }
  return fetch(path, init);
}
