package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class XPathSelectorTest {
    private static final Map<String, String> NAMESPACES = Map.of("cards", "urn:synthetic:cards");
    private final XPathSelector selector = new XPathSelector();

    @Test
    void selectsByNamespaceUriWithDifferentPrefixesOrDefaultNamespaces() {
        for (String xml : new String[]{
                "<different:created xmlns:different=\"urn:synthetic:cards\"><different:accountNumber>000123</different:accountNumber></different:created>",
                "<created xmlns=\"urn:synthetic:cards\"><accountNumber>000123</accountNumber></created>"}) {
            assertEquals("000123", selector.requiredScalar(xml, "/cards:created/cards:accountNumber", NAMESPACES));
        }
    }

    @Test
    void preservesEmptyWhitespaceCdataAttributesAndEntityText() {
        String xml = "<created version=\"0007\" xml:lang=\"en\"><empty/><text> 000123 </text><cdata><![CDATA[000123]]></cdata><name>Card &amp; Account</name></created>";
        XmlDocumentView view = new XmlDocumentReader().read(xml);
        assertEquals("", selector.requiredScalar(view, "//empty", Map.of()));
        assertEquals(" 000123 ", selector.requiredScalar(view, "//text", Map.of()));
        assertEquals("000123", selector.requiredScalar(view, "//cdata/text()", Map.of()));
        assertEquals("0007", selector.requiredScalar(view, "/created/@version", Map.of()));
        assertEquals("en", selector.requiredScalar(view, "/created/@xml:lang", Map.of()));
        assertEquals("Card & Account", selector.requiredScalar(view, "//name", Map.of()));
    }

    @Test
    void rejectsMissingAmbiguousAndNonScalarNodes() {
        String xml = "<root><value>1</value><value>2</value><!--secret-card--><?data hidden?></root>";
        assertEquals(TemplateDataError.MISSING_VALUE,
            assertThrows(TemplateDataException.class, () -> selector.requiredScalar(xml, "//absent", Map.of())).code());
        for (String expression : new String[]{"//value", "/", "/root", "//comment()", "//processing-instruction()"}) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> selector.requiredScalar(xml, expression, Map.of())).code());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "/root[", "/unknown:Envelope", "count(//*)", "string(//*)", "$missing",
        "document('file:///nonexistent-secret-card')", "//value[false() and $missing]", "//value[true() or $missing]"})
    void validatesNodeSelectorsBeforeReadingTheSource(String expression) {
        TemplateDataException failure = assertThrows(TemplateDataException.class,
            () -> selector.requiredScalar("bad XML", expression, Map.of()));
        assertEquals(TemplateDataError.INVALID_SELECTOR, failure.code());
        assertFalse(failure.getMessage().contains("secret-card"));
        assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"system:getProperty('user.home')", "//value[false() and system:getProperty('user.home')]",
        "//value[true() or system:getProperty('user.home')]", "//value[system:getProperty ('user.home')]",
        "//value[child::system:getProperty('user.home')]", "//value[false() and system:get\u0301Property('user.home')]"})
    void rejectsExtensionsEvenInUnreachablePredicates(String expression) {
        assertEquals(TemplateDataError.INVALID_SELECTOR, assertThrows(TemplateDataException.class,
            () -> selector.validate(expression, Map.of("system", "http://xml.apache.org/xalan/java/java.lang.System"))).code());
    }

    @Test
    void allowsSafePredicatesAxesAndLiteralDollarsOrQualifiedFunctionText() {
        String xml = "<root><value>$missing system:getProperty()</value></root>";
        assertEquals("$missing system:getProperty()", selector.requiredScalar(xml,
            "/root/child::value[starts-with(text(), '$missing') and contains(text(), 'system:getProperty()')]", Map.of()));
        assertEquals("$missing system:getProperty()", selector.requiredScalar(xml, "/root/value/child::text()", Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "xmlns", "xmlOther", "", "has:colon", "has space", "9prefix"})
    void rejectsInvalidAndXmlReservedNamespacePrefixes(String prefix) {
        assertEquals(TemplateDataError.INVALID_SELECTOR,
            assertThrows(TemplateDataException.class, () -> selector.validate("/root", Map.of(prefix, "urn:cards"))).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://www.w3.org/XML/1998/namespace", "http://www.w3.org/2000/xmlns/"})
    void rejectsInvalidAndReservedNamespaceValues(String uri) {
        assertEquals(TemplateDataError.INVALID_SELECTOR,
            assertThrows(TemplateDataException.class, () -> selector.validate("/root", Map.of("cards", uri))).code());
    }

    @Test
    void namespaceUrisAreNamesNotResourcesAndSoapPrefixIsNotAGenericReservation() {
        String namespace = "file:///nonexistent-secret-card";
        String xml = "<value xmlns=\"" + namespace + "\">000123</value>";
        assertEquals("000123", selector.requiredScalar(xml, "/soap:value", Map.of("soap", namespace)));
    }

    @Test
    void rejectsMissingBindingsAndNonXmlSourceTypes() {
        Map<String, String> nullPrefix = new HashMap<>();
        nullPrefix.put(null, "urn:cards");
        Map<String, String> nullUri = new HashMap<>();
        nullUri.put("cards", null);
        for (Map<String, String> namespaces : java.util.Arrays.asList(null, nullPrefix, nullUri)) {
            assertEquals(TemplateDataError.INVALID_SELECTOR,
                assertThrows(TemplateDataException.class, () -> selector.validate("/root", namespaces)).code());
        }
        for (Object source : java.util.Arrays.asList(null, Map.of(), 12, new StringBuilder("<root/>"))) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> selector.requiredScalar(source, "/root", Map.of())).code());
        }
    }

    @Test
    void extractedCodeLookingValuesRemainData() {
        String value = "${T(java.lang.Runtime).getRuntime().exec('do-not-run')} {{ data.secret }}";
        assertEquals(value, selector.requiredScalar("<value>" + value + "</value>", "/value", Map.of()));
    }
}
