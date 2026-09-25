package io.pockethive.acceptance.config;

/**
 * Responsibility: retain explicit lifecycle and Grafana outcome observation settings.
 * Must not: resolve production sink configuration or infer credentials/datasources.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#transaction-outcome-persistence-acceptance-da-3.
 */
public record TxOutcomeTarget(AcceptanceTarget lifecycle, String grafanaUsername,
    String grafanaPassword, String datasourceUid, String outcomeTable) { }
