import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const project = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourcePath = resolve(project, '..', 'ui-v2', 'public', 'logo.svg');
const activityPath = resolve(project, 'resources', 'activity-mark.svg');
const brandTokensPath = resolve(project, 'resources', 'brand-tokens.css');
const headerPath = resolve(project, 'resources', 'logo-mark.svg');
const callbackLogoPath = resolve(project, 'src', 'generated', 'callbackLogo.ts');
const source = await readFile(sourcePath, 'utf8');
const digest = createHash('sha256').update(source).digest('hex');
const mark = match(source, /<!-- === MARK === -->([\s\S]*?)<!-- === TEXT === -->/).trim();
const definitions = match(source, /<defs>([\s\S]*?)<\/defs>/).trim();
const sourceStyles = match(source, /<style>([\s\S]*?)<\/style>/).trim();
const brandHiveColour = match(sourceStyles, /\.brandHive\s*\{[^}]*\bfill\s*:\s*(#[0-9a-f]{6})\s*;/i);
const hexagon = match(mark, /(<polygon class="hex"[^>]*\/>)/);
const edges = elements(mark, /<line class="edge"[^>]*\/>/g, 6, 'connectors');
const panel = match(mark, /(<rect class="panel"[^>]*\/>)/);
const lensOuter = match(mark, /(<circle class="lensOuter"[^>]*\/>)/);
const lensInner = match(mark, /(<circle class="lensInner"[^>]*\/>)/).replace('r="14"', 'r="8"');
const nodes = elements(mark, /<circle class="node"[^>]*\/>/g, 6, 'nodes');

const activity = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="20 40 180 200"
  role="img" aria-label="PocketHive" data-pocket-hive-activity-icon="24px-silhouette">
  <!-- Generated from ui-v2/public/logo.svg sha256:${digest}; do not edit. -->
  <style>
    .hex,.edge,.panel,.lensOuter,.node {
      stroke:currentColor;
      stroke-linecap:round;
      stroke-linejoin:round;
      vector-effect:non-scaling-stroke;
    }
    .hex { fill:none; stroke-width:1.25; }
    .edge { fill:none; stroke-width:0.9; }
    .panel { fill:none; stroke-width:1.1; }
    .lensOuter { fill:none; stroke-width:1; }
    .lensInner,.node { fill:currentColor; }
    .lensInner { stroke:none; }
    .node { stroke-width:0.5; }
  </style>
  <defs>
    <mask id="connector-cutout" maskUnits="userSpaceOnUse" x="0" y="0" width="220" height="220">
      <rect width="220" height="220" fill="white"/>
      <rect x="84" y="68" width="52" height="84" rx="8" fill="black"/>
    </mask>
  </defs>
  <g transform="translate(0,30)">
    ${hexagon}
    <g mask="url(#connector-cutout)">
      ${edges.join('\n      ')}
    </g>
    ${panel}
    ${lensOuter}
    ${lensInner}
    ${nodes.join('\n    ')}
  </g>
</svg>
`;

const header = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 220 250" role="img" aria-label="PocketHive">
  <!-- Generated from ui-v2/public/logo.svg sha256:${digest}; do not edit. -->
  <style>${sourceStyles}</style>
  <defs>${definitions}</defs>
  ${mark}
</svg>
`;

const brandTokens = `/* Generated from ui-v2/public/logo.svg sha256:${digest}; do not edit. */
:root {
  --ph-brand-hive: ${brandHiveColour};
}
`;

const callbackLogo = `// Generated from ui-v2/public/logo.svg sha256:${digest}; do not edit.
export const CALLBACK_LOGO_DATA_URI =
  'data:image/svg+xml;base64,${Buffer.from(source, 'utf8').toString('base64')}';
`;

if (process.argv.includes('--check')) {
  await check(activityPath, activity);
  await check(brandTokensPath, brandTokens);
  await check(headerPath, header);
  await check(callbackLogoPath, callbackLogo);
} else {
  await mkdir(resolve(project, 'src', 'generated'), { recursive: true });
  await writeFile(activityPath, activity);
  await writeFile(brandTokensPath, brandTokens);
  await writeFile(headerPath, header);
  await writeFile(callbackLogoPath, callbackLogo);
}

function match(value, pattern) {
  const result = value.match(pattern);
  if (!result?.[1]) throw new Error(`PocketHive logo source contract changed: ${pattern}`);
  return result[1];
}

function elements(value, pattern, expectedCount, label) {
  const matches = [...value.matchAll(pattern)].map(result => result[0]);
  if (matches.length !== expectedCount) {
    throw new Error(`PocketHive logo source contract changed: expected ${expectedCount} ${label}`);
  }
  return matches;
}

async function check(path, expected) {
  const actual = await readFile(path, 'utf8').catch(() => '');
  if (actual !== expected) throw new Error(`Stale generated logo asset: ${path}`);
}
