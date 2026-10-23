import { useAuth } from '../lib/authContext'
import { AccessObservationNotice } from './AccessObservationNotice'

/**
 * Responsibility: present the current global identity error and access observation.
 * Must not: calculate permissions or mutate login state.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function AuthAccessNotice() {
  const auth = useAuth()
  return <>
    {auth.error ? <p role="alert">{auth.error} <a href="/login">Sign in</a></p> : null}
    <AccessObservationNotice status={auth.accessStatus} error={auth.accessError} retry={auth.reloadAccess} />
  </>
}
