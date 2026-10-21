import { useAuth } from '../lib/authContext'

/**
 * Responsibility: present shared device sign-in, confirmation and cancellation actions.
 * Must not: poll, persist credentials, navigate the original page or decide permissions.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
export function BrowserSignInActions() {
  const auth = useAuth()
  return <div className="loginForm">
    <div className="loginActions">
      {auth.status !== 'authenticated' && !auth.signingIn ? <button type="button" className="btnPrimary"
        disabled={auth.status === 'loading'} onClick={() => { void auth.login() }}>Sign in with PocketHive</button> : null}
      {auth.signingIn ? <>
        <span role="status">Confirm sign-in in the PocketHive tab.</span>
        {auth.confirmationUrl ? <button type="button" className="btnPrimary" onClick={auth.openConfirmation}>Open confirmation</button> : null}
        <button type="button" className="btnSecondary" onClick={auth.cancelSignIn}>Cancel sign-in</button>
      </> : null}
      {auth.status === 'authenticated' ? <button type="button" className="btnSecondary"
        onClick={() => { void auth.logout() }}>Sign out</button> : null}
      {!auth.signingIn && auth.confirmationUrl ? <button type="button" className="btnSecondary" onClick={auth.openConfirmation}>Open shared sign-out</button> : null}
    </div>
  </div>
}
