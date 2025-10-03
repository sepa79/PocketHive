/**
 * @vitest-environment jsdom
 */
import { describe, expect, test } from 'vitest'

import ScenarioRemote, { ScenarioApp } from './remoteEntry'

describe('scenario remote entry', () => {
  test('exports ScenarioApp as the default component', () => {
    expect(ScenarioRemote).toBe(ScenarioApp)
  })
})
