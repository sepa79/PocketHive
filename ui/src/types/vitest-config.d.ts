import 'vite'

declare module 'vite' {
  interface UserConfig {
    test?: {
      environment?: string
    }
  }
}
