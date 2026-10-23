package io.pockethive.templatedata;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;

/**
 * Responsibility: validate explicit XPath namespace bindings and resolve namespace names.
 * Must not: load namespace URIs or reserve protocol-specific prefixes.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
final class XPathNamespaces implements NamespaceContext {
    private final Map<String, String> bindings;

    XPathNamespaces(Map<String, String> bindings) {
        if (bindings == null) throw invalidBinding();
        for (var binding : bindings.entrySet()) {
            String prefix = binding.getKey();
            String uri = binding.getValue();
            if (prefix == null || !prefix.matches("[A-Za-z_][A-Za-z0-9_.-]*")
                    || prefix.toLowerCase(Locale.ROOT).startsWith(XMLConstants.XML_NS_PREFIX)
                    || uri == null || uri.isBlank() || uri.equals(XMLConstants.XML_NS_URI)
                    || uri.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI)) {
                throw invalidBinding();
            }
        }
        this.bindings = Map.copyOf(bindings);
    }

    @Override
    public String getNamespaceURI(String prefix) {
        if (prefix == null) throw invalidBinding();
        if (XMLConstants.XML_NS_PREFIX.equals(prefix)) return XMLConstants.XML_NS_URI;
        if (XMLConstants.XMLNS_ATTRIBUTE.equals(prefix)) return XMLConstants.XMLNS_ATTRIBUTE_NS_URI;
        return bindings.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
    }

    @Override
    public String getPrefix(String namespaceURI) {
        Iterator<String> prefixes = getPrefixes(namespaceURI);
        return prefixes.hasNext() ? prefixes.next() : null;
    }

    @Override
    public Iterator<String> getPrefixes(String namespaceURI) {
        if (namespaceURI == null) throw invalidBinding();
        if (XMLConstants.XML_NS_URI.equals(namespaceURI)) return java.util.List.of(XMLConstants.XML_NS_PREFIX).iterator();
        if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespaceURI)) return java.util.List.of(XMLConstants.XMLNS_ATTRIBUTE).iterator();
        return bindings.entrySet().stream().filter(binding -> namespaceURI.equals(binding.getValue()))
            .map(Map.Entry::getKey).iterator();
    }

    private TemplateDataException invalidBinding() {
        return new TemplateDataException(TemplateDataError.INVALID_SELECTOR, "Invalid or reserved XPath namespace binding.");
    }
}
