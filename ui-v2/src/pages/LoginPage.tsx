import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/authContext'

export function LoginPage() {
  const navigate = useNavigate()
  const auth = useAuth()
  const [username, setUsername] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmed = username.trim()
    if (!trimmed) {
      setError('Username is required')
      return
    }

    try {
      setSubmitting(true)
      setError(null)
      await auth.loginDev(trimmed)
      navigate('/', { replace: true })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Login failed')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="page">
      <h1 className="h1">Login</h1>
      <div className="muted">Shared auth-service dev login for UI v2. PocketHive permissions are resolved from the returned user grants.</div>
      <div className="card loginCard" style={{ marginTop: 12, maxWidth: 560 }}>
        <div className="kv">
          <div className="k">Session</div>
          <div className="v">
            {auth.status === 'authenticated' ? `${auth.user?.displayName ?? auth.user?.username}` : auth.status}
          </div>
        </div>
        <div className="kv" style={{ marginTop: 8 }}>
          <div className="k">Provider</div>
          <div className="v">{auth.user?.authProvider ?? 'DEV'}</div>
        </div>
        <div className="kv" style={{ marginTop: 8 }}>
          <div className="k">Permissions</div>
          <div className="v">
            {auth.user?.grants.length ? auth.user.grants.map((grant) => grant.permission).join(', ') : '(none)'}
          </div>
        </div>

        <form className="loginForm" onSubmit={onSubmit}>
          <label className="loginField">
            <span className="k">Username</span>
            <input
              className="textInput"
              placeholder="local-admin"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              disabled={submitting}
            />
          </label>
          <div className="loginActions">
            <button type="submit" className="btnPrimary" disabled={submitting}>
              {submitting ? 'Signing in...' : 'Sign in (DEV)'}
            </button>
            {auth.status === 'authenticated' ? (
              <button
                type="button"
                className="btnSecondary"
                disabled={submitting}
                onClick={() => {
                  auth.logout()
                  setError(null)
                }}
              >
                Sign out
              </button>
            ) : null}
          </div>
        </form>

        {error || auth.error ? (
          <div className="loginError" role="alert">
            {error ?? auth.error}
          </div>
        ) : null}
      </div>
    </div>
  )
}
