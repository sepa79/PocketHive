import { createServer, Server } from 'node:http';

import {
  BrowserAuthorizationPort,
  ConnectionContractError,
} from './contracts';
import {
  COMPANION_OAUTH_CALLBACK_HOST,
  COMPANION_OAUTH_CALLBACK_PATH,
  COMPANION_OAUTH_CALLBACK_PORT,
} from './companionOAuthClient';
import { CALLBACK_LOGO_DATA_URI } from '../generated/callbackLogo';

const TIMEOUT_MS = 120_000;

export type ExternalBrowser = (url: string) => PromiseLike<boolean>;

export class LoopbackBrowserAuthorization implements BrowserAuthorizationPort {
  constructor(private readonly openExternal: ExternalBrowser) {}

  async authorize(authorizationUrl: string, signal: AbortSignal): Promise<URL> {
    let server: Server | undefined;
    let timeout: NodeJS.Timeout | undefined;
    try {
      const callback = new Promise<URL>((resolve, reject) => {
        if (signal.aborted) {
          reject(new ConnectionContractError('OAUTH_AUTHORIZATION_CANCELLED', 'OAuth authorization was cancelled'));
          return;
        }
        server = createServer((request, response) => {
          if (request.socket.remoteAddress !== COMPANION_OAUTH_CALLBACK_HOST
              || request.method !== 'GET'
              || !request.url
              || new URL(request.url, `http://${COMPANION_OAUTH_CALLBACK_HOST}:${COMPANION_OAUTH_CALLBACK_PORT}`).pathname
                !== COMPANION_OAUTH_CALLBACK_PATH) {
            response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            response.end('Not found');
            return;
          }
          const callback = new URL(
            request.url,
            `http://${COMPANION_OAUTH_CALLBACK_HOST}:${COMPANION_OAUTH_CALLBACK_PORT}`,
          );
          response.writeHead(200, {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-store',
            'Content-Security-Policy': "default-src 'none'; img-src data:; style-src 'unsafe-inline'",
          });
          response.end(renderCallbackPage(callback));
          resolve(callback);
        });
        server.once('error', error => reject(new ConnectionContractError(
          'OAUTH_CALLBACK_LISTENER_FAILED', error.message,
        )));
        server.listen(COMPANION_OAUTH_CALLBACK_PORT, COMPANION_OAUTH_CALLBACK_HOST, async () => {
          try {
            if (!await this.openExternal(authorizationUrl)) {
              reject(new ConnectionContractError('OAUTH_BROWSER_OPEN_FAILED', 'VS Code declined the browser request'));
            }
          } catch (error) {
            reject(error);
          }
        });
        timeout = setTimeout(() => reject(new ConnectionContractError(
          'OAUTH_CALLBACK_TIMEOUT', 'OAuth callback was not received within two minutes',
        )), TIMEOUT_MS);
        signal.addEventListener('abort', () => reject(new ConnectionContractError(
          'OAUTH_AUTHORIZATION_CANCELLED', 'OAuth authorization was cancelled',
        )), { once: true });
      });
      return await callback;
    } finally {
      if (timeout) clearTimeout(timeout);
      if (server) await close(server);
    }
  }
}

