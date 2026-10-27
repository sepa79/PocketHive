import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { setTheme } from '../../lib/theme'
import { SwarmTopologyCanvas } from './SwarmTopologyCanvas'

beforeEach(() => {
  setTheme('dark')
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.sessionStorage.clear()
  delete document.documentElement.dataset.theme
})

it('updates the existing ReactFlow canvas and native controls when the applied theme changes', async () => {
  const view = render(<SwarmTopologyCanvas
    nodes={[]}
    edges={[]}
    selectedNodeId={null}
    onNodesChange={vi.fn()}
    onEdgesChange={vi.fn()}
    onInit={vi.fn()}
    onSelectNode={vi.fn()}
    onNodePositionChange={vi.fn()}
  />)
  const canvas = view.container.querySelector('.react-flow')
  const zoomIn = screen.getByRole('button', { name: /zoom in/i })
  expect(canvas?.classList.contains('dark')).toBe(true)

  await act(async () => setTheme('light'))
  expect(canvas?.classList.contains('light')).toBe(true)
  expect(canvas?.classList.contains('dark')).toBe(false)
  expect(view.container.querySelector('.react-flow')).toBe(canvas)
  expect(screen.getByRole('button', { name: /zoom in/i })).toBe(zoomIn)

  await act(async () => setTheme('dark'))
  expect(canvas?.classList.contains('dark')).toBe(true)
  expect(canvas?.classList.contains('light')).toBe(false)
  expect(view.container.querySelector('.react-flow')).toBe(canvas)
})
