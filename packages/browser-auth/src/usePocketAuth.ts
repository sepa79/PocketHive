import { useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import { PocketAuthController } from './PocketAuthController.js'
import type { PocketAuthOptions } from './PocketAuthOptions.js'
import type { PocketAuthResult } from './PocketAuthResult.js'
import type { PocketSession } from './PocketSession.js'
import { SessionApi } from './SessionApi.js'
import type { SessionApiPort } from './SessionApiPort.js'

const defaultApi = new SessionApi()

/**
 * Responsibility: bind one browser-session controller to React subscriptions and cleanup.
 * Must not: duplicate the session state machine, parse protocol responses or own product UI.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function usePocketAuth<Session extends PocketSession>(options: PocketAuthOptions<Session> & { api: SessionApiPort<Session> }): PocketAuthResult<Session>
export function usePocketAuth(options: PocketAuthOptions): PocketAuthResult
export function usePocketAuth({ storageKey, onError, api = defaultApi }: PocketAuthOptions): PocketAuthResult {
  const errorHandler = useRef(onError)
  errorHandler.current = onError
  const controller = useMemo(() => new PocketAuthController(storageKey, error => errorHandler.current?.(error), api), [storageKey, api])
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useEffect(() => controller.mount(), [controller])
  return { ...state, signIn: controller.signIn, signOut: controller.signOut,
    cancelSignIn: controller.cancelSignIn, openConfirmation: controller.openConfirmation,
    fetchWithAuth: controller.fetchWithAuth, refresh: controller.refresh, getAccessToken: controller.getAccessToken }
}
