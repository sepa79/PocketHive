export interface DeviceAuthorization {
  deviceCode: string
  userCode: string
  verificationUrl: string
  expiresAt: number
  intervalMs: number
}
