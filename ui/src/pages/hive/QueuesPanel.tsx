import type { QueueInfo } from '../../types/hive';
import { queueHealth, healthColor } from '../../lib/health';

interface Props {
  queues: QueueInfo[];
}

export default function QueuesPanel({ queues }: Props) {
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="text-left">
          <th className="p-2">Queue</th>
          <th className="p-2">Role</th>
          <th className="p-2 text-center">Depth</th>
          <th className="p-2 text-center">Consumers</th>
          <th className="p-2 text-center">Health</th>
        </tr>
      </thead>
      <tbody>
        {queues.map((q) => (
          <tr key={q.name} className="border-t">
            <td className="p-2">{q.name}</td>
            <td className="p-2">{q.role}</td>
            <td className="p-2 text-center">{q.depth ?? '—'}</td>
            <td className="p-2 text-center">{q.consumers ?? '—'}</td>
            <td className="p-2 text-center">
              <span className={`w-3 h-3 rounded-full inline-block ${healthColor(queueHealth(q))}`} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
