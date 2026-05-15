import loader from '@monaco-editor/loader'

type MonacoInstance = typeof import('monaco-editor')

let monacoInitPromise: Promise<MonacoInstance> | null = null

function localVsPath() {
  const base = import.meta.env.BASE_URL
  return `${base.endsWith('/') ? base : `${base}/`}monaco/vs`
}

export function initMonaco(): Promise<MonacoInstance> {
  if (typeof window === 'undefined') {
    return Promise.reject(new Error('Monaco requires a browser environment'))
  }
  if (monacoInitPromise) {
    return monacoInitPromise
  }

  loader.config({ paths: { vs: localVsPath() } })
  monacoInitPromise = loader.init().catch((error: unknown) => {
    monacoInitPromise = null
    throw error instanceof Error ? error : new Error('Failed to initialize local Monaco')
  })

  return monacoInitPromise
}
