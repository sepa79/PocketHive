import { Component, type ReactNode, Suspense } from 'react'

interface ScenarioHostProps {
  children: ReactNode
}

interface ScenarioErrorBoundaryProps {
  children: ReactNode
  fallback: ReactNode
}

interface ScenarioErrorBoundaryState {
  hasError: boolean
}

function ScenarioLoading() {
  return (
    <div className="flex h-full min-h-[360px] flex-1 items-center justify-center bg-slate-950">
      <span className="text-sm uppercase tracking-[0.3em] text-amber-300">Loading Scenario…</span>
    </div>
  )
}

function ScenarioError() {
  return (
    <div
      role="alert"
      className="flex h-full min-h-[360px] flex-1 flex-col items-center justify-center gap-2 bg-slate-950 text-center text-slate-200"
    >
      <p className="text-lg font-semibold text-rose-300">Failed to load Scenario Builder</p>
      <p className="text-sm text-slate-400">Refresh the page or contact the PocketHive team if this persists.</p>
    </div>
  )
}

class ScenarioErrorBoundary extends Component<ScenarioErrorBoundaryProps, ScenarioErrorBoundaryState> {
  state: ScenarioErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): ScenarioErrorBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: Error) {
    console.error('Scenario remote failed to render', error)
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback
    }

    return this.props.children
  }
}

export default function ScenarioHost({ children }: ScenarioHostProps) {
  return (
    <div className="flex h-full min-h-full flex-1 bg-slate-950 text-slate-100">
      <ScenarioErrorBoundary fallback={<ScenarioError />}>
        <Suspense fallback={<ScenarioLoading />}>{children}</Suspense>
      </ScenarioErrorBoundary>
    </div>
  )
}
