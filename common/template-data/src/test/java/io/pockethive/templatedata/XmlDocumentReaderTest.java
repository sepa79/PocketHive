package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class XmlDocumentReaderTest {
    private final XmlDocumentReader reader = new XmlDocumentReader();
    @TempDir Path directory;

    @Test
    void acceptsGenericXmlWithoutSoapAndDepthExactly256() {
        String document = "<nested>".repeat(255) + "<leaf>000123</leaf>" + "</nested>".repeat(255);
        XmlDocumentView view = reader.read(document);
        assertEquals("000123", new XPathSelector().requiredScalar(view, "//leaf", Map.of()));
        String excessive = "<nested>" + document + "</nested>";
        assertEquals(TemplateDataError.LIMIT_EXCEEDED,
            assertThrows(TemplateDataException.class, () -> reader.read(excessive)).code());
    }

    @Test
    void enforcesExactOneMiBUtf8BoundaryBeforeParsing() {
        String document = "<a>" + "x".repeat(1024 * 1024 - 7) + "</a>";
        reader.read(document);
        assertEquals(TemplateDataError.LIMIT_EXCEEDED,
            assertThrows(TemplateDataException.class, () -> reader.read(document + " ")).code());
        String multibyte = "<a>" + "é".repeat((1024 * 1024 - 7) / 2) + "</a> ";
        reader.read(multibyte);
        assertEquals(TemplateDataError.LIMIT_EXCEEDED,
            assertThrows(TemplateDataException.class, () -> reader.read(multibyte + " ")).code());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "{\"not\":\"xml\"}", "<a", "<secret-card></wrong>", "<a/><b/>", "<a>&undefined;</a>"})
    void rejectsMalformedXmlWithoutLoggingOrExposingContent(String document) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(replacement);
            TemplateDataException failure = assertThrows(TemplateDataException.class, () -> reader.read(document));
            assertEquals(TemplateDataError.INVALID_DOCUMENT, failure.code());
            assertFalse(failure.getMessage().contains("secret-card"));
            assertNull(failure.getCause());
        } finally {
            System.setErr(previous);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsDtdEntitiesAndXincludeWithoutNetworkOrFileDisclosure() throws Exception {
        Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, "secret-card-file");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] content = "secret-card-network".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, content.length);
            try (var output = exchange.getResponseBody()) { output.write(content); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/entity";
            for (String declaration : new String[]{
                    "<!DOCTYPE value [<!ENTITY value SYSTEM '" + secret.toUri() + "'>]>",
                    "<!DOCTYPE value [<!ENTITY value SYSTEM '" + url + "'>]>",
                    "<!DOCTYPE value SYSTEM '" + url + "'>",
                    "<!DOCTYPE value [<!ENTITY % remote SYSTEM '" + url + "'>%remote;]>",
                    "<!DOCTYPE value [<!ENTITY seed 'secret-card'><!ENTITY repeated '&seed;&seed;&seed;'><!ENTITY value '&repeated;&repeated;&repeated;'>]>",
                    "<!DOCTYPE value>"}) {
                TemplateDataException failure = assertThrows(TemplateDataException.class,
                    () -> reader.read(declaration + "<value>&value;</value>"));
                assertEquals(TemplateDataError.INVALID_DOCUMENT, failure.code());
                assertFalse(failure.getMessage().contains("secret-card"));
                assertNull(failure.getCause());
            }
            for (String resource : new String[]{url, secret.toUri().toString()}) {
                String xinclude = "<value xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"" + resource + "\" parse=\"text\"/></value>";
                assertEquals(TemplateDataError.INVALID_DOCUMENT,
                    assertThrows(TemplateDataException.class, () -> reader.read(xinclude)).code());
            }
            String schemaHint = "<value xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"" + url + "\">000123</value>";
            assertEquals("000123", new XPathSelector().requiredScalar(reader.read(schemaHint), "/value", Map.of()));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
