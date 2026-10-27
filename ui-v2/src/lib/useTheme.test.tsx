import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getTheme, installTheme, setTheme } from './theme'
import { useTheme } from './useTheme'
import { useMonacoTheme } from './useMonacoTheme'

beforeEach(() => {
  window.sessionStorage.clear()
  installTheme()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.sessionStorage.clear()
  installTheme()
})

describe('applied theme projection', () => {
  it('updates independent consumers and Monaco together in both directions', async () => {
    const first = renderHook(useTheme)
    const second = renderHook(useTheme)
    const editor = renderHook(useMonacoTheme)

    expect([first.result.current, second.result.current, editor.result.current]).toEqual(['dark', 'dark', 'vs-dark'])

    await act(async () => { setTheme('light') })
    expect([first.result.current, second.result.current, editor.result.current]).toEqual(['light', 'light', 'vs'])

    await act(async () => { setTheme('dark') })
    expect([first.result.current, second.result.current, editor.result.current]).toEqual(['dark', 'dark', 'vs-dark'])
  })

  it('follows the applied theme when preference persistence is unavailable', async () => {
    const theme = renderHook(useTheme)
    const editor = renderHook(useMonacoTheme)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage unavailable') })

    await act(async () => { setTheme('light') })

    expect(document.documentElement.dataset.theme).toBe('light')
    expect(theme.result.current).toBe('light')
    expect(editor.result.current).toBe('vs')
  })

  it('restores the persisted preference at application bootstrap', () => {
    setTheme('light')
    document.documentElement.removeAttribute('data-theme')

    installTheme()

    expect(getTheme()).toBe('light')
  })

  it('rejects missing or invalid applied theme state', () => {
    document.documentElement.removeAttribute('data-theme')
    expect(getTheme).toThrow('PocketHive theme must be installed')

    document.documentElement.dataset.theme = 'unsupported'
    expect(getTheme).toThrow('PocketHive theme must be installed')
  })
})
