export const CONTROL_PLANE_SCHEMA_URL =
  '/orchestrator/api/control-plane/schema/control-events'

const base =
  typeof window !== 'undefined'
    ? window.location
    : { protocol: 'http:', host: 'localhost' }
const protocol = base.protocol === 'https:' ? 'wss' : 'ws'

export const CONTROL_PLANE_STOMP_URL = `${protocol}://${base.host}/ws`

export const CONTROL_PLANE_STOMP_USER =
  import.meta.env.VITE_CONTROL_PLANE_STOMP_USER ?? 'ph-observer'
export const CONTROL_PLANE_STOMP_PASSCODE =
  import.meta.env.VITE_CONTROL_PLANE_STOMP_PASSCODE ?? 'ph-observer'
