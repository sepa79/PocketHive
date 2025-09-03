import { useEffect, useRef, useState } from 'react';
import type { StatusMessage, ControlCommand } from '../../types/hive';
import { createHiveClient } from '../../lib/stompClient';
import type { HiveClient } from '../../lib/stompClient';
import ComponentList from './ComponentList';
import ComponentDetail from './ComponentDetail';

export default function HivePage() {
  const [components, setComponents] = useState<StatusMessage[]>([]);
  const [selected, setSelected] = useState<StatusMessage | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const clientRef = useRef<HiveClient | null>(null);

  useEffect(() => {
    clientRef.current = createHiveClient((msg) => {
      if ('type' in msg && (msg.type === 'ack' || msg.type === 'nack')) {
        setToast(`${msg.type} for ${msg.componentId}`);
        setTimeout(() => setToast(null), 3000);
      } else {
        const status = msg as StatusMessage;
        setComponents((prev) => {
          const others = prev.filter((c) => c.componentId !== status.componentId);
          return [...others, status];
        });
      }
    });
    return () => clientRef.current?.disconnect();
  }, []);

  const handleSendConfig = (payload: unknown) => {
    if (!selected || !clientRef.current) return;
    const cmd: ControlCommand = {
      type: 'config.update',
      componentId: selected.componentId,
      payload,
      correlationId: `c-${crypto.randomUUID()}`,
      requestedBy: 'ui',
    };
    clientRef.current.publish(cmd);
  };

  return (
    <div className="flex h-full">
      <ComponentList components={components} onSelect={(c) => setSelected(c)} />
      {selected && (
        <ComponentDetail
          component={selected}
          onClose={() => setSelected(null)}
          onSendConfig={handleSendConfig}
        />
      )}
      {toast && (
        <div className="fixed top-4 right-4 bg-gray-800 text-white px-3 py-2 rounded shadow">
          {toast}
        </div>
      )}
    </div>
  );
}
