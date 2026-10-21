package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SoapEnvelopeTest {
    private static final Map<String, String> NAMESPACES = Map.of("cards", "urn:synthetic:cards");
    private static final String ACCOUNT_PATH = "/soap:Envelope/soap:Body/cards:created/cards:accountNumber";
    private final SoapEnvelope soap = new SoapEnvelope();
    private final XPathSelector selector = new XPathSelector();

    @ParameterizedTest
    @EnumSource(SoapVersion.class)
    void extractsEitherSoapVersionWithReservedSoapPrefixAndDifferentSourcePrefixes(SoapVersion version) {
        String xml = envelope(version,
            "<different:created xmlns:different=\"urn:synthetic:cards\"><different:accountNumber>000123</different:accountNumber></different:created>");
        XmlDocumentView view = soap.read(version, xml);
        Map<String, String> namespaces = soap.namespaces(version, NAMESPACES);
        assertEquals(version.namespace(), namespaces.get("soap"));
        assertEquals("000123", selector.requiredScalar(view, ACCOUNT_PATH, namespaces));
        assertEquals(Map.of("cards", "urn:synthetic:cards"), NAMESPACES);
        assertThrows(UnsupportedOperationException.class, () -> namespaces.put("other", "urn:other"));
    }

    @ParameterizedTest
    @EnumSource(SoapVersion.class)
    void supportsDefaultNamespacesOptionalHeaderAttributesAndCdata(SoapVersion version) {
        String xml = "<Envelope xmlns=\"" + version.namespace() + "\"><Header><meta:trace xmlns:meta=\"urn:trace\">ignored</meta:trace></Header>"
            + "<Body><created xmlns=\"urn:synthetic:cards\" version=\"0007\"><accountNumber><![CDATA[000123]]></accountNumber>"
            + "<name>Card &amp; Account</name></created></Body></Envelope>";
        XmlDocumentView view = soap.read(version, xml);
        Map<String, String> namespaces = soap.namespaces(version, NAMESPACES);
        assertEquals("000123", selector.requiredScalar(view, ACCOUNT_PATH + "/text()", namespaces));
        assertEquals("0007", selector.requiredScalar(view, "/soap:Envelope/soap:Body/cards:created/@version", namespaces));
        assertEquals("Card & Account", selector.requiredScalar(view, "//cards:name", namespaces));
    }

    @ParameterizedTest
    @EnumSource(SoapVersion.class)
    void rejectsFaultEvenWhenTheCallerReceivedHttp200(SoapVersion version) {
        String xml = envelope(version, "<env:Fault><faultcode>env:Server</faultcode><faultstring>secret-card</faultstring></env:Fault>");
        TemplateDataException failure = assertThrows(TemplateDataException.class, () -> soap.read(version, xml));
        assertEquals(TemplateDataError.INVALID_VALUE, failure.code());
        assertFalse(failure.getMessage().contains("secret-card"));
        assertNull(failure.getCause());
    }

    @Test
    void rejectsRealSoap12FaultWithCodeReasonAndDetail() {
        String xml = envelope(SoapVersion.SOAP12, "<env:Fault><env:Code><env:Value>env:Receiver</env:Value></env:Code>"
            + "<env:Reason><env:Text xml:lang=\"en\">secret-card</env:Text></env:Reason><env:Detail>hidden</env:Detail></env:Fault>");
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> soap.read(SoapVersion.SOAP12, xml)).code());
    }

    @ParameterizedTest
    @EnumSource(SoapVersion.class)
    void rejectsWrongVersionFaultEvenAlongsideOtherwiseValidData(SoapVersion version) {
        SoapVersion other = version == SoapVersion.SOAP11 ? SoapVersion.SOAP12 : SoapVersion.SOAP11;
        String xml = envelope(version, "<wrong:Fault xmlns:wrong=\"" + other.namespace() + "\"/>"
            + "<cards:created xmlns:cards=\"urn:synthetic:cards\"><cards:accountNumber>000123</cards:accountNumber></cards:created>");
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> soap.read(version, xml)).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<env:Body/><env:Body/>", "<env:Header/>", "<env:Body/><env:Header/>",
        "<env:Other/><env:Body/>", "<Body/>", "<wrong:Body xmlns:wrong=\"urn:wrong\"/>", "secret-card<env:Body/>",
        "<env:Header/><env:Header/><env:Body/>", "<env:Body>secret-card</env:Body>", "<![CDATA[secret-card]]><env:Body/>"})
    void rejectsInvalidEnvelopeStructureAndUnexpectedText(String content) {
        String xml = "<env:Envelope xmlns:env=\"" + SoapVersion.SOAP11.namespace() + "\">" + content + "</env:Envelope>";
        TemplateDataException failure = assertThrows(TemplateDataException.class, () -> soap.read(SoapVersion.SOAP11, xml));
        assertEquals(TemplateDataError.INVALID_DOCUMENT, failure.code());
        assertFalse(failure.getMessage().contains("secret-card"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<env:Body xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"/>",
        "<Envelope><Body/></Envelope>", "<env:Envelope xmlns:env=\"urn:wrong\"><env:Body/></env:Envelope>"})
    void rejectsWrongRootOrSoapNamespace(String xml) {
        assertEquals(TemplateDataError.INVALID_DOCUMENT,
            assertThrows(TemplateDataException.class, () -> soap.read(SoapVersion.SOAP11, xml)).code());
    }

    @ParameterizedTest
    @EnumSource(SoapVersion.class)
    void rejectsWrongEnvelopeVersion(SoapVersion version) {
        SoapVersion other = version == SoapVersion.SOAP11 ? SoapVersion.SOAP12 : SoapVersion.SOAP11;
        assertEquals(TemplateDataError.INVALID_DOCUMENT,
            assertThrows(TemplateDataException.class, () -> soap.read(version, envelope(other, ""))).code());
    }

    @Test
    void protocolValidationDoesNotRejectBusinessElementsNamedFaultOrNormalizeScalars() {
        String xml = envelope(SoapVersion.SOAP11,
            "<cards:Fault xmlns:cards=\"urn:synthetic:cards\"><cards:empty/><cards:text> 000123 </cards:text></cards:Fault>");
        XmlDocumentView view = soap.read(SoapVersion.SOAP11, xml);
        Map<String, String> namespaces = soap.namespaces(SoapVersion.SOAP11, NAMESPACES);
        assertEquals("", selector.requiredScalar(view, "//cards:empty", namespaces));
        assertEquals(" 000123 ", selector.requiredScalar(view, "//cards:text", namespaces));
    }

    @ParameterizedTest
    @ValueSource(strings = {"soap", "SOAP", "SoAp", "xml", "xmlns", "xmlOther", "", "has:colon", "has space", "9prefix"})
    void rejectsReservedOrInvalidSuppliedNamespacePrefixes(String prefix) {
        assertEquals(TemplateDataError.INVALID_SELECTOR, assertThrows(TemplateDataException.class,
            () -> soap.namespaces(SoapVersion.SOAP11, Map.of(prefix, "urn:cards"))).code());
    }

    @Test
    void rejectsNullNamespaceMapKeysAndValues() {
        Map<String, String> nullPrefix = new HashMap<>();
        nullPrefix.put(null, "urn:cards");
        Map<String, String> nullUri = new HashMap<>();
        nullUri.put("cards", null);
        for (Map<String, String> namespaces : java.util.Arrays.asList(null, nullPrefix, nullUri)) {
            assertEquals(TemplateDataError.INVALID_SELECTOR, assertThrows(TemplateDataException.class,
                () -> soap.namespaces(SoapVersion.SOAP11, namespaces)).code());
        }
    }

    @Test
    void generatesCanonicalPdHeadersIncludingEmptyActionWithoutTrimming() {
        assertEquals(Map.of("Content-Type", "text/xml; charset=utf-8", "SOAPAction", "\"urn:create\""),
            soap.headers(SoapVersion.SOAP11, "urn:create"));
        assertEquals(Map.of("Content-Type", "application/soap+xml; charset=utf-8; action=\"urn:create\""),
            soap.headers(SoapVersion.SOAP12, "urn:create"));
        assertEquals("\"\"", soap.headers(SoapVersion.SOAP11, "").get("SOAPAction"));
        assertEquals("application/soap+xml; charset=utf-8; action=\"\"", soap.headers(SoapVersion.SOAP12, "").get("Content-Type"));
        assertEquals("\" urn:create \"", soap.headers(SoapVersion.SOAP11, " urn:create ").get("SOAPAction"));
        assertThrows(UnsupportedOperationException.class, () -> soap.headers(SoapVersion.SOAP11, "").put("x", "y"));
        soap.headers(SoapVersion.SOAP12, "a".repeat(2048));
        assertThrows(TemplateDataException.class, () -> soap.headers(SoapVersion.SOAP12, "a".repeat(2049)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"bad\rheader", "bad\nheader", "bad\theader", "bad\"quote", "bad\\escape", "é", "\u007f"})
    void rejectsUnsafeActionsForBothVersions(String action) {
        for (SoapVersion version : SoapVersion.values()) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> soap.headers(version, action)).code());
        }
    }

    @Test
    void requiresExplicitSoapVersionForEveryOperation() {
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> soap.read(null, "bad XML")).code());
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> soap.namespaces(null, Map.of())).code());
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> soap.headers(null, "")).code());
    }

    private String envelope(SoapVersion version, String content) {
        return "<env:Envelope xmlns:env=\"" + version.namespace() + "\"><env:Body>" + content + "</env:Body></env:Envelope>";
    }
}
