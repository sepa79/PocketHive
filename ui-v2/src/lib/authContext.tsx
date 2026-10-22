import { createContext, useContext, useEffect, useMemo, type ReactNode } from 'react'
import { usePocketAuth } from '@pockethive/browser-auth'
import { useAccessObservation } from './useAccessObservation'
import { authAccessApi } from './authAccessApi'
import { AUTH_STORAGE_KEY } from './authSession'
import { PocketHiveSessionApi } from './PocketHiveSessionApi'
import { bootstrapControlPlane, resetControlPlaneBootstrap } from './controlPlane/bootstrap'
import { resetControlPlaneSchema } from './controlPlane/schemaRegistry'

const api = new PocketHiveSessionApi()

function usePocketHiveIdentity() {
  const auth = usePocketAuth({ storageKey: AUTH_STORAGE_KEY, api })
  const user = auth.session.user
  const status = auth.loading ? 'loading' as const : user ? 'authenticated' as const : 'anonymous' as const
  const token = auth.getAccessToken()
  const session = useMemo(() => token !== null && user !== null ? { accessToken: token, user } : null, [token, user])
  const access = useAccessObservation(user, token, authAccessApi, status === 'authenticated')

  useEffect(() => {
    resetControlPlaneBootstrap()
    resetControlPlaneSchema()
    if (status === 'authenticated') bootstrapControlPlane()
  }, [status, token])

  return { status, user, session, error: auth.error?.message ?? null,
    login: auth.signIn, logout: auth.signOut, refresh: auth.refresh,
    signingIn: auth.signingIn, confirmationUrl: auth.confirmationUrl,
    cancelSignIn: auth.cancelSignIn, openConfirmation: auth.openConfirmation,
    canAccessPocketHive: access.value?.canAccessPocketHive === true,
    canRunPocketHive: access.value?.canRunPocketHive === true,
    canManageDeployment: access.value?.canManageDeployment === true,
    isAuthAdmin: access.value?.canManageUsers === true,
    accessStatus: access.status, accessError: access.error, reloadAccess: access.reload }
}

const AuthContext = createContext<ReturnType<typeof usePocketHiveIdentity> | null>(null)

/**
 * Responsibility: compose the shared credential lifecycle with PH's backend access observations.
 * Must not: store a second session, derive permissions from grants or select a fallback login.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const auth = usePocketHiveIdentity()
  return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used within AuthProvider')
  return value
}
