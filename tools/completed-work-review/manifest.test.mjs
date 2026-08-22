import assert from "node:assert/strict";
import {
  link,
  mkdir,
  mkdtemp,
  rm,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { sha256 } from "../docs-impact/canonical.mjs";
import {
  bundleChecksumText,
  bundleDigest,
  verifyBundleDirectory,
} from "./manifest.mjs";

async function withBundle(files, action) {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "completed-work-manifest-"));
  const bundle = path.join(temporary, "bundle");
  await mkdir(bundle);
  try {
    const entries = [];
    for (const [bundlePath, bytes] of Object.entries(files)) {
      const absolutePath = path.join(bundle, ...bundlePath.split("/"));
      await mkdir(path.dirname(absolutePath), { recursive: true });
      await writeFile(absolutePath, bytes);
      entries.push({ path: bundlePath, sha256: sha256(bytes) });
    }
    const digest = bundleDigest(entries);
    await writeFile(path.join(bundle, "bundle.sha256"), bundleChecksumText(entries), "utf8");
    return await action({ bundle, digest, entries });
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}

test("bundle verification captures exact direct files through stable handles", async () => {
  await withBundle({
    "nested/result.txt": Buffer.from("verified result", "utf8"),
    "review.json": Buffer.from("{}", "utf8"),
  }, async ({ bundle, digest }) => {
    assert.deepEqual(await verifyBundleDirectory(bundle, digest), {
      bundleDigest: digest,
      fileCount: 2,
    });
  });
});

test("bundle verification rejects hard-linked file aliases", async () => {
  await withBundle({
    "review.json": Buffer.from("{}", "utf8"),
  }, async ({ bundle }) => {
    const original = path.join(bundle, "review.json");
    const alias = path.join(bundle, "review-alias.json");
    await link(original, alias);
    const entries = [
      { path: "review-alias.json", sha256: sha256(Buffer.from("{}", "utf8")) },
      { path: "review.json", sha256: sha256(Buffer.from("{}", "utf8")) },
    ];
    const digest = bundleDigest(entries);
    await writeFile(path.join(bundle, "bundle.sha256"), bundleChecksumText(entries), "utf8");
    await assert.rejects(
      verifyBundleDirectory(bundle, digest),
      /must not be a hard-linked file/u,
    );
  });
});

test("bundle checksum paths preserve exact casing", async () => {
  await withBundle({
    "CaseSensitive.json": Buffer.from("{}", "utf8"),
  }, async ({ bundle }) => {
    const entries = [{
      path: "casesensitive.json",
      sha256: sha256(Buffer.from("{}", "utf8")),
    }];
    const digest = bundleDigest(entries);
    await writeFile(path.join(bundle, "bundle.sha256"), bundleChecksumText(entries), "utf8");
    await assert.rejects(
      verifyBundleDirectory(bundle, digest),
      /Bundle file set does not match bundle.sha256/u,
    );
  });
});
