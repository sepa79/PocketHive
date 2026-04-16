import { useEffect, useState, type ReactNode } from 'react'
import type { EditorProps } from '@monaco-editor/react'
import { initMonaco } from '../lib/monaco/bootstrap'

type MonacoEditorComponent = typeof import('@monaco-editor/react').default

function defaultFallback(message: ReactNode, detail?: string, className?: string) {
  return (
    <div className={['monacoFallback', className].filter(Boolean).join(' ')}>
      <div className="monacoFallbackBody">
        <div className="muted">{message}</div>
        {detail ? <div className="monacoFallbackDetail">{detail}</div> : null}
      </div>
    </div>
  )
}

function pickErrorMessage(error: unknown) {
  if (error instanceof Error) {
    const message = error.message.trim()
    return message.length > 0 ? message : 'Unknown Monaco initialization error'
  }
  return 'Unknown Monaco initialization error'
}

export function MonacoEditorHost(props: EditorProps) {
  const [Editor, setEditor] = useState<MonacoEditorComponent | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    Promise.all([initMonaco(), import('@monaco-editor/react')])
      .then(([, module]) => {
        if (cancelled) return
        setEditor(() => module.default)
        setError(null)
      })
      .catch((loadError: unknown) => {
        console.error('Failed to load local Monaco editor', loadError)
        if (cancelled) return
        setEditor(null)
        setError(pickErrorMessage(loadError))
      })

    return () => {
      cancelled = true
    }
  }, [])

  if (error) {
    return defaultFallback('Local editor failed to load.', error, props.className)
  }

  if (!Editor) {
    return typeof props.loading === 'undefined'
      ? defaultFallback('Loading editor…', undefined, props.className)
      : <>{props.loading}</>
  }

  return <Editor {...props} />
}
