const CONTROL_PLANE_REFRESH_URL = '/orchestrator/api/control-plane/refresh'

export async function requestControlPlaneRefresh() {
  try {
    const response = await fetch(CONTROL_PLANE_REFRESH_URL, { method: 'POST' })
    return response.ok
  } catch {
    return false
  }
}
