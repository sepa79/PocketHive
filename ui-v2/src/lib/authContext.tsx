import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import {
  clearAuthSession,
  fetchCurrentUser,
  type AuthGrantMatch,
  loginDevUser,
  readStoredAuthSession,
  replaceSessionUser,
  type AuthSession,
  type AuthenticatedUser,
  userCanManagePocketHive,
  userCanManagePocketHiveFolder,
  userCanManagePocketHiveResource,
  userCanRunAnywhere,
  userCanRunPocketHiveResource,
  userCanViewPocketHive,
  userCanViewPocketHiveResource,
  userHasGrant,
} from './auth'
import {
  AuthProducts,
  AuthServicePermissionIds,
  AuthServiceResourceSelectors,
  AuthServiceResourceTypes,
} from './authContracts'

type AuthStatus = 'loading' | 'anonymous' | 'authenticated'

type AuthContextValue = {
  status: AuthStatus
  user: AuthenticatedUser | null
  session: AuthSession | null
  error: string | null
  loginDev: (username: string) => Promise<void>
  logout: () => void
  refresh: () => Promise<void>
  hasPermission: (permission: string) => boolean
  hasGrant: (match: AuthGrantMatch) => boolean
  canAccessPocketHive: boolean
  canRunPocketHive: boolean
  canManagePocketHive: boolean
  canViewBundle: (bundlePath: string | null | undefined, folderPath: string | null | undefined) => boolean
  canRunBundle: (bundlePath: string | null | undefined, folderPath: string | null | undefined) => boolean
  canManageBundle: (bundlePath: string | null | undefined, folderPath: string | null | undefined) => boolean
  canManageFolder: (folderPath: string | null | undefined) => boolean
  isAuthAdmin: boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

function hasPermission(user: AuthenticatedUser | null, permission: string): boolean {
  if (!user) return false
  return user.grants.some((grant) => grant.product === AuthProducts.POCKETHIVE && grant.permission === permission)
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [session, setSession] = useState<AuthSession | null>(() => readStoredAuthSession())
  const [user, setUser] = useState<AuthenticatedUser | null>(() => readStoredAuthSession()?.user ?? null)
  const [error, setError] = useState<string | null>(null)

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
      } catch (e) {
        if (cancelled) return
        clearAuthSession()
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
    setSession(nextSession)
    setUser(nextSession.user)
    setStatus('authenticated')
  }

  function logout() {
    clearAuthSession()
    setSession(null)
    setUser(null)
    setStatus('anonymous')
    setError(null)
  }

  async function refresh() {
    const stored = readStoredAuthSession()
    if (!stored) {
      setSession(null)
      setUser(null)
      setStatus('anonymous')
      return
    }
    const resolvedUser = await fetchCurrentUser(stored.accessToken)
    replaceSessionUser(resolvedUser)
    setSession({ ...stored, user: resolvedUser })
    setUser(resolvedUser)
    setStatus('authenticated')
    setError(null)
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
        hasPermission: (permission) => hasPermission(user, permission),
        hasGrant: (match) => userHasGrant(user, match),
        canAccessPocketHive: userCanViewPocketHive(user),
        canRunPocketHive: userCanRunAnywhere(user),
        canManagePocketHive: userCanManagePocketHive(user),
        canViewBundle: (bundlePath, folderPath) => userCanViewPocketHiveResource(user, { bundlePath, folderPath }),
        canRunBundle: (bundlePath, folderPath) => userCanRunPocketHiveResource(user, { bundlePath, folderPath }),
        canManageBundle: (bundlePath, folderPath) => userCanManagePocketHiveResource(user, { bundlePath, folderPath }),
        canManageFolder: (folderPath) => userCanManagePocketHiveFolder(user, folderPath),
        isAuthAdmin: userHasGrant(user, {
          product: AuthProducts.AUTH_SERVICE,
          permission: AuthServicePermissionIds.ADMIN,
          resourceType: AuthServiceResourceTypes.GLOBAL,
          resourceSelector: AuthServiceResourceSelectors.GLOBAL,
        }),
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
