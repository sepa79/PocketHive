import { Client, IMessage } from '@stomp/stompjs';
import type { StatusMessage, ControlAck, ControlCommand } from '../types/hive';

type MessageHandler = (msg: StatusMessage | ControlAck) => void;

export interface HiveClient {
  publish: (cmd: ControlCommand) => void;
  disconnect: () => void;
}

export function createHiveClient(handler: MessageHandler): HiveClient {
  const mock = import.meta.env.VITE_MOCK === 'true';
  return mock ? createMockClient(handler) : createStompClient(handler);
}

function createStompClient(handler: MessageHandler): HiveClient {
  const url = import.meta.env.VITE_STOMP_URL as string;
  const sub = import.meta.env.VITE_CONTROL_SUB as string;
  const pub = import.meta.env.VITE_CONTROL_PUB as string;

  const client = new Client({ brokerURL: url, reconnectDelay: 5000 });
  client.onConnect = () => {
    client.subscribe(sub, (message: IMessage) => {
      try {
        const data = JSON.parse(message.body);
        handler(data as StatusMessage | ControlAck);
      } catch (err) {
        console.error('Failed to parse message', err);
      }
    });
  };
  client.activate();

  return {
    publish: (cmd: ControlCommand) => {
      client.publish({ destination: pub, body: JSON.stringify(cmd) });
    },
    disconnect: () => client.deactivate(),
  };
}

function createMockClient(handler: MessageHandler): HiveClient {
  const components: StatusMessage[] = [
    {
      componentId: 'log-aggregator-1',
      name: 'LogAggregator',
      componentType: 'LogAggregator',
      type: 'status',
      version: '1.2.3',
      env: 'dev',
      uptimeMs: 0,
      lastHeartbeatTs: Date.now(),
      health: 'OK',
      queues: [
        { name: 'logs.q', role: 'consumer', depth: 0, consumers: 2, oldestAgeSec: 0 },
        { name: 'loki.out', role: 'producer' },
      ],
    },
    {
      componentId: 'worker-1',
      name: 'Worker 1',
      componentType: 'Worker',
      type: 'status',
      version: '0.9.0',
      env: 'dev',
      uptimeMs: 0,
      lastHeartbeatTs: Date.now(),
      health: 'OK',
      queues: [
        { name: 'jobs.q', role: 'consumer', depth: 0, consumers: 1, oldestAgeSec: 0 },
        { name: 'results.q', role: 'producer' },
      ],
    },
  ];

  const interval = setInterval(() => {
    components.forEach((c) => {
      c.uptimeMs = (c.uptimeMs ?? 0) + 3000;
      c.lastHeartbeatTs = Date.now();
      c.queues.forEach((q) => {
        if (q.depth !== undefined) {
          q.depth = Math.max(0, (q.depth ?? 0) + Math.floor(Math.random() * 50 - 20));
        }
        if (q.oldestAgeSec !== undefined) {
          q.oldestAgeSec = Math.max(0, (q.oldestAgeSec ?? 0) + Math.floor(Math.random() * 10 - 5));
        }
      });
      handler({ ...c });
    });
  }, 3000);

  return {
    publish: (cmd: ControlCommand) => {
      setTimeout(() => {
        const ack: ControlAck = { type: 'ack', componentId: cmd.componentId, correlationId: cmd.correlationId };
        handler(ack);
      }, 500);
    },
    disconnect: () => clearInterval(interval),
  };
}
