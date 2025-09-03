import { useState } from 'react';
import type { StatusMessage } from '../../types/hive';
import { healthColor } from '../../lib/health';
import QueuesPanel from './QueuesPanel';

interface Props {
  component: StatusMessage;
  onClose: () => void;
  onSendConfig: (payload: unknown) => void;
}

export default function ComponentDetail({ component, onClose, onSendConfig }: Props) {
  const [payload, setPayload] = useState('{}');

  const handleSend = () => {
    try {
      const p = JSON.parse(payload);
      onSendConfig(p);
    } catch {
      onSendConfig({});
    }
  };

  return (
    <div className="fixed top-0 right-0 w-96 h-full bg-white shadow-xl overflow-y-auto z-10">
      <button className="m-2 text-sm" onClick={onClose}>
        Close
      </button>
      <div className="p-4 border-b flex items-center justify-between">
        <div>
          <h2 className="font-bold">{component.name}</h2>
          <div className="text-xs text-gray-500">{component.componentId}</div>
        </div>
        <span className={`w-3 h-3 rounded-full ${healthColor(component.health)}`} />
      </div>
      <div className="p-4 space-y-1 text-sm">
        <div>Version: {component.version ?? '—'}</div>
        <div>Env: {component.env ?? '—'}</div>
        <div>Uptime: {formatMs(component.uptimeMs)}</div>
        <div>Last heartbeat: {component.lastHeartbeatTs ? formatRelative(component.lastHeartbeatTs) : '—'}</div>
      </div>
      <div className="p-4 border-t space-y-2">
        <h3 className="font-semibold">Component</h3>
        <div className="p-2 border rounded text-sm text-gray-500">component-specific controls go here</div>
      </div>
      <div className="p-4 border-t space-y-2">
        <h3 className="font-semibold">Queues</h3>
        <QueuesPanel queues={component.queues} />
      </div>
      <div className="p-4 border-t space-y-2">
        <h3 className="font-semibold">Actions</h3>
        <textarea
          value={payload}
          onChange={(e) => setPayload(e.target.value)}
          className="w-full border p-1 text-xs font-mono"
          rows={3}
        />
        <button className="px-2 py-1 bg-blue-500 text-white rounded" onClick={handleSend}>
          Send config.update
        </button>
      </div>
    </div>
  );
}

function formatMs(ms?: number) {
  if (!ms) return '—';
  const sec = Math.floor(ms / 1000);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}m ${s}s`;
}

function formatRelative(ts: number) {
  const diff = Date.now() - ts;
  const sec = Math.floor(diff / 1000);
  return `${sec}s ago`;
}
