import { loadControlPlaneSchema } from './schemaRegistry'

let started = false

export function bootstrapControlPlane() {
  if (started) {
    return
  }
  started = true
  void loadControlPlaneSchema()
}
