import { ReactNode, StrictMode } from 'react'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider } from './lib/config'
import { Theme, ThemeProvider } from './lib/theme'

const queryClient = new QueryClient()

export type ShellProvidersProps = {
  children: ReactNode
  withRouter?: boolean
  theme?: Theme
}

export function ShellProviders({ children, withRouter = true, theme = 'dark' }: ShellProvidersProps) {
  const content = (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <ThemeProvider initialTheme={theme}>{children}</ThemeProvider>
      </ConfigProvider>
    </QueryClientProvider>
  )

  const routedContent = withRouter ? <BrowserRouter>{content}</BrowserRouter> : content

  return <StrictMode>{routedContent}</StrictMode>
}

export default ShellProviders
