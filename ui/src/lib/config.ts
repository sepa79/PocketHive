import { useEffect, useState } from 'react'

export type UIConfig = {
  rabbitmq: string
  prometheus: string
  grafana: string
  wiremock: string
  tcpMock: string
  redis: string
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

const config: UIConfig = {
  rabbitmq: `/rabbitmq`,
  prometheus: `/prometheus`,
  grafana: `/grafana`,
  redis: `/redis`,
  // grafana: `http://${host}:3333`,
  wiremock: `http://${host}:8080/__admin`,
  tcpMock: `http://${host}:8083`,
  stompUrl: `/ws`,
  stompUser: readOnlyUser,
  stompPasscode: readOnlyPasscode,
  stompSubscription: '/exchange/ph.control/#',
}

type Listener = (cfg: UIConfig) => void
let listeners: Listener[] = []

export function getConfig() {
  return config
}

export function setConfig(partial: Partial<UIConfig>) {
  Object.assign(config, partial)
  listeners.forEach((l) => l(config))
}

export function subscribeConfig(fn: Listener) {
  listeners.push(fn)
  fn(config)
  return () => {
    listeners = listeners.filter((l) => l !== fn)
  }
}

export function useConfig(): UIConfig {
  const [cfg, setCfg] = useState(config)
  useEffect(() => subscribeConfig(setCfg), [])
  return cfg
}
