import type { AnchorHTMLAttributes } from 'react'

export type NavigationItemPresentation = Pick<
  AnchorHTMLAttributes<HTMLAnchorElement>,
  'className' | 'title' | 'aria-label' | 'children'
>
