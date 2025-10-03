import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import * as matchers from '@testing-library/jest-dom/matchers'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

expect.extend(matchers)

import ScenarioApp from './ScenarioApp'
import { ShellProviders } from '@ph/shell'
import { useAssetStore } from './assets/assetStore'

const resetAssets = () => {
  useAssetStore.getState().reset()
  if (typeof window !== 'undefined' && window.localStorage) {
    window.localStorage.removeItem('ph.scenario.assets')
  }
}

describe('ScenarioApp asset workflows', () => {
  beforeEach(() => {
    resetAssets()
  })

  afterEach(() => {
    cleanup()
    resetAssets()
  })

  it('disables submission until required fields are populated', async () => {
    const user = userEvent.setup()

    render(
      <ShellProviders>
        <MemoryRouter initialEntries={['/scenario/new']}>
          <Routes>
            <Route path="/scenario/*" element={<ScenarioApp />} />
          </Routes>
        </MemoryRouter>
      </ShellProviders>,
    )
    const newSystemButton = await screen.findByRole('button', { name: /new system/i })
    await user.click(newSystemButton)
    const systemDialog = await screen.findByRole('dialog')
    const systemSubmit = await within(systemDialog).findByRole('button', { name: /create system/i })
    expect(systemSubmit).toBeDisabled()

    await user.type(await within(systemDialog).findByLabelText(/Identifier/i), 'sut-1')
    await user.type(await within(systemDialog).findByLabelText(/Name/i), 'Primary system')
    await user.type(await within(systemDialog).findByLabelText(/Entry point/i), 'image:latest')
    await user.type(await within(systemDialog).findByLabelText(/Version/i), 'v1')
    expect(systemSubmit).toBeEnabled()
    await user.click(await within(systemDialog).findByRole('button', { name: /cancel/i }))

    await user.click(await screen.findByRole('button', { name: /datasets/i }))
    await user.click(await screen.findByRole('button', { name: /new dataset/i }))
    const datasetDialog = await screen.findByRole('dialog')
    const datasetSubmit = await within(datasetDialog).findByRole('button', { name: /create dataset/i })
    expect(datasetSubmit).toBeDisabled()

    await user.type(await within(datasetDialog).findByLabelText(/Identifier/i), 'dataset-1')
    await user.type(await within(datasetDialog).findByLabelText(/Name/i), 'Dataset')
    await user.type(await within(datasetDialog).findByLabelText(/Source URI/i), 's3://bucket/data.json')
    await user.type(await within(datasetDialog).findByLabelText(/Format/i), 'json')
    expect(datasetSubmit).toBeEnabled()
    await user.click(await within(datasetDialog).findByRole('button', { name: /cancel/i }))

    useAssetStore.getState().upsertSut({
      id: 'sut-1',
      name: 'Primary',
      entrypoint: 'image:latest',
      version: 'v1',
    })
    useAssetStore.getState().upsertDataset({
      id: 'dataset-1',
      name: 'Dataset',
      uri: 's3://bucket/data.json',
      format: 'json',
    })

    await user.click(await screen.findByRole('button', { name: /swarm templates/i }))
    await user.click(await screen.findByRole('button', { name: /new template/i }))
    const templateDialog = await screen.findByRole('dialog')
    const templateSubmit = await within(templateDialog).findByRole('button', { name: /create template/i })
    expect(templateSubmit).toBeDisabled()

    await user.type(within(templateDialog).getByLabelText(/Identifier/i), 'template-1')
    await user.type(within(templateDialog).getByLabelText(/Name/i), 'Template')
    expect(templateSubmit).toBeEnabled()
  })

  it('supports create, edit, and delete flows for assets', async () => {
    const user = userEvent.setup()

    render(
      <ShellProviders>
        <MemoryRouter initialEntries={['/scenario/new']}>
          <Routes>
            <Route path="/scenario/*" element={<ScenarioApp />} />
          </Routes>
        </MemoryRouter>
      </ShellProviders>,
    )

    await user.click(await screen.findByRole('button', { name: /new system/i }))
    const systemDialog = await screen.findByRole('dialog')
    await user.type(await within(systemDialog).findByLabelText(/Identifier/i), 'sut-1')
    await user.type(await within(systemDialog).findByLabelText(/Name/i), 'Primary system')
    await user.type(await within(systemDialog).findByLabelText(/Entry point/i), 'image:latest')
    await user.type(await within(systemDialog).findByLabelText(/Version/i), 'v1')
    await user.click(await within(systemDialog).findByRole('button', { name: /create system/i }))

    expect(screen.getByText('Primary system')).toBeInTheDocument()
    expect(screen.getByText('sut-1')).toBeInTheDocument()


    await user.click(await screen.findByRole('button', { name: /datasets/i }))
    await user.click(await screen.findByRole('button', { name: /new dataset/i }))
    const datasetDialog = await screen.findByRole('dialog')
    await user.type(await within(datasetDialog).findByLabelText(/Identifier/i), 'dataset-1')
    await user.type(await within(datasetDialog).findByLabelText(/Name/i), 'Main dataset')
    await user.type(await within(datasetDialog).findByLabelText(/Source URI/i), 's3://bucket/data.json')
    await user.type(await within(datasetDialog).findByLabelText(/Format/i), 'json')
    await user.click(await within(datasetDialog).findByRole('button', { name: /create dataset/i }))

    expect(screen.getByText('Main dataset')).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: /swarm templates/i }))
    await user.click(await screen.findByRole('button', { name: /new template/i }))
    const templateDialog = await screen.findByRole('dialog')
    await user.type(await within(templateDialog).findByLabelText(/Identifier/i), 'template-1')
    await user.type(await within(templateDialog).findByLabelText(/Name/i), 'Load test template')
    await user.click(await within(templateDialog).findByRole('button', { name: /create template/i }))

    expect(screen.getByText('Load test template')).toBeInTheDocument()
    expect(screen.getByText('Swarm size')).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: /systems/i }))
    await user.click(await screen.findByRole('button', { name: /^edit$/i }))
    const editDialog = await screen.findByRole('dialog')
    const nameField = within(editDialog).getByLabelText(/Name/i)
    await user.clear(nameField)
    await user.type(nameField, 'Primary system updated')
    await user.click(await within(editDialog).findByRole('button', { name: /save changes/i }))

    expect(screen.getByText('Primary system updated')).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: /datasets/i }))
    await user.click((await screen.findAllByRole('button', { name: /^delete$/i }))[0]!)

    expect(screen.queryByText('Main dataset')).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: /swarm templates/i }))
    expect(screen.queryByText('Load test template')).not.toBeInTheDocument()
  })
})
