import type { SVGProps } from 'react'

export default function MonolithIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 32 32" width="16" height="16" aria-hidden="true" {...props}>
      <polygon points="10,8 18,8 18,26 10,26" fill="#111" />
      <polygon points="18,8 20,6 20,24 18,26" fill="#1a1a1a" />
      <polygon points="10,8 18,8 20,6 12,6" fill="#222" />
    </svg>
  )
}
