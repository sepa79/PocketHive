import type { QueueInfo } from '../../types/hive'
import { queueHealth, colorForHealth } from '../../lib/health'

interface Props {
  queues: QueueInfo[]
}

export default function QueuesPanel({ queues }: Props) {
  return (
    <table className="w-full text-sm border-collapse">
      <thead>
        <tr className="text-left border-b border-white/10">
          <th className="py-2">Name</th>
          <th className="py-2">Role</th>
          <th className="py-2 text-center">Depth</th>
          <th className="py-2 text-center">Consumers</th>
          <th className="py-2 text-center">Health</th>
        </tr>
      </thead>
      <tbody>
        {queues.map((q) => (
          <tr key={q.name} className="border-b border-white/5">
            <td className="py-1">{q.name}</td>
            <td className="py-1">{q.role}</td>
            <td className="py-1 text-center">{q.depth ?? '—'}</td>
            <td className="py-1 text-center">{q.consumers ?? '—'}</td>
            <td className="py-1 text-center">
              <span
                className={`inline-block h-3 w-3 rounded-full ${colorForHealth(queueHealth(q))}`}
              />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

