import assert from "node:assert/strict";
import {
  link,
  mkdir,
  mkdtemp,
  readFile,
  rename,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  assertDirectPathSnapshot,
  captureDirectDirectorySnapshot,
  captureStableRegularFile,
  createDirectoryUnderSnapshot,
  HARD_LINK_POLICY,
  isPathInside,
  sameFilesystemPath,
  writeNewFileUnderSnapshot,
} from "./file-safety.mjs";

async function withTemporaryDirectory(action) {
  const root = await mkdtemp(path.join(os.tmpdir(), "completed-work-file-safety-"));
  try {
    return await action(root);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test("filesystem equality and containment preserve exact path casing", () => {
  const root = path.resolve("CaseSensitiveBoundary");
  const differentlyCased = path.resolve("casesensitiveboundary");
  assert.notEqual(root, differentlyCased, "The fixture paths must differ by code point");
  assert.equal(sameFilesystemPath(root, differentlyCased), false);
  assert.equal(isPathInside(root, path.join(differentlyCased, "file.txt")), false);
  assert.equal(isPathInside(root, path.join(root, "nested", "file.txt")), true);
});

test("stable regular-file capture binds one direct handle and rejects hard-linked aliases", async () => {
  await withTemporaryDirectory(async (root) => {
    const original = path.join(root, "original.txt");
    const alias = path.join(root, "alias.txt");
    await writeFile(original, "bound bytes", "utf8");
    assert.equal(
      (await captureStableRegularFile({
        anchorPath: root,
        hardLinkPolicy: HARD_LINK_POLICY.REJECT,
        path: original,
        label: "Original fixture",
        maxBytes: 1024,
      })).toString("utf8"),
      "bound bytes",
    );
    await link(original, alias);
    await assert.rejects(
      captureStableRegularFile({
        anchorPath: root,
        hardLinkPolicy: HARD_LINK_POLICY.REJECT,
        path: original,
        label: "Hard-linked fixture",
        maxBytes: 1024,
      }),
      /must not be a hard-linked file/u,
    );
    assert.equal(
      (await captureStableRegularFile({
        anchorPath: root,
        hardLinkPolicy: HARD_LINK_POLICY.ALLOW_STABLE_IDENTITY,
        path: original,
        label: "Explicitly allowed hard-linked fixture",
        maxBytes: 1024,
      })).toString("utf8"),
      "bound bytes",
    );
  });
});

test("Windows alternate data streams are rejected for inputs and outputs", async (t) => {
  if (process.platform !== "win32") {
    t.skip("Alternate data streams are Windows-specific");
    return;
  }
  await withTemporaryDirectory(async (root) => {
    const host = path.join(root, "host.txt");
    const alternateStream = `${host}:payload`;
    await writeFile(host, "host", "utf8");
    try {
      await writeFile(alternateStream, "hidden", "utf8");
    } catch (error) {
      t.skip(`The temporary filesystem does not support alternate data streams: ${error.message}`);
      return;
    }
    await assert.rejects(
      captureStableRegularFile({
        anchorPath: root,
        hardLinkPolicy: HARD_LINK_POLICY.REJECT,
        path: alternateStream,
        label: "Alternate-stream input",
        maxBytes: 1024,
      }),
      /must not use a Windows alternate data stream/u,
    );
    const rootSnapshot = await captureDirectDirectorySnapshot({
      path: root,
      label: "Alternate-stream output root",
    });
    await assert.rejects(
      writeNewFileUnderSnapshot({
        rootSnapshot,
        path: `${path.join(root, "output.txt")}:payload`,
        bytes: Buffer.from("hidden output", "utf8"),
        label: "Alternate-stream output",
      }),
      /must not use a Windows alternate data stream/u,
    );
  });
});

test("ancestor identity snapshots detect rename-and-replacement without symlink privileges", async () => {
  await withTemporaryDirectory(async (root) => {
    const owned = path.join(root, "owned");
    const displaced = path.join(root, "displaced");
    await mkdir(owned);
    const snapshot = await captureDirectDirectorySnapshot({
      anchorPath: root,
      path: owned,
      label: "Owned directory",
    });
    await rename(owned, displaced);
    await mkdir(owned);
    await assert.rejects(
      assertDirectPathSnapshot(snapshot),
      /path identity changed after validation/u,
    );
  });
});

test("stale staging snapshots reject directory creation and file writes in a replacement", async () => {
  await withTemporaryDirectory(async (root) => {
    const staging = path.join(root, "staging");
    const displaced = path.join(root, "displaced");
    await mkdir(staging);
    const snapshot = await captureDirectDirectorySnapshot({
      anchorPath: root,
      path: staging,
      label: "Staging directory",
    });
    await rename(staging, displaced);
    await mkdir(staging);
    await assert.rejects(
      createDirectoryUnderSnapshot({
        rootSnapshot: snapshot,
        path: path.join(staging, "nested"),
        label: "Nested output",
      }),
      /path identity changed after validation/u,
    );
    await assert.rejects(
      writeNewFileUnderSnapshot({
        rootSnapshot: snapshot,
        path: path.join(staging, "redirected.txt"),
        bytes: Buffer.from("must not be written", "utf8"),
        label: "Redirected output",
      }),
      /path identity changed after validation/u,
    );
    assert.equal(await stat(path.join(staging, "nested")).catch(() => null), null);
    assert.equal(await stat(path.join(staging, "redirected.txt")).catch(() => null), null);
  });
});

test("output creation rejects a replaced parent before content is written", async () => {
  await withTemporaryDirectory(async (root) => {
    const parent = path.join(root, "parent");
    const displaced = path.join(root, "displaced");
    const output = path.join(parent, "output");
    await mkdir(parent);
    const parentSnapshot = await captureDirectDirectorySnapshot({
      anchorPath: root,
      path: parent,
      label: "Output parent",
    });
    await rename(parent, displaced);
    await mkdir(parent);
    await assert.rejects(
      createDirectoryUnderSnapshot({
        rootSnapshot: parentSnapshot,
        path: output,
        label: "Bundle output",
      }),
      /path identity changed after validation|must not traverse/u,
    );
    assert.equal(await stat(output).catch(() => null), null);
    assert.ok((await stat(displaced)).isDirectory());
  });
});

test("owned output writes are exclusive and preserve their directory identity", async () => {
  await withTemporaryDirectory(async (root) => {
    const parentSnapshot = await captureDirectDirectorySnapshot({
      path: root,
      label: "Output parent",
    });
    const output = path.join(root, "output");
    const outputSnapshot = await createDirectoryUnderSnapshot({
      rootSnapshot: parentSnapshot,
      path: output,
      label: "Output directory",
    });
    const nested = path.join(output, "nested");
    await createDirectoryUnderSnapshot({
      rootSnapshot: outputSnapshot,
      path: nested,
      label: "Nested directory",
    });
    const outputFile = path.join(nested, "result.txt");
    await writeNewFileUnderSnapshot({
      rootSnapshot: outputSnapshot,
      path: outputFile,
      bytes: Buffer.from("verified result", "utf8"),
      label: "Result file",
    });
    await assert.rejects(
      writeNewFileUnderSnapshot({
        rootSnapshot: outputSnapshot,
        path: outputFile,
        bytes: Buffer.from("overwrite", "utf8"),
        label: "Result file",
      }),
      /already exists/u,
    );
    await assertDirectPathSnapshot(outputSnapshot);
    assert.equal(await readFile(path.join(output, "nested", "result.txt"), "utf8"), "verified result");
  });
});
