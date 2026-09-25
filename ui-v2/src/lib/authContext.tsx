import { useAccessObservation } from './useAccessObservation'
import { authAccessApi } from './authAccessApi'
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import {
  clearAuthSession,
  fetchCurrentUser,
  loginDevUser,
  readStoredAuthSession,
  replaceSessionUser,
  type AuthSession,
  type AuthenticatedUser,
} from './auth'
import { bootstrapControlPlane, resetControlPlaneBootstrap } from './controlPlane/bootstrap'
import { resetControlPlaneSchema } from './controlPlane/schemaRegistry'

type AuthStatus = 'loading' | 'anonymous' | 'authenticated'

type AuthContextValue = {
  status: AuthStatus
  user: AuthenticatedUser | null
  session: AuthSession | null
  error: string | null
  loginDev: (username: string) => Promise<void>
  logout: () => void
  refresh: () => Promise<void>
  canAccessPocketHive: boolean
  canRunPocketHive: boolean
  accessStatus: 'idle' | 'loading' | 'ready' | 'error'
  accessError: string | null
  reloadAccess: () => Promise<void>
  isAuthAdmin: boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

/**
 * Responsibility: provide authentication state and compose backend access observations.
 * Must not: derive permission decisions from grants.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [session, setSession] = useState<AuthSession | null>(() => readStoredAuthSession())
  const [user, setUser] = useState<AuthenticatedUser | null>(() => readStoredAuthSession()?.user ?? null)
  const [error, setError] = useState<string | null>(null)
  const access = useAccessObservation(user, session?.accessToken ?? null, authAccessApi, status === 'authenticated')

  useEffect(() => {
    let cancelled = false

    async function resolveInitialSession() {
      const stored = readStoredAuthSession()
      if (!stored) {
        if (!cancelled) {
          setStatus('anonymous')
          setSession(null)
          setUser(null)
          setError(null)
        }
        return
      }

      try {
        const resolvedUser = await fetchCurrentUser(stored.accessToken)
        if (cancelled) return
        replaceSessionUser(resolvedUser)
        setSession({ ...stored, user: resolvedUser })
        setUser(resolvedUser)
        setStatus('authenticated')
        setError(null)
        bootstrapControlPlane()
      } catch (e) {
        if (cancelled) return
        clearAuthSession()
        resetControlPlaneBootstrap()
        resetControlPlaneSchema()
        setSession(null)
        setUser(null)
        setStatus('anonymous')
        setError(e instanceof Error ? e.message : 'Session restore failed')
      }
    }

    void resolveInitialSession()

    return () => {
      cancelled = true
    }
  }, [])

  async function loginDev(username: string) {
    setError(null)
    const nextSession = await loginDevUser(username)
    resetControlPlaneBootstrap()
    resetControlPlaneSchema()
    setSession(nextSession)
    setUser(nextSession.user)
    setStatus('authenticated')
    bootstrapControlPlane()
  }

  function logout() {
    clearAuthSession()
    resetControlPlaneBootstrap()
    resetControlPlaneSchema()
    setSession(null)
    setUser(null)
    setStatus('anonymous')
    setError(null)
  }

  async function refresh() {
    const stored = readStoredAuthSession()
    if (!stored) {
      resetControlPlaneBootstrap()
      resetControlPlaneSchema()
      setSession(null)
      setUser(null)
      setStatus('anonymous')
      return
    }
    const resolvedUser = await fetchCurrentUser(stored.accessToken)
    replaceSessionUser(resolvedUser)
    resetControlPlaneBootstrap()
    resetControlPlaneSchema()
    setSession({ ...stored, user: resolvedUser })
    setUser(resolvedUser)
    setStatus('authenticated')
    setError(null)
    bootstrapControlPlane()
  }

  return (
    <AuthContext.Provider
      value={{
        status,
        user,
        session,
        error,
        loginDev,
        logout,
        refresh,
        canAccessPocketHive: access.value?.canAccessPocketHive === true,
        canRunPocketHive: access.value?.canRunPocketHive === true,
        isAuthAdmin: access.value?.canManageUsers === true,
        accessStatus: access.status,
        accessError: access.error,
        reloadAccess: access.reload,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return value
}
