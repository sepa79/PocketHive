import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfigUpdatePatchModal } from './ConfigUpdatePatchModal'
import type { CapabilityConfigEntry } from '../types/capabilities'

describe('ConfigUpdatePatchModal', () => {
  it('does not reset user edits when props refresh while open', async () => {
    const user = userEvent.setup()

    const entries: CapabilityConfigEntry[] = [
      {
        name: 'trafficPolicy.bufferGuard.enabled',
        type: 'boolean',
        default: false,
        ui: { label: 'Buffer guard enabled', group: 'Buffer guard' },
      },
    ]

    const { rerender } = render(
      <ConfigUpdatePatchModal
        open
        imageLabel="swarm-controller:latest"
        entries={entries}
        baseConfig={{ trafficPolicy: { bufferGuard: { enabled: true } } }}
        existingPatch={undefined}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    )

    const checkbox = screen.getByRole('checkbox', { name: 'Enabled' })
    expect(checkbox).toBeChecked()

    await user.click(checkbox)
    expect(checkbox).not.toBeChecked()

    rerender(
      <ConfigUpdatePatchModal
        open
        imageLabel="swarm-controller:latest"
        entries={[...entries]}
        baseConfig={{ trafficPolicy: { bufferGuard: { enabled: true } } }}
        existingPatch={undefined}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    )

    expect(screen.getByRole('checkbox', { name: 'Enabled' })).not.toBeChecked()
  })

  it('keeps weighted editor mounted when adding or clearing options', async () => {
    const user = userEvent.setup()

    const entries: CapabilityConfigEntry[] = [
      {
        name: 'worker.pick',
        type: 'text',
        default: "pickWeighted('redis-balance', 40, 'redis-topup', 40, 'redis-auth', 20)",
        multiline: true,
        ui: { label: 'Pick', group: 'General' },
      },
    ]

    render(
      <ConfigUpdatePatchModal
        open
        imageLabel="generator:latest"
        entries={entries}
        baseConfig={{}}
        existingPatch={undefined}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: 'Add option' })).toBeInTheDocument()

    const before = screen.getAllByPlaceholderText('value').length
    await user.click(screen.getByRole('button', { name: 'Add option' }))
    const after = screen.getAllByPlaceholderText('value').length
    expect(after).toBe(before + 1)

    const lastValueInput = screen.getAllByPlaceholderText('value')[after - 1]!
    expect((lastValueInput as HTMLInputElement).value).toMatch(/^OptionName/)

    await user.clear(lastValueInput)
    expect(screen.getByRole('button', { name: 'Add option' })).toBeInTheDocument()
    expect(screen.getAllByPlaceholderText('value').length).toBe(after)
  })
})
