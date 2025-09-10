import { describe, expect, it, beforeEach } from 'vitest'
import { useUIStore } from './store'

describe('useUIStore messageLimit', () => {
  beforeEach(() => {
    useUIStore.setState({ messageLimit: 100 })
  })

  it('has default value of 100', () => {
    expect(useUIStore.getState().messageLimit).toBe(100)
  })

  it('enforces minimum limit of 10', () => {
    useUIStore.getState().setMessageLimit(5)
    expect(useUIStore.getState().messageLimit).toBe(10)
  })

  it('enforces maximum limit of 500', () => {
    useUIStore.getState().setMessageLimit(600)
    expect(useUIStore.getState().messageLimit).toBe(500)
  })

  it('accepts valid values within range', () => {
    useUIStore.getState().setMessageLimit(250)
    expect(useUIStore.getState().messageLimit).toBe(250)
  })
})

describe('useUIStore debugMode', () => {
  beforeEach(() => {
    useUIStore.setState({ debugMode: false })
  })

  it('has default value of false', () => {
    expect(useUIStore.getState().debugMode).toBe(false)
  })

  it('toggles debug mode', () => {
    useUIStore.getState().toggleDebug()
    expect(useUIStore.getState().debugMode).toBe(true)
  })
})