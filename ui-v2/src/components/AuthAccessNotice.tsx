import { useAuth } from '../lib/authContext'
import { AccessObservationNotice } from './AccessObservationNotice'

/**
 * Responsibility: present the current global access observation.
 * Must not: calculate permissions or mutate login state.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function AuthAccessNotice() {
  const auth = useAuth()
  return <AccessObservationNotice status={auth.accessStatus} error={auth.accessError} retry={auth.reloadAccess} />
}
