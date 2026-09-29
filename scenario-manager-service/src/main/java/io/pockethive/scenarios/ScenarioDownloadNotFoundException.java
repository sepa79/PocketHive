package io.pockethive.scenarios;

/**
 * Responsibility: report the existing missing-target cases of scenario-ID downloads.
 * Must not: decide HTTP mapping or implement target lookup.
 * Contract: RESP-SCENARIO-BUNDLE-DOWNLOAD — docs/architecture/runtime-responsibilities.md#resp-scenario-bundle-download.
 */
public class ScenarioDownloadNotFoundException extends RuntimeException {
    public ScenarioDownloadNotFoundException() { super(); }
    public ScenarioDownloadNotFoundException(String message) { super(message); }
    public ScenarioDownloadNotFoundException(String message, Throwable cause) { super(message, cause); }
}
