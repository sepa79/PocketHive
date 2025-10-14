import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DocPage from './DocPage'

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({
      svg: '<svg data-testid="mermaid-output"></svg>'
    })
  }
}))

import mermaid from 'mermaid'

const mockedMermaid = mermaid as unknown as {
  initialize: ReturnType<typeof vi.fn>
  render: ReturnType<typeof vi.fn>
}

describe('DocPage', () => {
  const originalFetch = global.fetch

  beforeEach(() => {
    const sampleMarkdown = `# Sample Doc\n\n| Column | Value |\n| --- | --- |\n| A | B |\n\n\`\`\`mermaid\ngraph TD;A-->B;\n\`\`\`\n`
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(sampleMarkdown)
    }) as unknown as typeof fetch
  })

  afterEach(() => {
    global.fetch = originalFetch
    vi.clearAllMocks()
  })

  it('renders markdown content with tables and mermaid diagrams', async () => {
    render(
      <MemoryRouter initialEntries={['/docs/readme']}>
        <Routes>
          <Route path="/docs/:docId" element={<DocPage />} />
        </Routes>
      </MemoryRouter>
    )

    expect(await screen.findByRole('heading', { name: 'Sample Doc' })).toBeInTheDocument()
    expect(await screen.findByRole('table')).toBeInTheDocument()

    await waitFor(() => {
      const diagramHost = screen.getByTestId('mermaid-diagram')
      expect(diagramHost.innerHTML).toContain('mermaid-output')
    })

    expect(mockedMermaid.render).toHaveBeenCalled()
    expect(mockedMermaid.initialize).toHaveBeenCalled()
  })
})