function renderCallbackPage(callback: URL): string {
  const view = callbackView(callback);
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${escapeHtml(view.title)}</title>
    <style>
      :root {
        color-scheme: dark;
        --ph-brand-hive: #ffc107;
        --ph-bg: #050a11;
        --ph-surface: rgba(11, 20, 32, 0.96);
        --ph-border: #2a3a4d;
        --ph-text: #edf4fb;
        --ph-muted: #9eacbc;
        --ph-accent: #24c8f4;
        --ph-success: #3ddc97;
        --ph-danger: #ff8b85;
        font-family: Inter, "Segoe UI", system-ui, -apple-system, sans-serif;
      }
      * { box-sizing: border-box; }
      html, body { min-height: 100%; margin: 0; }
      body {
        background:
          radial-gradient(circle at top, rgba(36, 200, 244, 0.18), transparent 34rem),
          radial-gradient(circle at bottom, rgba(255, 193, 7, 0.08), transparent 28rem),
          var(--ph-bg);
        color: var(--ph-text);
      }
      .auth-shell {
        display: grid;
        min-height: 100vh;
        place-items: center;
        padding: 24px;
      }
      .auth-card {
        width: min(100%, 560px);
        padding: clamp(24px, 4vw, 36px);
        border: 1px solid var(--ph-border);
        border-radius: 18px;
        background: var(--ph-surface);
        box-shadow: 0 24px 80px rgba(0, 0, 0, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.05);
      }
      .auth-brand {
        display: flex;
        align-items: center;
        padding-bottom: 18px;
        border-bottom: 1px solid var(--ph-border);
      }
      .auth-brand__logo {
        display: block;
        width: min(100%, 220px);
        height: auto;
      }
      .auth-status {
        display: inline-flex;
        align-items: center;
        gap: 10px;
        margin-top: 24px;
        padding: 8px 14px;
        border-radius: 999px;
        background: rgba(255, 255, 255, 0.04);
        color: ${view.statusColor};
        font-size: 0.82rem;
        font-weight: 700;
        letter-spacing: 0.04em;
        text-transform: uppercase;
      }
      .auth-status__dot {
        width: 10px;
        height: 10px;
        border-radius: 999px;
        background: currentColor;
        box-shadow: 0 0 16px currentColor;
      }
      h1 {
        margin: 18px 0 10px;
        font-size: clamp(1.9rem, 6vw, 2.5rem);
        line-height: 1.08;
        letter-spacing: -0.04em;
      }
      p {
        margin: 0;
        color: var(--ph-muted);
        line-height: 1.6;
      }
      .auth-panel {
        margin-top: 22px;
        padding: 18px;
        border: 1px solid var(--ph-border);
        border-radius: 14px;
        background: rgba(255, 255, 255, 0.03);
      }
      .auth-panel strong {
        display: block;
        margin-bottom: 6px;
        color: var(--ph-text);
        font-size: 0.95rem;
      }
      .auth-hint {
        margin-top: 18px;
        color: var(--ph-text);
        font-weight: 600;
      }
      .auth-footer {
        margin-top: 24px;
        padding-top: 16px;
        border-top: 1px solid var(--ph-border);
        color: var(--ph-muted);
        font-size: 0.82rem;
      }
      @media (max-width: 480px) {
        .auth-shell { padding: 0; }
        .auth-card {
          min-height: 100vh;
          border: 0;
          border-radius: 0;
        }
      }
    </style>
  </head>
  <body>
    <main class="auth-shell">
      <section class="auth-card" aria-labelledby="auth-title">
        <header class="auth-brand">
          <img class="auth-brand__logo" src="${CALLBACK_LOGO_DATA_URI}" alt="PocketHive">
        </header>
        <div class="auth-status"><span class="auth-status__dot" aria-hidden="true"></span>${escapeHtml(view.badge)}</div>
        <h1 id="auth-title">${escapeHtml(view.heading)}</h1>
        <p>${escapeHtml(view.message)}</p>
        <div class="auth-panel">
          <strong>${escapeHtml(view.panelTitle)}</strong>
          <p>${escapeHtml(view.panelBody)}</p>
        </div>
        <p class="auth-hint">${escapeHtml(view.hint)}</p>
        <p class="auth-footer">This local hand-off page never stores your PocketHive credentials in the browser.</p>
      </section>
    </main>
  </body>
</html>`;
}

function callbackView(callback: URL): {
  readonly title: string;
  readonly badge: string;
  readonly heading: string;
  readonly message: string;
  readonly panelTitle: string;
  readonly panelBody: string;
  readonly hint: string;
  readonly statusColor: string;
} {
  const error = callback.searchParams.get('error');
  if (error === 'access_denied') {
    return {
      title: 'PocketHive sign-in cancelled',
      badge: 'Cancelled',
      heading: 'Sign-in cancelled',
      message: 'No PocketHive session was created because the authorization request was declined.',
      panelTitle: 'What happens next',
      panelBody: 'VS Code will return you to the environment screen so you can try again whenever you are ready.',
      hint: 'You can close this tab and return to VS Code.',
      statusColor: 'var(--ph-danger)',
    };
  }
  if (error) {
    return {
      title: 'PocketHive sign-in needs attention',
      badge: 'Action required',
      heading: 'PocketHive sign-in did not complete',
      message: `The browser returned the OAuth error "${error}".`,
      panelTitle: 'What happens next',
      panelBody: 'VS Code will show the exact failure so you can retry or adjust the environment configuration.',
      hint: 'You can close this tab and return to VS Code.',
      statusColor: 'var(--ph-danger)',
    };
  }
  return {
    title: 'PocketHive sign-in complete',
    badge: 'Connected',
    heading: 'PocketHive sign-in complete',
    message: 'The PocketHive environment accepted the authorization request and handed control back to VS Code.',
    panelTitle: 'Connection hand-off complete',
    panelBody: 'VS Code is now finishing the secure token exchange and validating the MCP session.',
    hint: 'Return to VS Code to finish connecting.',
    statusColor: 'var(--ph-success)',
  };
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll('\'', '&#39;');
}

function close(server: Server): Promise<void> {
  return new Promise(resolve => {
    if (!server.listening) {
      resolve();
      return;
    }
    server.close(() => resolve());
  });
}
