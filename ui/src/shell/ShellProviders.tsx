import type { PropsWithChildren } from 'react'
import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { ConfigProvider } from '../lib/config'

function ThemeProvider({ children }: PropsWithChildren): JSX.Element {
  return <>{children}</>
}

export interface ShellProvidersProps extends PropsWithChildren {
  queryClient?: QueryClient
}

export function ShellProviders({ children, queryClient }: ShellProvidersProps): JSX.Element {
  const [client] = useState(() => queryClient ?? new QueryClient())

  return (
    <ThemeProvider>
      <ConfigProvider>
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </ConfigProvider>
    </ThemeProvider>
  )
}
