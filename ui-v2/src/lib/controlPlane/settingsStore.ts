import {
  CONTROL_PLANE_STOMP_PASSCODE,
  CONTROL_PLANE_STOMP_URL,
  CONTROL_PLANE_STOMP_USER,
} from './config'

export type ControlPlaneSettings = {
  url: string
  user: string
  passcode: string
  enabled: boolean
}

type SettingsListener = (settings: ControlPlaneSettings) => void

let settings: ControlPlaneSettings = {
  url: CONTROL_PLANE_STOMP_URL,
  user: CONTROL_PLANE_STOMP_USER,
  passcode: CONTROL_PLANE_STOMP_PASSCODE,
  enabled: true,
}

const listeners = new Set<SettingsListener>()

export function getControlPlaneSettings() {
  return settings
}

export function subscribeControlPlaneSettings(listener: SettingsListener) {
  listeners.add(listener)
  listener(settings)
  return () => listeners.delete(listener)
}

export function updateControlPlaneSettings(partial: Partial<ControlPlaneSettings>) {
  settings = { ...settings, ...partial }
  listeners.forEach((listener) => listener(settings))
}
