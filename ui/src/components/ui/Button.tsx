import React from 'react'

export type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost'
  size?: 'default' | 'icon'
}

export default function Button({
  variant = 'primary',
  size = 'default',
  className = '',
  ...props
}: ButtonProps) {
  const base =
    'inline-flex items-center justify-center rounded-xl text-sm font-semibold uppercase tracking-wide transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-phl-accent2 dark:focus-visible:ring-ph-accent2 focus-visible:ring-offset-phl-bg dark:focus-visible:ring-offset-ph-bg disabled:opacity-50 disabled:pointer-events-none active:translate-y-px'

  const variants: Record<typeof variant, string> = {
    primary:
      'text-phl-accent2 dark:text-ph-accent2 border border-phl-accent2/35 dark:border-ph-accent2/35 bg-[radial-gradient(120%_120%_at_10%_10%,rgba(34,211,238,0.25),rgba(34,211,238,0.06)_40%,rgba(255,255,255,0.05)_70%,rgba(0,0,0,0)_100%)] shadow-[0_0_20px_rgba(34,211,238,0.15)_inset,0_0_16px_rgba(34,211,238,0.18)] hover:bg-[radial-gradient(120%_120%_at_10%_10%,rgba(34,211,238,0.35),rgba(34,211,238,0.1)_50%,rgba(255,255,255,0.07)_75%,rgba(0,0,0,0)_100%)] hover:shadow-[0_0_26px_rgba(34,211,238,0.22)_inset,0_0_20px_rgba(34,211,238,0.28)]',
    secondary:
      'bg-phl-surface text-phl-text border border-phl-border hover:bg-phl-surface/80 dark:bg-ph-surface dark:text-ph-text dark:border-ph-border dark:hover:bg-ph-surface/80',
    ghost: 'text-phl-text hover:bg-phl-surface/70 dark:text-ph-text dark:hover:bg-ph-surface/70'
  }

  const sizes: Record<typeof size, string> = {
    default: 'px-4 py-2',
    icon: 'h-9 w-9 p-0'
  }

  return (
    <button
      className={`${base} ${variants[variant]} ${sizes[size]} ${className}`}
      {...props}
    />
  )
}
