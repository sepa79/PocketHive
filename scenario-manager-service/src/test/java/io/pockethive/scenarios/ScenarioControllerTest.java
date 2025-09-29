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
        String body = """
            {"id":"1","name":"Test","assets":{"suts":[{"id":"sut-1","name":"SUT","entrypoint":"/run.sh","version":"1.0"}],"datasets":[{"id":"dataset-1","name":"Dataset","uri":"s3://bucket/data.json","format":"json"}],"swarmTemplates":[{"id":"template-1","name":"Template","sutId":"sut-1","datasetId":"dataset-1","swarmSize":1}]},"template":{"image":"controller","bees":[]},"tracks":[]}
            """;
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("1"));

        mvc.perform(get("/scenarios").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("1"))
                .andExpect(jsonPath("$[0].name").value("Test"));

        mvc.perform(get("/scenarios/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets.swarmTemplates[0].sutId").value("sut-1"));

        String updateBody = """
            {"id":"1","name":"Updated","assets":{"suts":[{"id":"sut-1","name":"Updated SUT","entrypoint":"/run.sh","version":"1.1"}],"datasets":[{"id":"dataset-1","name":"Dataset","uri":"s3://bucket/data.json","format":"json"}],"swarmTemplates":[{"id":"template-1","name":"Template","sutId":"sut-1","datasetId":"dataset-1","swarmSize":2}]},"template":{"image":"controller","bees":[]},"tracks":[]}
            """;
        mvc.perform(put("/scenarios/1").contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets.swarmTemplates[0].swarmSize").value(2));

        mvc.perform(delete("/scenarios/1"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void yamlSupport() throws Exception {
        String yaml = """
            id: 2
            name: Yaml
            assets:
              suts:
                - id: sut-2
                  name: YAML SUT
                  entrypoint: /run.sh
                  version: "1.0"
              datasets:
                - id: dataset-2
                  name: YAML Dataset
                  uri: s3://bucket/data.json
                  format: json
              swarmTemplates:
                - id: template-2
                  name: YAML Template
                  sutId: sut-2
                  datasetId: dataset-2
                  swarmSize: 1
            template:
              image: controller
              bees: []
            tracks: []
            """;
        mvc.perform(post("/scenarios").contentType("application/x-yaml").content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("2"));

        mvc.perform(get("/scenarios/2").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets.suts[0].entrypoint").value("/run.sh"));
    }

    @Test
    void validationFailure() throws Exception {
        String body = """
            {"id":"","name":"","assets":{"suts":[{"id":"","name":"","entrypoint":"","version":""}],"datasets":[{"id":"","name":"","uri":"","format":""}],"swarmTemplates":[{"id":"","name":"","sutId":"","datasetId":"","swarmSize":0}]},"template":{"image":"controller","bees":[]}}
            """;
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pathTraversalRejected() throws Exception {
        String body = """
            {"id":"../evil","name":"Hack","assets":{"suts":[],"datasets":[],"swarmTemplates":[]},"template":{"image":"controller","bees":[]}}
            """;
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
