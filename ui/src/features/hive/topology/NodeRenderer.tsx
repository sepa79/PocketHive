import type { FC } from 'react';
import { BeeNodeShape } from './shapes/BeeNodeShape';
import { BeeTitle, type Role } from './role';
import type { Status } from './theme';

export interface NodeData {
  id: string;
  role: Role;
  name: string;
  status: Status;
  x: number;
  y: number;
  progress?: number;
}

interface NodeRendererProps {
  node: NodeData;
  selected?: boolean;
}

export const NodeRenderer: FC<NodeRendererProps> = ({ node, selected }) => {
  const { role, status, name, progress, x, y } = node;
  const title = BeeTitle[role];
  return (
    <g transform={`translate(${x},${y})`}>
      <BeeNodeShape
        role={role}
        status={status}
        name={name}
        title={title}
        selected={selected}
        progress={progress}
      />
    </g>
  );
};
