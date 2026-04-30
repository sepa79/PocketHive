import { readStoredAccessToken } from '../auth'
import { loadControlPlaneSchema } from './schemaRegistry'

let started = false

export function bootstrapControlPlane() {
  if (started) {
    return
  }
  if (!readStoredAccessToken()) {
    return
  }
  started = true
  void loadControlPlaneSchema()
}

export function resetControlPlaneBootstrap() {
  started = false
}
