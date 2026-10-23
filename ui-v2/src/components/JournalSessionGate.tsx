import type { ReactNode } from 'react'
import { useAuth } from '../lib/authContext'
import { BrowserSignInActions } from './BrowserSignInActions'

/**
 * Responsibility: wait for identity before mounting protected journal routes.
 * Must not: fetch journal data, infer permissions or replace the exact run URL.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
export function JournalSessionGate({ children }: { children: ReactNode }) {
  const auth = useAuth()
  if (auth.status === 'authenticated') return children
  return <div className="page"><h1 className="h1">PocketHive journal</h1>
    {auth.status === 'loading' ? <p role="status">Restoring your PocketHive sign-in…</p>
      : <><p>Sign in to view this run. Its address stays unchanged.</p><BrowserSignInActions /></>}
    {auth.error ? <p role="alert">{auth.error}</p> : null}
  </div>
}
