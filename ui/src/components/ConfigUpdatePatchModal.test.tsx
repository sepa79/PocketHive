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
})

