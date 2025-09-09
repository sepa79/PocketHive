export interface ControlEvent {
  event: 'status' | 'lifecycle' | 'metric' | 'alert' | 'link';
  kind: string;
  version: string;
  role: 'generator' | 'moderator' | 'processor' | 'postprocessor' | 'trigger' | 'swarm-controller';
  instance: string;
  location?: string;
  messageId: string;
  timestamp: string;
  enabled?: boolean;
  traffic?: string;
  inQueue?: {
    name: string;
    routingKeys?: string[];
  };
  publishes?: string[];
  queues?: {
    work?: {
      in?: string[];
      out?: string[];
      routes?: string[];
    };
    control?: {
      in?: string[];
      out?: string[];
      routes?: string[];
    };
  };
  data?: Record<string, unknown>;
}

export function isControlEvent(raw: unknown): raw is ControlEvent {
  if (!raw || typeof raw !== 'object') return false
  const evt = raw as ControlEvent
  return (
    typeof evt.event === 'string' &&
    typeof evt.kind === 'string' &&
    typeof evt.version === 'string' &&
    typeof evt.role === 'string' &&
    typeof evt.instance === 'string' &&
    typeof evt.messageId === 'string' &&
    typeof evt.timestamp === 'string'
  )
}
