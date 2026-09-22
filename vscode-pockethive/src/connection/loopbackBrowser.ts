/**
 * Responsibility: Receive one loopback OAuth callback and release the temporary listener.
 * Must not: Validate OAuth state, exchange tokens or own callback presentation.
 * Contract: RESP-COMPANION-OAUTH-CALLBACK — docs/architecture/runtime-responsibilities.md#resp-companion-oauth-callback.
 */
import { createServer, Server } from 'node:http';

import {
  BrowserAuthorizationPort,
  BrowserAuthorizationResult,
  ConnectionContractError,
} from './contracts';
import {
  COMPANION_OAUTH_CALLBACK_HOST,
  COMPANION_OAUTH_CALLBACK_PATH,
  companionOAuthRedirectUri,
} from './companionOAuthClient';
import { renderCallbackPage } from './callbackPage';

const TIMEOUT_MS = 120_000;

export type ExternalBrowser = (url: string) => PromiseLike<boolean>;

export class LoopbackBrowserAuthorization implements BrowserAuthorizationPort {
  constructor(private readonly openExternal: ExternalBrowser) {}

  async authorize(
    authorizationUrl: (redirectUri: string) => string,
    signal: AbortSignal,
  ): Promise<BrowserAuthorizationResult> {
    let server: Server | undefined;
    let timeout: NodeJS.Timeout | undefined;
    let onAbort: (() => void) | undefined;
    try {
      const callback = new Promise<BrowserAuthorizationResult>((resolve, reject) => {
        if (signal.aborted) {
          reject(new ConnectionContractError('OAUTH_AUTHORIZATION_CANCELLED', 'OAuth authorization was cancelled'));
          return;
        }
        let redirectUri: string | undefined;
        server = createServer((request, response) => {
          if (request.socket.remoteAddress !== COMPANION_OAUTH_CALLBACK_HOST
              || request.method !== 'GET'
              || !request.url
              || redirectUri === undefined
              || new URL(request.url, redirectUri).pathname
                !== COMPANION_OAUTH_CALLBACK_PATH) {
            response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            response.end('Not found');
            return;
          }
          const callback = new URL(
            request.url,
            redirectUri,
          );
          response.writeHead(200, {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-store',
            'Content-Security-Policy': "default-src 'none'; img-src data:; style-src 'unsafe-inline'",
          });
          response.once('finish', () => resolve(Object.freeze({ callback, redirectUri: redirectUri! })));
          response.end(renderCallbackPage(callback));
        });
        server.once('error', error => reject(new ConnectionContractError(
          'OAUTH_CALLBACK_LISTENER_FAILED', error.message,
        )));
        server.listen(0, COMPANION_OAUTH_CALLBACK_HOST, async () => {
          try {
            const address = server?.address();
            if (address === undefined || address === null || typeof address === 'string') {
              reject(new ConnectionContractError(
                'OAUTH_CALLBACK_LISTENER_FAILED', 'OAuth callback listener did not expose a TCP port',
              ));
              return;
            }
            redirectUri = companionOAuthRedirectUri(address.port);
            if (!await this.openExternal(authorizationUrl(redirectUri))) {
              reject(new ConnectionContractError('OAUTH_BROWSER_OPEN_FAILED', 'VS Code declined the browser request'));
            }
          } catch (error) {
            reject(error);
          }
        });
        timeout = setTimeout(() => reject(new ConnectionContractError(
          'OAUTH_CALLBACK_TIMEOUT', 'OAuth callback was not received within two minutes',
        )), TIMEOUT_MS);
        onAbort = () => reject(new ConnectionContractError(
          'OAUTH_AUTHORIZATION_CANCELLED', 'OAuth authorization was cancelled',
        ));
        signal.addEventListener('abort', onAbort, { once: true });
      });
      return await callback;
    } finally {
      if (timeout) clearTimeout(timeout);
      if (onAbort) signal.removeEventListener('abort', onAbort);
      if (server) await close(server);
    }
  }
}

function close(server: Server): Promise<void> {
  return new Promise(resolve => {
    if (!server.listening) {
      resolve();
      return;
    }
    server.close(() => resolve());
    server.closeAllConnections();
  });
}
