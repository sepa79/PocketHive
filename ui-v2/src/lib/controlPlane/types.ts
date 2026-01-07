export type ControlPlaneSource = 'stomp' | 'rest'

export type ControlPlaneScope = {
  swarmId: string
  role: string
  instance: string
}

export type ControlPlaneEnvelope = {
  timestamp: string
  version: string
  kind: string
  type: string
  origin: string
  scope: ControlPlaneScope
  correlationId: string | null
  idempotencyKey: string | null
  data: Record<string, unknown>
}

export type ControlPlaneDecoderErrorCode =
  | 'schema-missing'
  | 'schema-invalid'
  | 'decode-failed'
  | 'schema-violation'
  | 'routing-invalid'

export type ControlPlaneDecoderError = {
  receivedAt: string
  source: ControlPlaneSource
  routingKey?: string
  errorCode: ControlPlaneDecoderErrorCode
  message: string
  schemaPath?: string
  dataPath?: string
  payloadSnippet?: string
}
