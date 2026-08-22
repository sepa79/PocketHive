import { constants } from "node:fs";
import {
  lstat,
  mkdir,
  open,
  realpath,
} from "node:fs/promises";
import {
  dirname,
  parse,
  resolve,
  sep,
} from "node:path";

const DIRECT_PATH_KIND = Object.freeze({
  DIRECTORY: "directory",
  FILE: "file",
});

export const HARD_LINK_POLICY = Object.freeze({
  ALLOW_STABLE_IDENTITY: "ALLOW_STABLE_IDENTITY",
  REJECT: "REJECT",
});

function pathIdentity(metadata) {
  return Object.freeze({
    dev: metadata.dev.toString(),
    ino: metadata.ino.toString(),
    mode: metadata.mode.toString(),
    birthtimeNs: metadata.birthtimeNs.toString(),
  });
}

function stableFileState(metadata) {
  return Object.freeze({
    ...pathIdentity(metadata),
    ctimeNs: metadata.ctimeNs.toString(),
    mtimeNs: metadata.mtimeNs.toString(),
    size: metadata.size.toString(),
  });
}

function sameRecord(left, right) {
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  return leftKeys.length === rightKeys.length
    && leftKeys.every((key) => left[key] === right[key]);
}

function assertMetadataKind(metadata, kind, label) {
  if (metadata.isSymbolicLink()) {
    throw new Error(`${label} must not traverse a symbolic link or junction-like entry`);
  }
  if (kind === DIRECT_PATH_KIND.DIRECTORY && !metadata.isDirectory()) {
    throw new Error(`${label} is not a direct directory`);
  }
  if (kind === DIRECT_PATH_KIND.FILE && !metadata.isFile()) {
    throw new Error(`${label} is not a direct regular file`);
  }
}

function componentsBetween(parent, child, label) {
  const resolvedParent = assertSupportedFilesystemPath(parent, `${label} parent`);
  const resolvedChild = assertSupportedFilesystemPath(child, `${label} child`);
  if (resolvedParent === resolvedChild) return [];
  if (!isPathInside(resolvedParent, resolvedChild)) {
    throw new Error(`${label} escapes its declared filesystem boundary`);
  }
  const prefix = resolvedParent.endsWith(sep) ? resolvedParent : `${resolvedParent}${sep}`;
  return resolvedChild.slice(prefix.length).split(sep);
}

