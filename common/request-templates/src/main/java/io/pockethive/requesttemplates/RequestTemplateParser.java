package io.pockethive.requesttemplates;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.worker.sdk.auth.AuthApplyAs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: parse and validate decoded request-template documents for every consumer.
 * Must not: read files, resolve bundle references, evaluate templates or open clients.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public final class RequestTemplateParser {
    private static final List<String> COMMON_REQUIRED_FIELDS = List.of("protocol", "serviceId", "callId");
    private static final List<String> HTTP_REQUIRED_FIELDS = List.of("method", "pathTemplate");
    public static final String AUTH_REFERENCE_FIELD = "authRef";

    /** Required fields use the same ordered definitions as parsing. */
    public static List<String> requiredFields(RequestTemplateProtocol protocol) {
        if (protocol == RequestTemplateProtocol.HTTP) {
            return java.util.stream.Stream.concat(COMMON_REQUIRED_FIELDS.stream(), HTTP_REQUIRED_FIELDS.stream()).toList();
        }
        return COMMON_REQUIRED_FIELDS;
    }

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public TemplateDefinition parse(Map<?, ?> document) {
        Objects.requireNonNull(document, "document");
        List<RequestTemplateProblem> problems = new ArrayList<>();
        Map<String, String> required = new LinkedHashMap<>();
        for (String field : COMMON_REQUIRED_FIELDS) {
            required.put(field, requiredText(document, field, problems));
        }
        String protocolText = required.get("protocol");
        String serviceId = required.get("serviceId");
        String callId = required.get("callId");
        RequestTemplateProtocol protocol = null;
        if (protocolText != null) {
            try {
                protocol = RequestTemplateProtocol.valueOf(protocolText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add(new RequestTemplateProblem(RequestTemplateProblemKind.INVALID_VALUE,
                    "protocol", "Unsupported request-template protocol: " + protocolText));
            }
        }
        if (protocol == RequestTemplateProtocol.HTTP) {
            for (String field : HTTP_REQUIRED_FIELDS) {
                requiredText(document, field, problems);
            }
        }
        problems.addAll(authProblems(document));
        if (!problems.isEmpty()) {
            throw new RequestTemplateException(problems);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        document.forEach((key, value) -> normalized.put(Objects.toString(key), value));
        normalized.put("protocol", protocol.name());
        normalized.put("serviceId", serviceId);
        normalized.put("callId", callId);
        if (document.containsKey(AUTH_REFERENCE_FIELD)) {
            normalized.put(AUTH_REFERENCE_FIELD, authReference(document.get(AUTH_REFERENCE_FIELD)));
        }
        Class<? extends TemplateDefinition> definitionType = switch (protocol) {
            case HTTP -> HttpTemplateDefinition.class;
            case TCP -> TcpTemplateDefinition.class;
            case ISO8583 -> Iso8583TemplateDefinition.class;
        };
        try {
            return mapper.convertValue(normalized, definitionType);
        } catch (IllegalArgumentException e) {
            throw new RequestTemplateException(List.of(new RequestTemplateProblem(
                RequestTemplateProblemKind.INVALID_VALUE, "", e.getMessage())));
        }
    }

    /** Auth shape/value checks shared with bundle profile-reference validation. */
    public List<RequestTemplateProblem> authProblems(Map<?, ?> document) {
        List<RequestTemplateProblem> problems = new ArrayList<>();
        if (document.containsKey("auth")) {
            problems.add(new RequestTemplateProblem(RequestTemplateProblemKind.INLINE_AUTH,
                "auth", "Request templates must use authRef instead of inline auth"));
        }
        if (document.containsKey(AUTH_REFERENCE_FIELD)) {
            try {
                authReference(document.get(AUTH_REFERENCE_FIELD));
            } catch (RequestTemplateException e) {
                problems.addAll(e.problems());
            }
        }
        return List.copyOf(problems);
    }

    public AuthRef authReference(Object value) {
        if (!(value instanceof Map<?, ?> document)) {
            throw authFailure(RequestTemplateProblemKind.AUTH_REFERENCE, "authRef",
                "authRef must be an object with profileId and applyAs");
        }
        AuthApplyAs applyAs;
        try {
            Object raw = document.get("applyAs");
            applyAs = AuthApplyAs.parse(raw instanceof String text ? text : null);
        } catch (IllegalArgumentException e) {
            throw authFailure(RequestTemplateProblemKind.AUTH_APPLY_AS, "authRef.applyAs", e.getMessage());
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        document.forEach((key, entry) -> normalized.put(Objects.toString(key), entry));
        normalized.put("applyAs", applyAs);
        try {
            return mapper.convertValue(normalized, AuthRef.class);
        } catch (IllegalArgumentException e) {
            throw authFailure(RequestTemplateProblemKind.AUTH_REFERENCE, "authRef", e.getMessage());
        }
    }

    private static RequestTemplateException authFailure(RequestTemplateProblemKind kind, String field, String message) {
        return new RequestTemplateException(List.of(new RequestTemplateProblem(kind, field, message)));
    }

    public static String key(String serviceId, String callId) {
        return Objects.requireNonNull(serviceId, "serviceId").trim() + "::"
            + Objects.requireNonNull(callId, "callId").trim();
    }

    private static String requiredText(Map<?, ?> document, String field, List<RequestTemplateProblem> problems) {
        if (document.get(field) instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        problems.add(new RequestTemplateProblem(RequestTemplateProblemKind.REQUIRED_FIELD,
            field, "Request template is missing required field '" + field + "'"));
        return null;
    }
}
