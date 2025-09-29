package io.pockethive.scenarios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ScenarioControllerTest {

    @Autowired
    MockMvc mvc;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("scenarios.dir", () -> tempDir.toString());
    }

    @Test
    void crudOperations() throws Exception {
        String body = scenarioJson("checkout-peak", "Checkout Peak");
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("checkout-peak"))
                .andExpect(jsonPath("$.assets.suts[0].id").value("sut-api"))
                .andExpect(jsonPath("$.scenario.tracks[0].blocks[0].type").value("Ramp"));

        mvc.perform(get("/scenarios").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("checkout-peak"))
                .andExpect(jsonPath("$[0].name").value("Checkout Peak"));

        mvc.perform(get("/scenarios/checkout-peak").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario.tracks[0].datasetRef").value("users"));

        String updated = scenarioJson("checkout-peak", "Checkout Peak Updated");
        mvc.perform(put("/scenarios/checkout-peak").contentType(MediaType.APPLICATION_JSON).content(updated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Checkout Peak Updated"))
                .andExpect(jsonPath("$.scenario.tracks[0].blocks[1].name").value("gate-open"));

        mvc.perform(delete("/scenarios/checkout-peak"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/checkout-peak").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void yamlSupport() throws Exception {
        String yaml = scenarioYaml("signal-fanout", "Signal Fanout");
        mvc.perform(post("/scenarios").contentType("application/x-yaml").content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("signal-fanout"))
                .andExpect(jsonPath("$.scenario.tracks[0].blocks[0].signal").value("ready"));

        mvc.perform(get("/scenarios/signal-fanout").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario.tracks[0].blocks[1].rateFrom").value(2));
    }

    @Test
    void validationFailure() throws Exception {
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(invalidScenarioJson()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pathTraversalRejected() throws Exception {
        String body = scenarioJson("../evil", "Hack Attempt");
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private String scenarioJson(String id, String name) {
        return ("""
                {
                  "id": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  "description": "Load scenario",
                  "metadata": {
                    "labels": ["test"]
                  },
                  "assets": {
                    "suts": [
                      {
                        "id": "sut-api",
                        "name": "Shop API",
                        "baseUrls": ["https://sut.test"]
                      }
                    ],
                    "datasets": [
                      {
                        "id": "users",
                        "name": "Users",
                        "provider": {
                          "type": "inline",
                          "inline": {
                            "rows": [
                              {
                                "id": "user-1"
                              }
                            ]
                          }
                        }
                      }
                    ],
                    "swarmTemplates": [
                      {
                        "id": "template-a",
                        "name": "Template A",
                        "processor": {
                          "sutRef": "sut-api",
                          "endpointRef": "checkout",
                          "headers": {
                            "Content-Type": "application/json"
                          }
                        }
                      }
                    ]
                  },
                  "scenario": {
                    "schedule": {
                      "startAt": "2025-01-01T00:00:00Z"
                    },
                    "runConfig": {
                      "runPrefix": "testrun",
                      "allowParallel": true
                    },
                    "tracks": [
                      {
                        "name": "Primary",
                        "templateRef": "template-a",
                        "datasetRef": "users",
                        "instances": 1,
                        "blocks": [
                          {
                            "type": "Ramp",
                            "rateFrom": 1,
                            "rateTo": 10,
                            "durationSec": 60
                          },
                          {
                            "type": "Signal",
                            "name": "gate-open"
                          }
                        ]
                      }
                    ]
                  }
                }
                """).formatted(id, name);
    }

    private String scenarioYaml(String id, String name) {
        return ("""
                id: %s
                name: %s
                version: 0.1.0
                assets:
                  suts:
                    - id: sut-api
                      name: Shop API
                  datasets:
                    - id: users
                      name: Users
                      provider:
                        type: redis
                        redis:
                          keys:
                            - ph:users:*
                  swarmTemplates:
                    - id: template-a
                      name: Template A
                      processor:
                        sutRef: sut-api
                scenario:
                  schedule:
                    startAt: 2025-02-02T00:00:00Z
                  tracks:
                    - name: Gate
                      templateRef: template-a
                      blocks:
                        - type: WaitFor
                          signal: ready
                        - type: Ramp
                          rateFrom: 2
                          rateTo: 5
                          durationSec: 30
                """
        ).formatted(id, name);
    }

    private String invalidScenarioJson() {
        return """
                {
                  "id": "broken",
                  "name": "Broken",
                  "version": "0.1.0",
                  "assets": {
                    "suts": [
                      {
                        "id": "sut-api",
                        "name": "Shop API"
                      }
                    ],
                    "datasets": [
                      {
                        "id": "users",
                        "name": "Users",
                        "provider": {
                          "type": "redis"
                        }
                      }
                    ],
                    "swarmTemplates": [
                      {
                        "id": "template-a",
                        "name": "Template A",
                        "processor": {
                          "sutRef": "sut-api"
                        }
                      }
                    ]
                  },
                  "scenario": {
                    "schedule": {
                      "startAt": "2025-01-01T00:00:00Z"
                    },
                    "tracks": [
                      {
                        "name": "Primary",
                        "templateRef": "template-a",
                        "blocks": [
                          {
                            "type": "Ramp",
                            "rateFrom": 1,
                            "rateTo": 5,
                            "durationSec": 30
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
    }
}
