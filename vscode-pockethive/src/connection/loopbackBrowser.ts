import { createServer, Server } from 'node:http';

import {
  BrowserAuthorizationPort,
  ConnectionContractError,
} from './contracts';

const CALLBACK_HOST = '127.0.0.1';
const CALLBACK_PORT = 57_548;
const CALLBACK_PATH = '/callback';
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
          if (request.socket.remoteAddress !== CALLBACK_HOST
              || request.method !== 'GET'
              || !request.url
              || new URL(request.url, `http://${CALLBACK_HOST}:${CALLBACK_PORT}`).pathname !== CALLBACK_PATH) {
            response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            response.end('Not found');
            return;
          }
          response.writeHead(200, {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-store',
            'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'",
          });
          response.end('<!doctype html><html><body><p>PocketHive sign-in complete. Return to VS Code.</p></body></html>');
          resolve(new URL(request.url, `http://${CALLBACK_HOST}:${CALLBACK_PORT}`));
        });
        server.once('error', error => reject(new ConnectionContractError(
          'OAUTH_CALLBACK_LISTENER_FAILED', error.message,
        )));
        server.listen(CALLBACK_PORT, CALLBACK_HOST, async () => {
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

function close(server: Server): Promise<void> {
  return new Promise(resolve => {
    if (!server.listening) {
      resolve();
      return;
    }
    server.close(() => resolve());
  });
}
