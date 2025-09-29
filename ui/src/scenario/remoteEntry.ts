import { StrictMode, createElement } from 'react'
import { createRoot } from 'react-dom/client'

import ScenarioApp from './ScenarioApp'

export type MountReturn = {
  unmount: () => void
}

export const mount = (element: HTMLElement): MountReturn => {
  const root = createRoot(element)
  root.render(createElement(StrictMode, undefined, createElement(ScenarioApp)))

  return {
    unmount: () => root.unmount()
  }
}

export { ScenarioApp }

if (import.meta.env.MODE === 'scenario' && import.meta.env.DEV) {
  const container = document.getElementById('root')
  if (container) {
    mount(container)
  }
}
