import { StrictMode, createElement } from 'react'
import { createRoot } from 'react-dom/client'

import { ShellProviders } from '@ph/shell'
import ScenarioApp from './ScenarioApp'

export type MountReturn = {
  unmount: () => void
}

const renderScenarioApp = () =>
  createElement(
    StrictMode,
    undefined,
    createElement(ShellProviders, undefined, createElement(ScenarioApp))
  )

export const mount = (element: HTMLElement): MountReturn => {
  const root = createRoot(element)
  root.render(renderScenarioApp())

  return {
    unmount: () => root.unmount()
  }
}

export { ScenarioApp }
export default ScenarioApp

if (import.meta.env.MODE === 'scenario' && import.meta.env.DEV) {
  const container = document.getElementById('root')
  if (container) {
    mount(container)
  }
}
