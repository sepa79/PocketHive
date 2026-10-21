package io.pockethive.templatedata;

/**
 * Responsibility: identify SOAP versions and their canonical envelope namespaces.
 * Must not: infer versions or own HTTP execution.
 * Contract: RESP-TEMPLATE-SOAP — docs/architecture/runtime-responsibilities.md#resp-template-soap.
 */
public enum SoapVersion {
    SOAP11("http://schemas.xmlsoap.org/soap/envelope/"),
    SOAP12("http://www.w3.org/2003/05/soap-envelope");

    private final String namespace;

    SoapVersion(String namespace) {
        this.namespace = namespace;
    }

    public String namespace() {
        return namespace;
    }
}
