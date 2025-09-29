import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'

export type UIConfig = {
  rabbitmq: string
  prometheus: string
  grafana: string
  wiremock: string
  stompUrl: string
  stompUser: string
  stompPasscode: string
  stompSubscription: string
}

const host = typeof window !== 'undefined' ? window.location.hostname : 'localhost'

const readOnlyUser =
  import.meta.env.VITE_STOMP_READONLY_USER || import.meta.env.VITE_STOMP_USER || 'ph-observer'
const readOnlyPasscode =
  import.meta.env.VITE_STOMP_READONLY_PASSCODE || import.meta.env.VITE_STOMP_PASSCODE || 'ph-observer'

const defaultConfig: UIConfig = {
  rabbitmq: `http://${host}:15672/`,
  prometheus: `http://${host}:9090/`,
  grafana: `http://${host}:3000/`,
  wiremock: `http://${host}:8080/__admin/`,
  stompUrl: `/ws`,
  stompUser: readOnlyUser,
  stompPasscode: readOnlyPasscode,
  stompSubscription: '/exchange/ph.control/ev.#'
}

type Listener = (cfg: UIConfig) => void

const globalObject = globalThis as typeof globalThis & {
  __POCKETHIVE_UI_CONFIG__?: UIConfig
  __POCKETHIVE_UI_CONFIG_LISTENERS__?: Set<Listener>
}

function ensureConfig(): UIConfig {
  if (!globalObject.__POCKETHIVE_UI_CONFIG__) {
    globalObject.__POCKETHIVE_UI_CONFIG__ = defaultConfig
  }

  return globalObject.__POCKETHIVE_UI_CONFIG__
}

function ensureListeners(): Set<Listener> {
  if (!globalObject.__POCKETHIVE_UI_CONFIG_LISTENERS__) {
    globalObject.__POCKETHIVE_UI_CONFIG_LISTENERS__ = new Set()
  }

  return globalObject.__POCKETHIVE_UI_CONFIG_LISTENERS__
}

export function getConfig(): UIConfig {
  return ensureConfig()
}

export function setConfig(partial: Partial<UIConfig>) {
  const next = { ...ensureConfig(), ...partial }
  globalObject.__POCKETHIVE_UI_CONFIG__ = next

  ensureListeners().forEach((listener) => listener(next))
}

export function subscribeConfig(fn: Listener) {
  const listeners = ensureListeners()
  listeners.add(fn)
  fn(getConfig())
  return () => {
    listeners.delete(fn)
  }
}

const ConfigContext = createContext<UIConfig>(getConfig())

export function ConfigProvider({ children }: { children: ReactNode }): JSX.Element {
  const [cfg, setCfg] = useState(getConfig())

  useEffect(() => subscribeConfig(setCfg), [])

  return <ConfigContext.Provider value={cfg}>{children}</ConfigContext.Provider>
}

export function useConfig(): UIConfig {
  return useContext(ConfigContext)
}
