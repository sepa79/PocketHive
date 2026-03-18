import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { once } from "node:events";
import { spawn } from "node:child_process";

test("import-influxdb3 writes NDJSON batches to the InfluxDB 3 line protocol endpoint", async () => {
  const requests = [];
  const server = http.createServer((req, res) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", () => {
      requests.push({
        method: req.method,
        url: req.url,
        authorization: req.headers.authorization,
        contentType: req.headers["content-type"],
        body
      });
      res.statusCode = 204;
      res.end();
    });
  });

  const tempDir = await mkdtemp(path.join(os.tmpdir(), "tx-outcome-influx-test-"));
  try {
    await once(server.listen(0, "127.0.0.1"), "listening");
    const address = server.address();
    assert.ok(address && typeof address === "object");
    const inputPath = path.join(tempDir, "tx-outcome.ndjson");
    const records = [
      {
        eventTime: "2026-03-01 12:00:00.123",
        swarmId: "swarm-a",
        sinkRole: "postprocessor",
        sinkInstance: "instance-a",
        traceId: "trace-1",
        callId: "call-a",
        processorStatus: 200,
        processorSuccess: 1,
        processorDurationMs: 111,
        businessCode: "approved",
        businessSuccess: 1,
        dimensions: { channel: "api" }
      },
      {
        eventTime: "2026-03-01T12:00:01.123Z",
        swarmId: "swarm-a",
        sinkRole: "postprocessor",
        sinkInstance: "instance-a",
        traceId: "trace-2",
        callId: "",
        processorStatus: 503,
        processorSuccess: 0,
        processorDurationMs: 222,
        businessCode: "",
        businessSuccess: 0,
        dimensions: { channel: "api" }
      },
      {
        eventTime: "2026-03-01 12:00:02.123",
        swarmId: "swarm-b",
        sinkRole: "postprocessor",
        sinkInstance: "instance-b",
        traceId: "trace-3",
        callId: "call-c",
        processorStatus: 404,
        processorSuccess: 0,
        processorDurationMs: 333,
        businessCode: "rejected",
        businessSuccess: 0,
        dimensions: { channel: "mq" }
      }
    ];
    await writeFile(inputPath, records.map((record) => JSON.stringify(record)).join("\n") + "\n", "utf8");

    const child = spawn(process.execPath, [
      "tools/tx-outcome-storage/import-influxdb3.mjs",
      "--input", inputPath,
      "--endpoint", `http://127.0.0.1:${address.port}`,
      "--database", "pockethive",
      "--measurement", "ph_tx_outcome",
      "--token", "test-token",
      "--batch-size", "2"
    ], {
      cwd: "/home/sepa/PocketHive",
      stdio: ["ignore", "pipe", "pipe"]
    });

    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });

    const [exitCode] = await once(child, "exit");
    assert.equal(exitCode, 0, stderr);
    assert.equal(requests.length, 2);

    assert.equal(requests[0].method, "POST");
    assert.equal(requests[0].authorization, "Bearer test-token");
    assert.equal(requests[0].contentType, "text/plain; charset=utf-8");
    assert.match(requests[0].url, /^\/api\/v3\/write_lp\?db=pockethive&precision=ms$/);
    assert.match(requests[0].body, /processorSuccess=1i/);
    assert.match(requests[0].body, /businessSuccess=1i/);
    assert.match(requests[0].body, /callIdKey=unknown/);
    assert.match(requests[0].body, /businessCodeKey=n\/a/);
    assert.match(requests[0].body, /processorStatusClass=5xx/);

    assert.match(requests[1].body, /swarmId=swarm-b/);
    assert.match(requests[1].body, /processorStatusClass=4xx/);
    assert.match(requests[1].body, /callIdKey=call-c/);
    assert.match(requests[1].body, /businessCodeKey=rejected/);
  } finally {
    server.close();
    await rm(tempDir, { recursive: true, force: true });
  }
});
