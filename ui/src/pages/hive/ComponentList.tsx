import { useState } from 'react';
import type { ComponentSummary } from '../../types/hive';
import { healthColor } from '../../lib/health';

interface Props {
  components: ComponentSummary[];
  onSelect: (c: ComponentSummary) => void;
}

export default function ComponentList({ components, onSelect }: Props) {
  const [query, setQuery] = useState('');
  const filtered = components.filter((c) => {
    const q = query.toLowerCase();
    return c.name.toLowerCase().includes(q) || c.componentId.toLowerCase().includes(q);
  });

  return (
    <div className="w-72 border-r h-full flex flex-col">
      <input
        className="m-2 p-1 border rounded"
        placeholder="Search..."
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <ul className="flex-1 overflow-y-auto">
        {filtered.map((c) => {
          const firstQ = c.queues[0];
          const summary = firstQ ? `${firstQ.name}${firstQ.depth !== undefined ? ` • depth:${firstQ.depth}` : ''}` : '';
          return (
            <li
              key={c.componentId}
              className="p-2 cursor-pointer hover:bg-gray-100 flex justify-between"
              onClick={() => onSelect(c)}
            >
              <div>
                <div className="font-medium">{c.name}</div>
                <div className="text-xs text-gray-500">{c.componentId}</div>
                {summary && <div className="text-xs text-gray-500">{summary}</div>}
              </div>
              <span className={`w-3 h-3 rounded-full self-center ${healthColor(c.health)}`} />
            </li>
          );
        })}
      </ul>
    </div>
  );
}
