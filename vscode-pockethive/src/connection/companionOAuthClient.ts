export const COMPANION_OAUTH_CLIENT_ID = 'pockethive-vscode';
export const COMPANION_OAUTH_CALLBACK_HOST = '127.0.0.1';
export const COMPANION_OAUTH_CALLBACK_PATH = '/callback';

export function companionOAuthRedirectUri(port: number): string {
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error('OAUTH_CALLBACK_PORT_INVALID');
  }
  return `http://${COMPANION_OAUTH_CALLBACK_HOST}:${port}${COMPANION_OAUTH_CALLBACK_PATH}`;
}
