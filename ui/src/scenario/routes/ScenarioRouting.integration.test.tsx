import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import * as matchers from '@testing-library/jest-dom/matchers'

expect.extend(matchers)

import ScenarioApp from '../ScenarioApp'
import { ShellProviders, useUIStore } from '@ph/shell'
import { useAssetStore } from '../assets/assetStore'
import * as apiModule from '../../lib/api'

const renderWithRouter = (initialEntry: string) =>
  render(
    <ShellProviders>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/scenario/*" element={<ScenarioApp />} />
        </Routes>
      </MemoryRouter>
    </ShellProviders>,
    { onRender: () => undefined },
  )

const createDeferred = <T,>() => {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })

  return { promise, resolve, reject }
}

const resetStores = () => {
  useAssetStore.getState().reset()
  useUIStore.setState({ toast: null })
  if (typeof window !== 'undefined' && window.localStorage) {
    window.localStorage.removeItem('ph.scenario.assets')
  }
}

describe('Scenario routes', () => {
  let apiFetchSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    resetStores()
    apiFetchSpy = vi.spyOn(apiModule, 'apiFetch')
  })

  afterEach(() => {
    cleanup()
    apiFetchSpy.mockRestore()
    resetStores()
  })

  it('renders scenario list from the API', async () => {
    apiFetchSpy.mockResolvedValue(
      new Response(
        JSON.stringify([
          { id: 'baseline', name: 'Baseline scenario', description: 'Smoke test path' },
          { id: 'load-test', name: 'Load test', description: 'Heavy load scenario' },
        ]),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    )

    renderWithRouter('/scenario')

    expect(await screen.findByText('Manage scenarios')).toBeInTheDocument()
    expect(await screen.findByText('Baseline scenario')).toBeInTheDocument()
    expect(await screen.findByText('Load test')).toBeInTheDocument()
    expect(apiFetchSpy).toHaveBeenCalledWith('/scenario-manager/scenarios', { headers: { Accept: 'application/json' } })
  })

  it('saves a scenario and surfaces success feedback', async () => {
    const user = userEvent.setup()

    const sutAsset = { id: 'sut-1', name: 'System', entrypoint: 'image:latest', version: '1.0.0' }
    const datasetAsset = { id: 'data-1', name: 'Dataset', uri: 's3://bucket', format: 'json' }
    useAssetStore.getState().upsertSut(sutAsset)
    useAssetStore.getState().upsertDataset(datasetAsset)

    const responseScenario = {
      id: 'smoke',
      name: 'Smoke scenario',
      description: 'Baseline description',
      sutAssets: [sutAsset],
      datasetAssets: [datasetAsset],
      swarmTemplates: [],
    }

    apiFetchSpy.mockImplementation(async (path, init) => {
      if (path === '/scenario-manager/scenarios' && (!init || init.method === undefined)) {
        return new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      if (path === '/scenario-manager/scenarios' && init?.method === 'POST') {
        const body = JSON.parse((init.body as string) ?? '{}')
        expect(body).toMatchObject({
          id: 'smoke',
          name: 'Smoke scenario',
          sutAssets: [sutAsset],
          datasetAssets: [datasetAsset],
        })
        return new Response(JSON.stringify(responseScenario), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      if (path === '/scenario-manager/scenarios/smoke' && (!init || init.method === undefined)) {
        return new Response(JSON.stringify(responseScenario), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      throw new Error(`Unexpected request: ${String(path)}`)
    })

    renderWithRouter('/scenario/new')

    await user.type(await screen.findByLabelText(/Scenario ID/i), 'smoke')
    await user.type(await screen.findByLabelText(/Scenario Name/i), 'Smoke scenario')
    await user.type(await screen.findByLabelText(/Summary/i), 'Baseline description')

    await user.click(await screen.findByRole('button', { name: /Save Scenario/i }))

    await waitFor(() => expect(useUIStore.getState().toast).toBe('Scenario saved'))
    await waitFor(() => {
      const postCall = apiFetchSpy.mock.calls.find(
        ([requestPath, init]) => requestPath === '/scenario-manager/scenarios' && init?.method === 'POST',
      )
      expect(postCall).toBeTruthy()
    })
    await waitFor(() => {
      const detailCall = apiFetchSpy.mock.calls.find(([requestPath]) => requestPath === '/scenario-manager/scenarios/smoke')
      expect(detailCall).toBeTruthy()
    })
    const idInput = await screen.findByDisplayValue('smoke')
    expect(idInput).toHaveAttribute('disabled')
  })

  it('surfaces validation errors returned from the API', async () => {
    const user = userEvent.setup()

    apiFetchSpy.mockImplementation(async (path, init) => {
      if (path === '/scenario-manager/scenarios' && (!init || init.method === undefined)) {
        return new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      if (path === '/scenario-manager/scenarios' && init?.method === 'POST') {
        return new Response(JSON.stringify({ message: 'Scenario name already exists' }), {
          status: 422,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      throw new Error(`Unexpected request: ${String(path)}`)
    })

    renderWithRouter('/scenario/new')

    await user.type(await screen.findByLabelText(/Scenario ID/i), 'duplicate')
    await user.type(await screen.findByLabelText(/Scenario Name/i), 'Duplicate scenario')
    await user.click(await screen.findByRole('button', { name: /Save Scenario/i }))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Scenario name already exists'),
    )
    expect(useUIStore.getState().toast).toBe('Scenario name already exists')
  })

  it('shares toast updates between the host and a lazily loaded remote bundle', async () => {
    useUIStore.setState({ toast: null })

    const hostStore = useUIStore
    const toastMessage = 'Remote toast from remote bundle'

    vi.resetModules()
    const remoteShell = (await import('@ph/shell')) as typeof import('@ph/shell')
    remoteShell.useUIStore.getState().setToast(toastMessage)

    expect(remoteShell.useUIStore).toBe(hostStore)
    expect(hostStore.getState().toast).toBe(toastMessage)
  })

  it('disables saving until the scenario payload hydrates when editing', async () => {
    const user = userEvent.setup()

    const scenarioResponse = {
      id: 'baseline',
      name: 'Baseline scenario',
      description: 'Existing scenario',
      sutAssets: [
        { id: 'sut-1', name: 'System', entrypoint: 'image:latest', version: '1.0.0' },
      ],
      datasetAssets: [
        { id: 'dataset-1', name: 'Dataset', uri: 's3://bucket', format: 'json' },
      ],
      swarmTemplates: [],
    }

    const detailDeferred = createDeferred<Response>()

    apiFetchSpy.mockImplementation(async (path, init) => {
      if (path === '/scenario-manager/scenarios' && (!init || init.method === undefined)) {
        return new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      if (path === '/scenario-manager/scenarios/baseline' && (!init || init.method === undefined)) {
        return detailDeferred.promise
      }

      if (path === '/scenario-manager/scenarios/baseline' && init?.method === 'PUT') {
        return new Response(JSON.stringify(scenarioResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      throw new Error(`Unexpected request: ${String(path)}`)
    })

    renderWithRouter('/scenario/edit/baseline')

    const loadingButton = await screen.findByRole('button', { name: /Loading scenario…/i })
    expect(loadingButton).toBeDisabled()

    await user.click(loadingButton)

    expect(
      apiFetchSpy.mock.calls.some(
        ([requestPath, init]) => requestPath === '/scenario-manager/scenarios/baseline' && init?.method === 'PUT',
      ),
    ).toBe(false)

    detailDeferred.resolve(
      new Response(JSON.stringify(scenarioResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    await waitFor(() => expect(screen.getByRole('button', { name: /Save Scenario/i })).not.toBeDisabled())

    await user.click(screen.getByRole('button', { name: /Save Scenario/i }))

    await waitFor(() =>
      expect(
        apiFetchSpy.mock.calls.some(
          ([requestPath, init]) => requestPath === '/scenario-manager/scenarios/baseline' && init?.method === 'PUT',
        ),
      ).toBe(true),
    )
  })
})
