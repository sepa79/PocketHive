import type { PropsWithChildren } from 'react'

type Props = PropsWithChildren<{
  className?: string
  title?: string
  subtitle?: string
}>

export default function Card({ className = '', title, subtitle, children }: Props) {
  return (
    <section className={`rounded-2xl border border-phl-border dark:border-ph-border bg-phl-card dark:bg-ph-card shadow-ph ${className}`}>
      {(title || subtitle) && (
        <header className="border-b border-phl-border dark:border-ph-border px-5 py-4">
          <h2 className="text-lg font-semibold">{title}</h2>
          {subtitle && <p className="mt-1 text-sm text-phl-muted dark:text-ph-muted">{subtitle}</p>}
        </header>
      )}
      <div className="p-5">{children}</div>
    </section>
  )
}
