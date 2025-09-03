export function hexagon(w: number, h: number): string {
  const a = w / 2,
    b = h / 2,
    x = a * 0.866; // cos(30°)
  return `M ${-x},${-b} L ${x},${-b} L ${a},0 L ${x},${b} L ${-x},${b} L ${-a},0 Z`;
}

export function shield(w: number, h: number): string {
  const r = w / 2;
  return `
    M ${-r},${-h / 3}
    H ${r}
    Q ${r},${0} 0,${h / 2}
    Q ${-r},${0} ${-r},${-h / 3}
    Z
  `;
}

export function roundedRect(w: number, h: number, r: number): string {
  const x = -w / 2,
    y = -h / 2;
  return `
    M ${x + r},${y}
    H ${x + w - r} A ${r},${r} 0 0 1 ${x + w},${y + r}
    V ${y + h - r} A ${r},${r} 0 0 1 ${x + w - r},${y + h}
    H ${x + r} A ${r},${r} 0 0 1 ${x},${y + h - r}
    V ${y + r} A ${r},${r} 0 0 1 ${x + r},${y}
    Z
  `;
}

export function triangleRight(w: number, h: number): string {
  return `M ${-w / 2},${-h / 2} L ${w / 2},0 L ${-w / 2},${h / 2} Z`;
}

export function documentFolded(w: number, h: number): {
  body: string;
  fold: string;
} {
  const x = -w / 2,
    y = -h / 2,
    f = Math.min(16, w * 0.18);
  const body = `
    M ${x},${y} H ${x + w - f} L ${x + w},${y + f} V ${y + h} H ${x} Z
  `;
  const fold = `
    M ${x + w - f},${y} L ${x + w - f},${y + f} L ${x + w},${y + f}
  `;
  return { body, fold };
}

export function cylinder(w: number, h: number) {
  const rx = w / 2,
    ry = Math.max(6, h * 0.12);
  const yTop = -h / 2,
    yBottom = h / 2;
  return { rx, ry, yTop, yBottom };
}
