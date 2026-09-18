import { describe, expect, it } from "vitest";
import {
  applyScenarioConfigDefaults,
  scenarioConfigDefaultsByImage,
} from "../tools/pockethive-mcp/generated/scenario-config-defaults.mjs";

describe("scenario config defaults generated from capability manifests", () => {
  it("materializes worker and selected IO defaults without overriding explicit config", () => {
    expect(applyScenarioConfigDefaults("registry.local/pockethive/generator:dev", {
      inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 7 } },
      outputs: { type: "RABBITMQ" },
      message: { bodyType: "SIMPLE", body: "{}" },
    })).toMatchObject({
      inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 7, maxMessages: 0 } },
    });

    expect(applyScenarioConfigDefaults("postprocessor:latest", {
      txOutcomeSinkMode: "CLICKHOUSE_V2",
    })).toEqual({
      forwardToOutput: false,
      txOutcomeSinkMode: "CLICKHOUSE_V2",
      dropTxOutcomeWithoutCallId: true,
    });
  });

  it("exports the capability-owned postprocessor defaults", () => {
    expect(scenarioConfigDefaultsByImage.postprocessor).toEqual({
      forwardToOutput: false,
      txOutcomeSinkMode: "NONE",
      dropTxOutcomeWithoutCallId: true,
    });
  });
});
