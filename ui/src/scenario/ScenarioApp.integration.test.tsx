import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import * as matchers from '@testing-library/jest-dom/matchers'

expect.extend(matchers)

import ScenarioApp from './ScenarioApp'
import { ShellProviders, getConfig, setConfig, useUIStore } from '@ph/shell'

describe('ScenarioApp shell integration', () => {
  const snapshotConfig = () => ({ ...getConfig() })
  let baselineConfig = snapshotConfig()

  beforeEach(() => {
    baselineConfig = snapshotConfig()
    setConfig(baselineConfig)
    useUIStore.setState({ messageLimit: 100 })
  })

  afterEach(() => {
    cleanup()
    setConfig(baselineConfig)
    useUIStore.setState({ messageLimit: 100 })
  })

  it('reads configuration and store values from the host shell', () => {
    setConfig({
      rabbitmq: 'https://host.example/rabbitmq',
      prometheus: 'https://host.example/prometheus',
    })
    useUIStore.setState({ messageLimit: 256 })

    render(
      <ShellProviders>
        <ScenarioApp />
      </ShellProviders>
    )

    expect(screen.getByTestId('config-rabbitmq')).toHaveTextContent('https://host.example/rabbitmq')
    expect(screen.getByTestId('config-prometheus')).toHaveTextContent('https://host.example/prometheus')
    expect(screen.getByTestId('ui-message-limit')).toHaveTextContent('256')
  })
})
