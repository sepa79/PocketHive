package io.pockethive.tcpmock.service;


import org.springframework.stereotype.Service;

/**
 * Responsibility: read documentation using the existing locations and order.
 * Must not: validate HTTP input or construct HTTP responses.
 * Contract: RESP-TCP-MOCK-WEB-TOOLS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-web-tools.
 */
@Service
public class DocumentationReader {

    public DocumentationReader() {
    }

    public String read(String filename) throws java.io.IOException {
      java.nio.file.Path path = java.nio.file.Paths.get("/app/docs", filename);
      if (java.nio.file.Files.exists(path)) {
        String content = java.nio.file.Files.readString(path);
        return content;
      }

      java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("docs/" + filename);
      if (is == null) {
        is = getClass().getClassLoader().getResourceAsStream(filename);
      }
      if (is != null) {
        String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return content;
      }

      return null;
    }

}
