package io.pockethive.templating;

import static org.junit.jupiter.api.Assertions.*;

import io.pockethive.requesttemplates.PortableCapability;
import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.PortableHttpCallParser;
import io.pockethive.requesttemplates.PortableResponseExtractor;
import io.pockethive.templatedata.JsonDocumentReader;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templating.api.DisabledSequenceAccess;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PortableHttpRendererTest {
    private final PortableHttpCallParser parser = new PortableHttpCallParser();
    private final PortableHttpRenderer renderer = new PortableHttpRenderer();
    private final PortableResponseExtractor responses = new PortableResponseExtractor();
    private final PortableHttpBinding binding = new PortableHttpBinding(URI.create("https://example.test/cards?tenant=qa"),
        Map.of("customerId", "000123", "accountNumber", "0001 & <account>"), Map.of("productClass", "A\"'&"));

    @Test void sameAuthoredJsonCallPreservesDataAndSeparatesRecordFromParams() throws Exception {
        PortableHttpCall call = fixture("account-json");
        PortableHttpRequest request = renderer.render(call, binding);
        assertEquals(binding.endpoint(), request.uri());
        assertEquals("POST", request.method());
        var document = new JsonDocumentReader().read(request.body());
        assertEquals("000123", document.at("/customerId").textValue());
        assertEquals("A\"'&", document.at("/productClass").textValue());
        assertEquals(Map.of("accountNumber", "000777"), responses.extract(call, 201, "{\"accountNumber\":\"000777\"}"));
        assertThrows(TemplateDataException.class, () -> renderer.render(call, new PortableHttpBinding(binding.endpoint(), Map.of(), binding.params())));
    }
    @ParameterizedTest @ValueSource(strings = {"card-soap11", "card-soap12"})
    void soapHeadersEscapingAndPrefixIndependentResponseUseTheSameCall(String fixture) throws Exception {
        PortableHttpCall call = fixture(fixture);
        PortableHttpRequest request = renderer.render(call, binding);
        String namespace = call.response().kind().name().equals("SOAP11") ? "http://schemas.xmlsoap.org/soap/envelope/" : "http://www.w3.org/2003/05/soap-envelope";
        assertTrue(request.body().contains("0001 &amp; &lt;account&gt;"));
        assertTrue(request.body().contains("class=\"A&quot;&apos;&amp;\""));
        assertTrue(request.headers().get("Content-Type").startsWith(fixture.endsWith("11") ? "text/xml" : "application/soap+xml"));
        String body = "<other:Envelope xmlns:other=\"" + namespace + "\"><other:Body><x:CreateCardResponse xmlns:x=\"urn:cards\"><x:cardNumber> 00042 </x:cardNumber></x:CreateCardResponse></other:Body></other:Envelope>";
        assertEquals(Map.of("cardNumber", " 00042 "), responses.extract(call, 200, body));
        String fault = "<s:Envelope xmlns:s=\"" + namespace + "\"><s:Body><s:Fault/></s:Body></s:Envelope>";
        assertThrows(TemplateDataException.class, () -> responses.extract(call, 200, fault));
    }
    @ParameterizedTest @ValueSource(strings = {"{{ eval('1 + 1') }}", "{{ sequence('key') }}", "{{ xml_path(record, '//id', {}) }}"})
    void unsupportedFunctionsFailCompilationBeforeRendering(String source) {
        PortableTemplateRenderer profile = new PortableTemplateRenderer(Set.of(PortableCapability.JSON_PATH));
        assertThrows(TemplateDataException.class, () -> profile.validateSyntax(source));
    }
    @Test void aliasesAreNotSilentlyMappedIntoThePortableProfile() {
        PortableTemplateRenderer profile = new PortableTemplateRenderer(Set.of(PortableCapability.JSON_PATH));
        for (String alias : new String[]{"payload", "payloadAsJson", "vars", "ctx", "endpointPath"}) {
            assertThrows(TemplateDataException.class, () -> profile.render("{{ " + alias + ".customerId }}", binding.context()));
        }
    }
    @Test void sameRequiredFunctionsAreAvailableThroughDirectPebbleAndSpelWithoutCoercion() {
        PebbleTemplateRenderer current = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        Map<String, Object> context = Map.of("payload", "{\"id\":\"00012\"}");
        assertEquals("00012", current.render("{{ json_path(payload, '/id') }}", context));
        assertEquals("00012", current.render("{{ eval(\"#json_path(payload, '/id')\") }}", context));
        for (String input : new String[]{"{}", "{\"id\":null}", "{\"id\":\"a\"} {}", "{\"id\":1,\"id\":2}"}) {
            assertThrows(RuntimeException.class, () -> current.render("{{ eval(\"#json_path(payload, '/id')\") }}", Map.of("payload", input)));
        }
        assertThrows(RuntimeException.class, () -> current.render("{{ eval(\"#xml_text(123)\") }}", Map.of()));
        assertThrows(RuntimeException.class, () -> current.render("{{ eval(\"#json_path(payload, 123)\") }}", context));
    }
    @Test void invalidProfileCapabilitiesHeadersAndSelectorsFailAdmission() throws Exception {
        String text = resource("account-json");
        for (String invalid : new String[]{text.replace("portable-v1", "portable-v2"), text.replace("json_string", "eval"), text.replace("/accountNumber", "/bad~2"), text.replace("201", "201.0"), text + " {}"}) {
            assertThrows(TemplateDataException.class, () -> parser.parse(invalid));
        }
        PortableHttpCall call = fixture("account-json");
        var request = call.request();
        for (String path : new String[]{"https://other.test", "//other.test", "/\\other.test", "/cards#fragment"}) {
            var changed = new io.pockethive.requesttemplates.HttpTemplateDefinition(request.serviceId(), request.callId(), request.protocol(), request.method(), path, request.bodyTemplate(), request.headersTemplate(), null, null);
            assertThrows(TemplateDataException.class, () -> renderer.render(new PortableHttpCall(call.profile(), call.requires(), changed, call.response()), binding));
        }
    }
    private PortableHttpCall fixture(String name) throws Exception { return parser.parse(resource(name)); }
    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/portable/examples/" + name + ".json")) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
