import { useCallback, useEffect, useRef, useState } from 'react'
import { listAdminUsers, type AuthenticatedUser } from './auth'
import type { AccessObservation } from './AccessObservation'

/**
 * Responsibility: load the admin user list once per caller session or explicit reload.
 * Must not: decide permissions or reset forms when the same caller refreshes access.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function useAdminUsersLoader(callerId: string | null, token: string | null,
  accessStatus: AccessObservation<unknown>['status'], canManage: boolean,
  onLoaded: (users: AuthenticatedUser[], preferredId: string | null) => void) {
  const loadedFor = useRef<{ callerId: string; token: string } | null>(null)
  const generation = useRef(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const initialized = callerId !== null && token !== null &&
    loadedFor.current?.callerId === callerId && loadedFor.current?.token === token
  const reload = useCallback(async (preferredId: string | null = null) => {
    if (!callerId || !token || accessStatus !== 'ready' || !canManage) return
    const request = ++generation.current
    setLoading(true)
    setError(null)
    try {
      const users = await listAdminUsers()
      if (request !== generation.current) return
      onLoaded(users, preferredId)
      loadedFor.current = { callerId, token }
    } catch (failure) {
      if (request === generation.current) setError(failure instanceof Error ? failure.message : 'Failed to load users')
    } finally {
      if (request === generation.current) setLoading(false)
    }
  }, [callerId, token, accessStatus, canManage, onLoaded])
  useEffect(() => {
    setLoading(false)
    if (loadedFor.current?.callerId !== callerId || loadedFor.current?.token !== token) loadedFor.current = null
    if (!callerId || !token || (accessStatus === 'ready' && !canManage)) {
      loadedFor.current = null
    } else if (accessStatus === 'ready' && canManage &&
      (loadedFor.current?.callerId !== callerId || loadedFor.current?.token !== token)) {
      void reload()
    }
    return () => { generation.current++ }
  }, [callerId, token, accessStatus, canManage, reload])
  return { loading, error, initialized, reload }
}
