package io.pockethive.capabilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCatalogueServiceTest {

    @TempDir
    Path capabilitiesDir;

    @Test
    void imageNameLookupCanonicalizesRegistryNamespaceTagAndDigest() throws Exception {
        writeManifest("processor");
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);
        catalogue.reload();

        assertThat(catalogue.findByImageName("processor")).isPresent();
        assertThat(catalogue.findByImageName("pockethive/processor:0.15")).isPresent();
        assertThat(catalogue.findByImageName("192.168.88.54:5000/pockethive/processor:0.15")).isPresent();
        assertThat(catalogue.findByImageName("192.168.88.54:5000/pockethive/processor@sha256:abc")).isPresent();
    }

    private void writeManifest(String imageName) throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "%s"
                role: "processor"
                config: []
                actions: []
                panels: []
                """.formatted(imageName);
        Files.writeString(capabilitiesDir.resolve(imageName + ".yaml"), body);
    }
}
