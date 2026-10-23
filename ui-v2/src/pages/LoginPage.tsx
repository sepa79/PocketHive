import { useAuth } from '../lib/authContext'
import { BrowserSignInActions } from '../components/BrowserSignInActions'

/**
 * Responsibility: present shared browser sign-in and the current identity.
 * Must not: collect provider credentials or decide product permissions.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
export function LoginPage() {
  const auth = useAuth()
  return <div className="page">
    <h1 className="h1">Login</h1>
    <div className="muted">Confirm in a PocketHive Auth tab. This page stays open and signs in automatically after confirmation.</div>
    <div className="card loginCard" style={{ marginTop: 12, maxWidth: 560 }}>
      <div className="kv"><div className="k">Session</div><div className="v">{auth.user?.displayName ?? auth.status}</div></div>
      <div className="kv" style={{ marginTop: 8 }}><div className="k">Provider</div><div className="v">{auth.user?.authProvider ?? '—'}</div></div>
      <div className="kv" style={{ marginTop: 8 }}><div className="k">Permissions</div>
        <div className="v">{auth.user?.grants.length ? auth.user.grants.map(grant => grant.permission).join(', ') : '(none)'}</div>
      </div>
      <BrowserSignInActions />
      {auth.error ? <div className="loginError" role="alert">{auth.error}</div> : null}
    </div>
  </div>
}
