export type HealthLevel = 'OK' | 'WARN' | 'ALERT';

export interface QueueInfo {
  name: string;
  role: 'producer' | 'consumer';
  depth?: number;
  consumers?: number;
  oldestAgeSec?: number;
}

export interface ComponentSummary {
  componentId: string;
  name: string;
  componentType: string;
  version?: string;
  env?: string;
  uptimeMs?: number;
  lastHeartbeatTs?: number;
  health: HealthLevel;
  queues: QueueInfo[];
}

export interface StatusMessage extends ComponentSummary {
  type: 'status';
}

export interface ControlCommand {
  type: 'config.update';
  componentId: string;
  payload: unknown;
  correlationId: string;
  requestedBy: 'ui';
}

export interface ControlAck {
  type: 'ack' | 'nack';
  componentId: string;
  correlationId: string;
  message?: string;
}
