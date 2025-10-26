package io.pockethive.worker.sdk.capabilities;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerCapabilitiesManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Map<String, Expectation> EXPECTATIONS = Map.of(
        "generator", new Expectation(
            Path.of("../../generator-service/src/main/resources/pockethive/capabilities/generator.json"),
            "1.0.0",
            "1457a888f82366b4180160c313c4d75507d557b5a957a553a9ed958480c840aa"
        ),
        "trigger", new Expectation(
            Path.of("../../trigger-service/src/main/resources/pockethive/capabilities/trigger.json"),
            "1.0.0",
            "d9e5cf16db9f9f0b0e6e277dedeb5b9d403a84499a919f85a6b5c8f287338b6c"
        ),
        "moderator", new Expectation(
            Path.of("../../moderator-service/src/main/resources/pockethive/capabilities/moderator.json"),
            "1.0.0",
            "a5bf4ef312f8e1f229deba6d58b9ca6408bd28614af4559d93682a940e092f3e"
        ),
        "processor", new Expectation(
            Path.of("../../processor-service/src/main/resources/pockethive/capabilities/processor.json"),
            "1.0.0",
            "e4cd99d3a3532c4e0509a63b7c7d1beb3e76bade52b539247789ad327e0da4cb"
        ),
        "postprocessor", new Expectation(
            Path.of("../../postprocessor-service/src/main/resources/pockethive/capabilities/postprocessor.json"),
            "1.0.0",
            "8a4fa80afe669a312770df620ddbccf3c22611f01e711c2172ee477a99981130"
        )
    );

    @Test
    void manifestsMatchExpectedFingerprints() throws Exception {
        for (Map.Entry<String, Expectation> entry : EXPECTATIONS.entrySet()) {
            String role = entry.getKey();
            Path manifestPath = entry.getValue().path();
            assertThat(Files.exists(manifestPath))
                .withFailMessage("Missing manifest for role %s at %s", role, manifestPath)
                .isTrue();
            byte[] bytes = Files.readAllBytes(manifestPath);
            String digest = sha256(bytes);
            Map<String, Object> json = MAPPER.readValue(bytes, MAP_TYPE);
            assertThat(json.get("capabilitiesVersion"))
                .as("capabilitiesVersion for %s", role)
                .isEqualTo(entry.getValue().version());
            assertThat(digest)
                .as("sha256 for %s", role)
                .isEqualTo(entry.getValue().sha256());
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HEX.formatHex(digest.digest(data));
    }

    private record Expectation(Path path, String version, String sha256) { }
}
