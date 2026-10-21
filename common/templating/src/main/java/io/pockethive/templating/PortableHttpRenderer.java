package io.pockethive.templating;

import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.PortableHttpCallParser;
import io.pockethive.requesttemplates.PortableResponseKind;
import io.pockethive.templatedata.SoapEnvelope;
import io.pockethive.templatedata.SoapVersion;
import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import io.pockethive.requesttemplates.PortableCapability;

/**
 * Responsibility: render an admitted portable call with fixed-origin paths/headers.
 * Must not: discover endpoint configuration, execute requests or interpret responses.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public final class PortableHttpRenderer {
    private final Map<Set<PortableCapability>, PortableTemplateRenderer> profiles = new LinkedHashMap<>(8, 0.75f, true);
    private synchronized PortableTemplateRenderer profile(PortableHttpCall call) {
        PortableTemplateRenderer current = profiles.get(call.requires());
        if (current == null) {
            if (profiles.size() == 8) profiles.remove(profiles.keySet().iterator().next());
            current = new PortableTemplateRenderer(call.requires());
            profiles.put(call.requires(), current);
        }
        return current;
    }
    public void validate(PortableHttpCall call) {
        new PortableHttpCallParser().validate(call);
        PortableTemplateRenderer renderer = profile(call);
        renderer.validateSyntax(call.request().pathTemplate());
        renderer.validateSyntax(call.request().bodyTemplate());
        call.request().headersTemplate().values().forEach(renderer::validateSyntax);
    }
    public PortableHttpRequest render(PortableHttpCall call, PortableHttpBinding binding) {
        validate(call);
        PortableTemplateRenderer renderer = profile(call);
        Map<String, Object> context = binding.context();
        String path = renderer.render(call.request().pathTemplate(), context);
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0) throw invalid("Portable path must stay on the bound endpoint origin.");
        URI relative;
        try { relative = URI.create(path); }
        catch (IllegalArgumentException failure) { throw invalid("Invalid rendered portable path."); }
        if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getFragment() != null) throw invalid("Portable paths cannot change origin or include fragments.");
        Map<String, String> headers = new LinkedHashMap<>();
        call.request().headersTemplate().forEach((name, template) -> {
            String value = renderer.render(template, context);
            if (value.length() > PortableHttpCallParser.MAX_HEADER_LENGTH || value.chars().anyMatch(character -> (character < 32 && character != 9) || character == 127)) throw invalid("Invalid rendered HTTP header.");
            headers.put(name, value);
        });
        if (call.response().kind() != PortableResponseKind.JSON) headers.putAll(new SoapEnvelope().headers(SoapVersion.valueOf(call.response().kind().name()), call.response().soapAction()));
        return new PortableHttpRequest(binding.endpoint().resolve(relative), call.request().method(), renderer.render(call.request().bodyTemplate(), context), headers);
    }
    private static TemplateDataException invalid(String message) { return new TemplateDataException(TemplateDataError.INVALID_VALUE, message); }
}
