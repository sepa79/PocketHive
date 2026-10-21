import { PocketAuthError } from './PocketAuthError.js'

/**
 * Responsibility: open and navigate one PH confirmation tab from a user gesture.
 * Must not: send tokens between windows, infer callback ports or implement confirmation UI.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export class ConfirmationTab {
  private tab: Window | null = null

  reserve(): void {
    this.close()
    try {
      this.tab = window.open('about:blank', '_blank')
      if (this.tab) this.tab.opener = null
    } catch {
      this.close()
    }
  }

  navigate(url: string): void {
    try {
      if (!this.tab || this.tab.closed) throw new Error()
      this.tab.location.replace(url)
    } catch {
      this.close()
      throw new PocketAuthError('popup_blocked', 'PH confirmation could not open. Use the confirmation link or allow popups.')
    }
  }

  close(): void {
    try { this.tab?.close() } catch {}
    this.tab = null
  }

  release(): void { this.tab = null }
}
