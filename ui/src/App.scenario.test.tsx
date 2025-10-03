/**
 * @vitest-environment jsdom
 */
import { MemoryRouter } from 'react-router-dom'
import { render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { describe, expect, test, vi } from 'vitest'

vi.mock('@stomp/stompjs', () => ({
  Client: vi.fn().mockImplementation(() => ({
    activate: vi.fn(),
    deactivate: vi.fn(),
    configure: vi.fn(),
    onConnect: undefined,
    onStompError: undefined,
    onWebSocketClose: undefined,
    onWebSocketError: undefined,
  })),
}))

vi.mock('@ph/scenario/ScenarioApp', () => import('./__mocks__/scenarioAppRemote'))

const App = (await import('./App')).default

describe('Scenario route', () => {
  test('renders the scenario remote placeholder when visiting /scenario', async () => {
    render(
      <MemoryRouter initialEntries={['/scenario']}>
        <App />
      </MemoryRouter>,
    )

    expect(
      await screen.findByText(/Scenario Builder Placeholder/i),
    ).toBeInTheDocument()
  })
})
