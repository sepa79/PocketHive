import type { FC } from 'react';
import { BeeColors, BeeSizes, type Status } from '../theme';
import { hexagon, shield, roundedRect, triangleRight, documentFolded, cylinder } from './paths';
import type { Role } from '../role';

export interface NodeVisualProps {
  role: Role;
  status: Status;
  title: string;
  name: string;
  w?: number;
  h?: number;
  selected?: boolean;
  progress?: number; // for Postprocessor
}

export const BeeNodeShape: FC<NodeVisualProps> = ({
  role,
  status,
  title,
  name,
  w = BeeSizes.w,
  h = BeeSizes.h,
  selected = false,
  progress = 0,
}) => {
  const stroke = BeeColors.nodeStroke;
  const fill = BeeColors.nodeFill;
  const halo = BeeColors.status[status];

  const shape = (() => {
    switch (role) {
      case 'Seeder':
        return <path d={hexagon(w, h)} fill={fill} stroke={stroke} />;
      case 'Gatekeeper':
        return <path d={shield(w, h)} fill={fill} stroke={stroke} />;
      case 'Worker':
        return (
          <>
            <path d={roundedRect(w, h, BeeSizes.radius)} fill={fill} stroke={stroke} />
            <g opacity="0.5">
              <polyline points="-20,-6 -8,0 -20,6" fill="none" stroke={stroke} />
              <polyline points="0,-6 12,0 0,6" fill="none" stroke={stroke} />
            </g>
          </>
        );
      case 'Postprocessor': {
        const { rx, ry, yTop, yBottom } = cylinder(w, h);
        const ringR = Math.max(w, h) / 2 + BeeSizes.halo;
        const sweep = Math.max(0.01, Math.min(1, progress)) * 2 * Math.PI;
        const x1 = ringR * Math.cos(-Math.PI / 2);
        const y1 = ringR * Math.sin(-Math.PI / 2);
        const x2 = ringR * Math.cos(-Math.PI / 2 + sweep);
        const y2 = ringR * Math.sin(-Math.PI / 2 + sweep);
        const large = sweep > Math.PI ? 1 : 0;

        return (
          <>
            <ellipse cx="0" cy={yTop} rx={rx} ry={ry} fill={fill} stroke={stroke} />
            <rect x={-rx} y={yTop} width={rx * 2} height={h} fill={fill} stroke={stroke} />
            <ellipse cx="0" cy={yBottom} rx={rx} ry={ry} fill={fill} stroke={stroke} />
            <path
              d={`M 0,0 m ${x1},${y1} A ${ringR},${ringR} 0 ${large} 1 ${x2},${y2}`}
              fill="none"
              stroke={halo}
              strokeWidth="3"
            />
          </>
        );
      }
      case 'Trigger':
        return (
          <>
            <path d={triangleRight(w, h)} fill={fill} stroke={stroke} />
            <polyline points="-6,-10 4,0 -6,10" fill="none" stroke={stroke} />
          </>
        );
      case 'LogAggregator': {
        const { body, fold } = documentFolded(w, h);
        return (
          <>
            <path d={body} fill={fill} stroke={stroke} />
            <path d={fold} fill="none" stroke={stroke} />
            <g opacity="0.5">
              <line x1="-40" y1="-8" x2="30" y2="-8" stroke={stroke} />
              <line x1="-40" y1="8" x2="18" y2="8" stroke={stroke} />
            </g>
          </>
        );
      }
    }
  })();

  return (
    <g className={`bee-node ${selected ? 'is-selected' : ''}`}>
      <circle
        cx="0"
        cy="0"
        r={Math.max(w, h) / 2 + BeeSizes.halo}
        fill="none"
        stroke={halo}
        strokeOpacity={status === 'ghost' ? 0.4 : 0.8}
        strokeDasharray={status === 'ghost' ? '4 4' : '0'}
      />
      {shape}
      <g textAnchor="middle" fill={BeeColors.text}>
        <text y={-8} fontSize="12" fontWeight="700">
          {title}
        </text>
        <text y={10} fontSize="11">
          {name}
        </text>
      </g>
    </g>
  );
};
