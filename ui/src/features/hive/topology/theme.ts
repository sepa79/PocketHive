export const BeeColors = {
  nodeFill: 'var(--ph-node-fill)',
  nodeStroke: 'var(--ph-node-stroke)',
  text: 'var(--ph-text)',
  status: {
    ok: 'var(--ph-ok)',
    warn: 'var(--ph-warn)',
    err: 'var(--ph-err)',
    ghost: 'var(--ph-ghost)',
  },
} as const;

export type Status = keyof typeof BeeColors.status;

export const BeeSizes = {
  w: 140,
  h: 72,
  radius: 10,
  halo: 8,
} as const;

export function statusToColor(status: Status): string {
  return BeeColors.status[status];
}
