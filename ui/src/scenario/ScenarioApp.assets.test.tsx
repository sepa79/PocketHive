import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import * as matchers from '@testing-library/jest-dom/matchers'

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
        <ScenarioApp />
      </ShellProviders>,
    )

    await user.click(screen.getByRole('button', { name: /new system/i }))
    const systemSubmit = screen.getByRole('button', { name: /create system/i })
    expect(systemSubmit).toBeDisabled()

    await user.type(screen.getByLabelText(/Identifier/i), 'sut-1')
    await user.type(screen.getByLabelText(/Name/i), 'Primary system')
    await user.type(screen.getByLabelText(/Entry point/i), 'image:latest')
    await user.type(screen.getByLabelText(/Version/i), 'v1')
    expect(systemSubmit).toBeEnabled()
    await user.click(screen.getByRole('button', { name: /cancel/i }))

    await user.click(screen.getByRole('button', { name: /datasets/i }))
    await user.click(screen.getByRole('button', { name: /new dataset/i }))
    const datasetSubmit = screen.getByRole('button', { name: /create dataset/i })
    expect(datasetSubmit).toBeDisabled()

    await user.type(screen.getByLabelText(/Identifier/i), 'dataset-1')
    await user.type(screen.getByLabelText(/Name/i), 'Dataset')
    await user.type(screen.getByLabelText(/Source URI/i), 's3://bucket/data.json')
    await user.type(screen.getByLabelText(/Format/i), 'json')
    expect(datasetSubmit).toBeEnabled()
    await user.click(screen.getByRole('button', { name: /cancel/i }))

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

    await user.click(screen.getByRole('button', { name: /swarm templates/i }))
    await user.click(screen.getByRole('button', { name: /new template/i }))
    const templateSubmit = screen.getByRole('button', { name: /create template/i })
    expect(templateSubmit).toBeDisabled()

    await user.type(screen.getByLabelText(/Identifier/i), 'template-1')
    await user.type(screen.getByLabelText(/Name/i), 'Template')
    expect(templateSubmit).toBeEnabled()
  })

  it('supports create, edit, and delete flows for assets', async () => {
    const user = userEvent.setup()

    render(
      <ShellProviders>
        <ScenarioApp />
      </ShellProviders>,
    )

    await user.click(screen.getByRole('button', { name: /new system/i }))
    await user.type(screen.getByLabelText(/Identifier/i), 'sut-1')
    await user.type(screen.getByLabelText(/Name/i), 'Primary system')
    await user.type(screen.getByLabelText(/Entry point/i), 'image:latest')
    await user.type(screen.getByLabelText(/Version/i), 'v1')
    await user.click(screen.getByRole('button', { name: /create system/i }))

    expect(screen.getByText('Primary system')).toBeInTheDocument()
    expect(screen.getByText('sut-1')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /datasets/i }))
    await user.click(screen.getByRole('button', { name: /new dataset/i }))
    await user.type(screen.getByLabelText(/Identifier/i), 'dataset-1')
    await user.type(screen.getByLabelText(/Name/i), 'Main dataset')
    await user.type(screen.getByLabelText(/Source URI/i), 's3://bucket/data.json')
    await user.type(screen.getByLabelText(/Format/i), 'json')
    await user.click(screen.getByRole('button', { name: /create dataset/i }))

    expect(screen.getByText('Main dataset')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /swarm templates/i }))
    await user.click(screen.getByRole('button', { name: /new template/i }))
    await user.type(screen.getByLabelText(/Identifier/i), 'template-1')
    await user.type(screen.getByLabelText(/Name/i), 'Load test template')
    await user.click(screen.getByRole('button', { name: /create template/i }))

    expect(screen.getByText('Load test template')).toBeInTheDocument()
    expect(screen.getByText('Swarm size')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /systems/i }))
    await user.click(screen.getByRole('button', { name: /^edit$/i }))
    const nameField = screen.getByLabelText(/Name/i)
    await user.clear(nameField)
    await user.type(nameField, 'Primary system updated')
    await user.click(screen.getByRole('button', { name: /save changes/i }))

    expect(screen.getByText('Primary system updated')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /datasets/i }))
    await user.click(screen.getAllByRole('button', { name: /^delete$/i })[0]!)

    expect(screen.queryByText('Main dataset')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /swarm templates/i }))
    expect(screen.queryByText('Load test template')).not.toBeInTheDocument()
  })
})
