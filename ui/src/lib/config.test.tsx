/**
 * @vitest-environment jsdom
 */
import { afterEach, describe, expect, test } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'

import { ConfigProvider, getConfig, setConfig, useConfig, type UIConfig } from './config'

function RabbitEndpoint() {
  const { rabbitmq } = useConfig()
  return <span data-testid="rabbit-endpoint">{rabbitmq}</span>
}

const initialConfig: UIConfig = { ...getConfig() }

describe('config', () => {
  afterEach(() => {
    act(() => {
      setConfig(initialConfig)
    })
  })

  test('notifies context consumers when config updates', () => {
    render(
      <ConfigProvider>
        <RabbitEndpoint />
      </ConfigProvider>,
    )

    const original = initialConfig.rabbitmq
    expect(screen.getByTestId('rabbit-endpoint')).toHaveTextContent(original)

    const updated = 'http://example.test:5672/'

    act(() => {
      setConfig({ rabbitmq: updated })
    })

    expect(screen.getByTestId('rabbit-endpoint')).toHaveTextContent(updated)
  })
})
