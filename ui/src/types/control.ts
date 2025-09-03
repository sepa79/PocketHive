import Ajv from 'ajv';
import schema from '../../../spec/control-events.schema.json' assert { type: 'json' };

export interface ControlEvent {
  event: 'status' | 'lifecycle' | 'metric' | 'alert' | 'link';
  kind: string;
  version: string;
  role: 'generator' | 'moderator' | 'processor';
  instance: string;
  location?: string;
  messageId: string;
  timestamp: string;
  traffic?: string;
  queues?: {
    in?: string[];
    out?: string[];
  };
  data?: Record<string, unknown>;
}

const ajv = new Ajv();
export const validateControlEvent = ajv.compile(schema);
