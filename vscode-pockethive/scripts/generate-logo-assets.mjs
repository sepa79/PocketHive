import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const project = resolve(import.meta.dirname, '..');
const sourcePath = resolve(project, '..', 'ui-v2', 'public', 'logo.svg');
const activityPath = resolve(project, 'resources', 'hive.svg');
const headerPath = resolve(project, 'resources', 'logo-mark.svg');
const source = await readFile(sourcePath, 'utf8');
const digest = createHash('sha256').update(source).digest('hex');
const mark = match(source, /<!-- === MARK === -->([\s\S]*?)<!-- === TEXT === -->/).trim();
const definitions = match(source, /<defs>([\s\S]*?)<\/defs>/).trim();
const sourceStyles = match(source, /<style>([\s\S]*?)<\/style>/).trim();

const activity = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 30 220 220" role="img" aria-label="PocketHive">
  <!-- Generated from ui-v2/public/logo.svg sha256:${digest}; do not edit. -->
  <style>
    .hex,.edge { fill:none; stroke:currentColor; }
    .hex { stroke-width:8; }
    .edge { stroke-width:2.5; }
    .node,.vent,.lensInner { fill:currentColor; stroke:currentColor; }
    .node { stroke-width:2; }
    .panel,.lensOuter { fill:none; stroke:currentColor; }
    .panel { stroke-width:2; }
    .lensOuter { stroke-width:3; }
  </style>
  ${mark}
</svg>
`;

const header = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 220 250" role="img" aria-label="PocketHive">
  <!-- Generated from ui-v2/public/logo.svg sha256:${digest}; do not edit. -->
  <style>${sourceStyles}</style>
  <defs>${definitions}</defs>
  ${mark}
</svg>
`;

if (process.argv.includes('--check')) {
  await check(activityPath, activity);
  await check(headerPath, header);
} else {
  await writeFile(activityPath, activity);
  await writeFile(headerPath, header);
}

function match(value, pattern) {
  const result = value.match(pattern);
  if (!result?.[1]) throw new Error(`PocketHive logo source contract changed: ${pattern}`);
  return result[1];
}

async function check(path, expected) {
  const actual = await readFile(path, 'utf8').catch(() => '');
  if (actual !== expected) throw new Error(`Stale generated logo asset: ${path}`);
}
