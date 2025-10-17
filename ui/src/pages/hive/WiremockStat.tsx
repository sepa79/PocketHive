import type { ReactNode } from 'react'

interface Props {
  label: string
  value: ReactNode
  error?: boolean
}

export default function WiremockStat({ label, value, error }: Props) {
  return (
    <div className="rounded border border-white/10 bg-white/5 p-3">
      <div className="text-[0.65rem] uppercase tracking-wide text-white/40">{label}</div>
      <div className={`mt-1 text-sm font-medium ${error ? 'text-red-400' : 'text-white'}`}>
        {value}
      </div>
    </div>
  )
}
