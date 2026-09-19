package io.pockethive.work.config;

/**
 * Responsibility: define canonical field names for the neutral Work IO configuration envelope.
 * Must not: define adapter settings fields, parse values or select an adapter.
 * Contract: RESP-WORK-CONFIGURATION-PARSER —
 * docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public final class WorkConfigurationFields {
    public static final String INPUTS = "inputs";
    public static final String OUTPUTS = "outputs";
    public static final String TYPE = "type";

    private WorkConfigurationFields() {
    }

    public static String path(String root, String field) {
        return root + "." + field;
    }
}
