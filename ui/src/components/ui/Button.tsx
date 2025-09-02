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
    'inline-flex items-center justify-center rounded-xl text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-phl-accent2 dark:focus-visible:ring-ph-accent2 focus-visible:ring-offset-phl-bg dark:focus-visible:ring-offset-ph-bg disabled:opacity-50 disabled:pointer-events-none'

  const variants: Record<typeof variant, string> = {
    primary:
      'bg-phl-accent text-phl-bg hover:bg-phl-accent/90 dark:bg-ph-accent dark:text-ph-bg dark:hover:bg-ph-accent/90',
    secondary:
      'bg-phl-surface text-phl-text hover:bg-phl-surface/80 dark:bg-ph-surface dark:text-ph-text dark:hover:bg-ph-surface/80',
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
