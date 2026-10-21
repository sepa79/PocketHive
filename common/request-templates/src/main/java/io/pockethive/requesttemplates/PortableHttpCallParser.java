package io.pockethive.requesttemplates;

import io.pockethive.templatedata.JsonDocumentReader;
import io.pockethive.templatedata.JsonPointerSelector;
import io.pockethive.templatedata.SoapEnvelope;
import io.pockethive.templatedata.SoapVersion;
import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templatedata.XPathSelector;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: admit the explicit portable HTTP call contract through the canonical request parser.
 * Must not: compile engine syntax, bind product data, access files or execute requests.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public final class PortableHttpCallParser {
    public static final int MAX_TEMPLATE_LENGTH = 65536;
    public static final int MAX_HEADER_LENGTH = 8192;
    private static final Set<String> CALL_FIELDS = Set.of("profile", "requires", "request", "response");
    private static final Set<String> RESPONSE_FIELDS = Set.of("kind", "soapAction", "expectedStatus", "extract", "namespaces");
    private static final Set<String> REQUEST_FIELDS = Set.of("serviceId", "callId", "protocol", "method", "pathTemplate", "bodyTemplate", "headersTemplate");
    private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade", "proxy-authorization");

    public PortableHttpCall parse(String source) {
        return parse(new JsonDocumentReader().readObject(source));
    }

    public PortableHttpCall parse(Map<?, ?> source) {
        fields(source, CALL_FIELDS);
        String profile = text(source.get("profile"), 40, false);
        if (!(source.get("requires") instanceof List<?> requested) || requested.isEmpty() || requested.size() > PortableCapability.values().length) throw invalid("Declare portable capabilities explicitly.");
        Set<PortableCapability> requires = new LinkedHashSet<>();
        for (Object value : requested) if (!requires.add(PortableCapability.parse(text(value, 40, false)))) throw invalid("Duplicate portable capability.");
        Map<?, ?> request = object(source.get("request"));
        fields(request, REQUEST_FIELDS);
        for (String field : REQUEST_FIELDS) if (!field.equals("headersTemplate")) text(request.get(field), MAX_TEMPLATE_LENGTH, field.equals("bodyTemplate"));
        strings(request.get("headersTemplate"), 30, MAX_TEMPLATE_LENGTH);
        TemplateDefinition definition;
        try { definition = new RequestTemplateParser().parse(request); }
        catch (IllegalArgumentException failure) { throw invalid("Invalid portable HTTP request definition."); }
        if (!(definition instanceof HttpTemplateDefinition http)) throw invalid("Portable calls require HTTP.");
        Map<?, ?> response = object(source.get("response"));
        fields(response, RESPONSE_FIELDS);
        PortableResponseKind kind;
        try { kind = PortableResponseKind.valueOf(text(response.get("kind"), 10, false)); }
        catch (IllegalArgumentException failure) { throw invalid("Choose JSON, SOAP11 or SOAP12 explicitly."); }
        if (!(response.get("expectedStatus") instanceof Integer number)) throw invalid("Expected status must be an integer.");
        PortableHttpCall call = new PortableHttpCall(profile, requires, http, new PortableResponseDefinition(kind,
            text(response.get("soapAction"), 2048, true), number.intValue(), strings(response.get("extract"), 200, 2048), strings(response.get("namespaces"), 30, 2048)));
        validate(call);
        return call;
    }

    public void validate(PortableHttpCall call) {
        if (call == null || !PortableHttpCall.PROFILE.equals(call.profile()) || call.request() == null || call.response() == null || call.requires().isEmpty()) throw invalid("Unsupported or incomplete portable profile.");
        HttpTemplateDefinition request = call.request();
        PortableResponseDefinition response = call.response();
        if (!"HTTP".equals(request.protocol()) || request.authRef() != null || request.resultRules() != null
            || !Set.of("POST", "PUT", "PATCH", "GET", "DELETE").contains(request.method())) throw invalid("Unsupported portable HTTP request capability.");
        text(request.pathTemplate(), MAX_TEMPLATE_LENGTH, false);
        text(request.bodyTemplate(), MAX_TEMPLATE_LENGTH, true);
        if (request.headersTemplate() == null || request.headersTemplate().size() > 30) throw invalid("Define at most 30 HTTP headers explicitly.");
        Set<String> names = new LinkedHashSet<>();
        for (var header : request.headersTemplate().entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!header.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !names.add(name) || TRANSPORT_HEADERS.contains(name)) throw invalid("Invalid, duplicate or transport-owned header.");
            if (response.kind() != PortableResponseKind.JSON && Set.of("content-type", "soapaction").contains(name)) throw invalid("SOAP headers belong to the shared SOAP owner.");
            text(header.getValue(), MAX_TEMPLATE_LENGTH, true);
        }
        if (response.kind() == null || response.expectedStatus() < 200 || response.expectedStatus() > 299 || response.extract().isEmpty()) throw invalid("Declare successful status and response fields explicitly.");
        for (var entry : response.extract().entrySet()) {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,79}")) throw invalid("Use canonical output field names.");
            text(entry.getValue(), 2048, response.kind() == PortableResponseKind.JSON);
        }
        if (response.kind() == PortableResponseKind.JSON) {
            if (!call.requires().contains(PortableCapability.JSON_PATH) || !response.soapAction().isEmpty() || !response.namespaces().isEmpty()) throw invalid("JSON response requires json_path and no SOAP settings.");
            response.extract().values().forEach(new JsonPointerSelector()::validate);
        } else {
            PortableCapability capability = response.kind() == PortableResponseKind.SOAP11 ? PortableCapability.SOAP11 : PortableCapability.SOAP12;
            if (!request.method().equals("POST") || !call.requires().contains(capability) || !call.requires().contains(PortableCapability.XML_PATH)) throw invalid("SOAP requires POST, xml_path and its version capability.");
            SoapVersion version = SoapVersion.valueOf(response.kind().name());
            SoapEnvelope soap = new SoapEnvelope();
            soap.headers(version, response.soapAction());
            Map<String, String> bindings = soap.namespaces(version, response.namespaces());
            response.extract().values().forEach(selector -> new XPathSelector().validate(selector, bindings));
        }
    }

    private static Map<?, ?> object(Object source) {
        if (!(source instanceof Map<?, ?> map)) throw invalid("Expected a portable call object.");
        return map;
    }
    private static void fields(Map<?, ?> source, Set<String> expected) {
        if (source == null || !source.keySet().equals(expected)) throw invalid("Portable fields are missing or unknown.");
    }
    private static Map<String, String> strings(Object source, int maximum, int length) {
        Map<?, ?> map = object(source);
        if (map.size() > maximum) throw invalid("Portable mapping exceeds its limit.");
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(text(key, 80, false), text(value, length, true)));
        return result;
    }
    private static String text(Object source, int maximum, boolean empty) {
        if (!(source instanceof String value) || value.length() > maximum || (!empty && value.isBlank())) throw invalid("Invalid portable text value.");
        return value;
    }
    private static TemplateDataException invalid(String message) { return new TemplateDataException(TemplateDataError.INVALID_VALUE, message); }
}
