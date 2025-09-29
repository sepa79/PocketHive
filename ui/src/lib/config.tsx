import { ReactNode, createContext, useContext, useEffect, useState } from 'react'

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
  stompSubscription: '/exchange/ph.control/ev.#',
}

let configState: UIConfig = { ...defaultConfig }

type Listener = (cfg: UIConfig) => void
let listeners: Listener[] = []

const ConfigContext = createContext<UIConfig>(configState)

function notify() {
  listeners.forEach((listener) => listener(configState))
}

function hasConfigChanged(partial: Partial<UIConfig>) {
  return Object.entries(partial).some(([key, value]) => {
    const typedKey = key as keyof UIConfig
    return configState[typedKey] !== value
  })
}

export function getConfig(): UIConfig {
  return configState
}

export function setConfig(partial: Partial<UIConfig>) {
  if (!hasConfigChanged(partial)) {
    return
  }
  configState = { ...configState, ...partial }
  notify()
}

export function resetConfig() {
  configState = { ...defaultConfig }
  notify()
}

export function subscribeConfig(listener: Listener) {
  listeners.push(listener)
  listener(configState)
  return () => {
    listeners = listeners.filter((l) => l !== listener)
  }
}

export function ConfigProvider({ children }: { children: ReactNode }) {
  const [cfg, setCfg] = useState<UIConfig>(configState)

  useEffect(() => subscribeConfig(setCfg), [])

  return <ConfigContext.Provider value={cfg}>{children}</ConfigContext.Provider>
}

export function useConfig(): UIConfig {
  return useContext(ConfigContext)
}