export function assertSupportedFilesystemPath(value, label = "Filesystem path") {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${label} must be an explicit non-empty path`);
  }
  const resolvedPath = resolve(value);
  if (process.platform === "win32") {
    const volumeRoot = parse(resolvedPath).root;
    if (resolvedPath.slice(volumeRoot.length).includes(":")) {
      throw new Error(`${label} must not use a Windows alternate data stream`);
    }
  }
  return resolvedPath;
}

export function sameFilesystemPath(left, right) {
  return assertSupportedFilesystemPath(left) === assertSupportedFilesystemPath(right);
}

export function isPathInside(parent, child) {
  const resolvedParent = assertSupportedFilesystemPath(parent);
  const resolvedChild = assertSupportedFilesystemPath(child);
  if (resolvedParent === resolvedChild) return false;
  const prefix = resolvedParent.endsWith(sep) ? resolvedParent : `${resolvedParent}${sep}`;
  return resolvedChild.startsWith(prefix);
}

export function filesystemRoot(path) {
  return parse(resolve(path)).root;
}

async function inspectDirectPath({ anchorPath, targetPath, targetKind, label }) {
  if (![DIRECT_PATH_KIND.DIRECTORY, DIRECT_PATH_KIND.FILE].includes(targetKind)) {
    throw new Error(`${label} has an unsupported direct-path target kind`);
  }
  const anchor = resolve(anchorPath ?? filesystemRoot(targetPath));
  const target = resolve(targetPath);
  if (!sameFilesystemPath(anchor, target) && !isPathInside(anchor, target)) {
    throw new Error(`${label} escapes its declared filesystem boundary`);
  }
  const volumeRoot = filesystemRoot(target);
  const canonicalVolumeRoot = await realpath(volumeRoot);
  if (!sameFilesystemPath(volumeRoot, canonicalVolumeRoot)) {
    throw new Error(`${label} filesystem root is an alias`);
  }
  const anchorComponents = componentsBetween(canonicalVolumeRoot, anchor, label);
  const targetComponents = componentsBetween(canonicalVolumeRoot, target, label);
  if (anchorComponents.length > targetComponents.length
    || anchorComponents.some((segment, index) => (
      !sameFilesystemPath(
        resolve(canonicalVolumeRoot, ...anchorComponents.slice(0, index + 1)),
        resolve(canonicalVolumeRoot, ...targetComponents.slice(0, index + 1)),
      )
    ))) {
    throw new Error(`${label} escapes its declared filesystem boundary`);
  }

  const entries = [];
  let current = canonicalVolumeRoot;
  const rootMetadata = await lstat(current, { bigint: true });
  assertMetadataKind(rootMetadata, DIRECT_PATH_KIND.DIRECTORY, `${label} filesystem root`);
  entries.push({
    path: current,
    kind: DIRECT_PATH_KIND.DIRECTORY,
    identity: pathIdentity(rootMetadata),
  });
  for (const [index, segment] of targetComponents.entries()) {
    current = resolve(current, segment);
    const kind = index === targetComponents.length - 1
      ? targetKind
      : DIRECT_PATH_KIND.DIRECTORY;
    const metadata = await lstat(current, { bigint: true });
    assertMetadataKind(metadata, kind, label);
    const canonicalCurrent = await realpath(current);
    if (!sameFilesystemPath(current, canonicalCurrent)) {
      throw new Error(`${label} must not traverse a filesystem alias`);
    }
    entries.push({ path: current, kind, identity: pathIdentity(metadata) });
  }
  const targetEntry = entries.at(-1);
  if (!sameFilesystemPath(targetEntry.path, target)) {
    throw new Error(`${label} did not resolve to the requested target`);
  }
  return Object.freeze({
    anchor,
    target,
    targetKind,
    label,
    entries: Object.freeze(entries.map((entry) => Object.freeze(entry))),
    targetIdentity: targetEntry.identity,
  });
}

export async function captureDirectDirectorySnapshot({ anchorPath, path, label }) {
  return inspectDirectPath({
    anchorPath,
    targetPath: path,
    targetKind: DIRECT_PATH_KIND.DIRECTORY,
    label,
  });
}

export async function captureDirectFileSnapshot({ anchorPath, path, label }) {
  return inspectDirectPath({
    anchorPath,
    targetPath: path,
    targetKind: DIRECT_PATH_KIND.FILE,
    label,
  });
}

export async function assertDirectPathSnapshot(snapshot) {
  const current = await inspectDirectPath({
    anchorPath: snapshot.anchor,
    targetPath: snapshot.target,
    targetKind: snapshot.targetKind,
    label: snapshot.label,
  });
  if (current.entries.length !== snapshot.entries.length
    || current.entries.some((entry, index) => (
      !sameFilesystemPath(entry.path, snapshot.entries[index].path)
      || entry.kind !== snapshot.entries[index].kind
      || !sameRecord(entry.identity, snapshot.entries[index].identity)
    ))) {
    throw new Error(`${snapshot.label} path identity changed after validation`);
  }
  return current;
}

export async function resolveDirectRegularFilePath({ anchorPath, path, label }) {
  return (await captureDirectFileSnapshot({ anchorPath, path, label })).target;
}

export async function resolveDirectDirectoryPath({ anchorPath, path, label }) {
  return (await captureDirectDirectorySnapshot({ anchorPath, path, label })).target;
}

async function readExact(handle, size, label) {
  const bytes = Buffer.alloc(size);
  let offset = 0;
  while (offset < size) {
    const result = await handle.read(bytes, offset, size - offset, offset);
    if (result.bytesRead === 0) {
      throw new Error(`${label} changed length while it was read`);
    }
    offset += result.bytesRead;
  }
  const trailing = Buffer.alloc(1);
  if ((await handle.read(trailing, 0, 1, size)).bytesRead !== 0) {
    throw new Error(`${label} changed length while it was read`);
  }
  return bytes;
}

export async function captureStableRegularFile({
  anchorPath,
  hardLinkPolicy,
  path,
  label,
  maxBytes,
}) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 0) {
    throw new Error(`${label} requires an explicit non-negative safe-integer byte limit`);
  }
  if (!Object.values(HARD_LINK_POLICY).includes(hardLinkPolicy)) {
    throw new Error(`${label} requires an explicit supported hard-link policy`);
  }
  const snapshot = await captureDirectFileSnapshot({ anchorPath, path, label });
  const openFlags = constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0);
  const handle = await open(snapshot.target, openFlags);
  try {
    const before = await handle.stat({ bigint: true });
    assertMetadataKind(before, DIRECT_PATH_KIND.FILE, label);
    if (!sameRecord(pathIdentity(before), snapshot.targetIdentity)) {
      throw new Error(`${label} opened handle does not match the validated direct file`);
    }
    if (before.nlink < 1n
      || (hardLinkPolicy === HARD_LINK_POLICY.REJECT && before.nlink !== 1n)) {
      throw new Error(`${label} must not be a hard-linked file`);
    }
    if (before.size > BigInt(maxBytes)) {
      throw new Error(`${label} exceeds the ${maxBytes}-byte limit`);
    }
    await assertDirectPathSnapshot(snapshot);
    const firstBytes = await readExact(handle, Number(before.size), label);
    const secondBytes = await readExact(handle, Number(before.size), label);
    const after = await handle.stat({ bigint: true });
    if (!sameRecord(stableFileState(before), stableFileState(after))
      || !firstBytes.equals(secondBytes)) {
      throw new Error(`${label} changed while it was captured`);
    }
    const current = await assertDirectPathSnapshot(snapshot);
    const currentMetadata = await lstat(current.target, { bigint: true });
    if (!sameRecord(stableFileState(after), stableFileState(currentMetadata))) {
      throw new Error(`${label} path no longer names the captured file state`);
    }
    return firstBytes;
  } finally {
    await handle.close();
  }
}

export async function assertPathAbsent(path, label) {
  assertSupportedFilesystemPath(path, label);
  try {
    await lstat(path);
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }
  throw new Error(`${label} already exists`);
}

export async function createDirectoryUnderSnapshot({ rootSnapshot, path, label }) {
  if (!isPathInside(rootSnapshot.target, path)) {
    throw new Error(`${label} escapes its owned output directory`);
  }
  const parentPath = dirname(path);
  const parentSnapshot = await captureDirectDirectorySnapshot({
    anchorPath: rootSnapshot.target,
    path: parentPath,
    label: `${label} parent`,
  });
  await assertDirectPathSnapshot(rootSnapshot);
  await assertPathAbsent(path, label);
  await mkdir(path);
  const created = await captureDirectDirectorySnapshot({
    anchorPath: rootSnapshot.target,
    path,
    label,
  });
  await assertDirectPathSnapshot(rootSnapshot);
  await assertDirectPathSnapshot(parentSnapshot);
  return created;
}

async function writeAll(handle, bytes, label) {
  let offset = 0;
  while (offset < bytes.byteLength) {
    const result = await handle.write(bytes, offset, bytes.byteLength - offset, offset);
    if (result.bytesWritten === 0) throw new Error(`${label} could not be written completely`);
    offset += result.bytesWritten;
  }
}

export async function writeNewFileUnderSnapshot({ rootSnapshot, path, bytes, label }) {
  if (!Buffer.isBuffer(bytes)) throw new Error(`${label} bytes must be an explicit Buffer`);
  if (!isPathInside(rootSnapshot.target, path)) {
    throw new Error(`${label} escapes its owned output directory`);
  }
  const parentSnapshot = await captureDirectDirectorySnapshot({
    anchorPath: rootSnapshot.target,
    path: dirname(path),
    label: `${label} parent`,
  });
  await assertDirectPathSnapshot(rootSnapshot);
  await assertPathAbsent(path, label);
  const openFlags = constants.O_CREAT
    | constants.O_EXCL
    | constants.O_WRONLY
    | (constants.O_NOFOLLOW ?? 0);
  const handle = await open(path, openFlags, 0o600);
  try {
    const opened = await handle.stat({ bigint: true });
    assertMetadataKind(opened, DIRECT_PATH_KIND.FILE, label);
    const created = await captureDirectFileSnapshot({
      anchorPath: rootSnapshot.target,
      path,
      label,
    });
    if (!sameRecord(pathIdentity(opened), created.targetIdentity)) {
      throw new Error(`${label} opened handle does not match the newly created direct file`);
    }
    if (opened.nlink !== 1n || opened.size !== 0n) {
      throw new Error(`${label} newly created file has an unsafe link count or initial size`);
    }
    await assertDirectPathSnapshot(rootSnapshot);
    await assertDirectPathSnapshot(parentSnapshot);
    await writeAll(handle, bytes, label);
    await handle.sync();
    const written = await handle.stat({ bigint: true });
    if (!sameRecord(pathIdentity(opened), pathIdentity(written))
      || written.size !== BigInt(bytes.byteLength)) {
      throw new Error(`${label} changed while it was written`);
    }
    const current = await assertDirectPathSnapshot(created);
    const currentMetadata = await lstat(current.target, { bigint: true });
    if (!sameRecord(stableFileState(written), stableFileState(currentMetadata))) {
      throw new Error(`${label} path no longer names the written file state`);
    }
    await assertDirectPathSnapshot(rootSnapshot);
    await assertDirectPathSnapshot(parentSnapshot);
  } finally {
    await handle.close();
  }
}
