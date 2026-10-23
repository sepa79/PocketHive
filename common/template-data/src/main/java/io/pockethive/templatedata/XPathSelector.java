package io.pockethive.templatedata;

import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Responsibility: preflight namespace-aware XPath and require exactly one scalar XML node.
 * Must not: execute extension functions, resolve variables or interpret SOAP envelopes.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class XPathSelector {
    private final XmlDocumentReader reader = new XmlDocumentReader();

    public void validate(String selector, Map<String, String> namespaces) {
        compile(selector, namespaces);
    }

    public String requiredScalar(Object source, String selector, Map<String, String> namespaces) {
        XPathExpression expression = compile(selector, namespaces);
        XmlDocumentView view;
        if (source instanceof String document) view = reader.read(document);
        else if (source instanceof XmlDocumentView document) view = document;
        else throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "XPath source must be XML text or an XML document view.");
        try {
            NodeList nodes = (NodeList) expression.evaluate(view.document(), XPathConstants.NODESET);
            if (nodes.getLength() == 0) {
                throw new TemplateDataException(TemplateDataError.MISSING_VALUE, "XPath must select one response node.");
            }
            if (nodes.getLength() != 1) {
                throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "XPath must select exactly one response node.");
            }
            return scalar(nodes.item(0));
        } catch (TemplateDataException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "XML could not be interpreted with the configured XPath.");
        }
    }

    private XPathExpression compile(String selector, Map<String, String> namespaces) {
        XPathNamespaces context = new XPathNamespaces(namespaces);
        if (selector == null || selector.isBlank()) throw invalidSelector();
        rejectVariablesAndExtensions(selector);
        try {
            XPathFactory factory = XPathFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XPath xpath = factory.newXPath();
            xpath.setNamespaceContext(context);
            xpath.setXPathVariableResolver(name -> { throw invalidSelector(); });
            xpath.setXPathFunctionResolver((name, arity) -> { throw invalidSelector(); });
            XPathExpression expression = xpath.compile(selector);
            expression.evaluate(reader.read("<preflight/>").document(), XPathConstants.NODESET);
            return expression;
        } catch (Exception failure) {
            throw invalidSelector();
        }
    }

    private void rejectVariablesAndExtensions(String selector) {
        char quote = 0;
        for (int index = 0; index < selector.length(); index++) {
            char character = selector.charAt(index);
            if (quote != 0) {
                if (character == quote) quote = 0;
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '$') {
                throw invalidSelector();
            } else if (character == '(') {
                int end = index;
                while (end > 0 && Character.isWhitespace(selector.charAt(end - 1))) end--;
                int start = end;
                while (start > 0) {
                    char previous = selector.charAt(start - 1);
                    if (Character.isWhitespace(previous) || "/[]()@=<>!+*|,'\"".indexOf(previous) >= 0) break;
                    start--;
                }
                String function = selector.substring(start, end);
                int axis = function.lastIndexOf("::");
                if (axis >= 0) function = function.substring(axis + 2);
                if (function.indexOf(':') >= 0) throw invalidSelector();
            }
        }
    }

    private String scalar(Node node) {
        if (node.getNodeType() != Node.ELEMENT_NODE && node.getNodeType() != Node.ATTRIBUTE_NODE
                && node.getNodeType() != Node.TEXT_NODE && node.getNodeType() != Node.CDATA_SECTION_NODE) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "XPath must select a text-only value.");
        }
        if (node instanceof Element element && element.getElementsByTagName("*").getLength() != 0) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "XPath must not select an XML container.");
        }
        return node.getTextContent();
    }

    private TemplateDataException invalidSelector() {
        return new TemplateDataException(TemplateDataError.INVALID_SELECTOR, "XPath must select nodes without variables, extensions or undeclared namespaces.");
    }
}
