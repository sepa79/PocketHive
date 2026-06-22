import type { WiremockRequestSummary } from '../../lib/wiremockClient'

interface Props {
  title: string
  requests: WiremockRequestSummary[]
  emptyMessage: string
  error?: boolean
  formatTimestamp?: (timestamp: number) => string
}

export default function WiremockRequestsTable({
  title,
  requests,
  emptyMessage,
  error,
  formatTimestamp,
}: Props) {
  return (
    <div className="overflow-hidden rounded border border-white/10">
      <div className="border-b border-white/10 bg-white/10 px-3 py-2 text-sm font-medium text-white/80">
        {title}
      </div>
      {error ? (
        <div className="px-3 py-4 text-xs text-red-400">Unable to load {title.toLowerCase()}.</div>
      ) : requests.length === 0 ? (
        <div className="px-3 py-4 text-xs text-white/60">{emptyMessage}</div>
      ) : (
        <table className="w-full border-collapse text-left text-xs">
          <thead className="bg-white/5 text-white/60">
            <tr>
              <th className="px-3 py-2 font-normal">Method</th>
              <th className="px-3 py-2 font-normal">URL</th>
              <th className="px-3 py-2 font-normal">Status</th>
              <th className="px-3 py-2 font-normal">Logged</th>
            </tr>
          </thead>
          <tbody>
            {requests.map((request) => {
              const key = request.id ?? `${request.method}-${request.url}-${request.loggedDate}`
              const logged =
                typeof request.loggedDate === 'number'
                  ? formatTimestamp?.(request.loggedDate) ?? new Date(request.loggedDate).toLocaleString()
                  : '—'
              return (
                <tr key={key} className="odd:bg-white/5">
                  <td className="px-3 py-2 font-mono text-[0.65rem] uppercase text-white/80">
                    {request.method ?? '—'}
                  </td>
                  <td className="max-w-[14rem] truncate px-3 py-2 text-white/80" title={request.url}>
                    {request.url ?? '—'}
                  </td>
                  <td className="px-3 py-2 text-white/80">{request.status ?? '—'}</td>
                  <td className="px-3 py-2 text-white/60">{logged}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}
