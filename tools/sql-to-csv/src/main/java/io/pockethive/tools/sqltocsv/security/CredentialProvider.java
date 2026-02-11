package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.ValidationException;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves database credentials from multiple sources using Strategy pattern.
 * Thread-safe: Immutable design with defensive copies.
 * Follows Single Responsibility and Open/Closed principles.
 */
public class CredentialProvider {
    
    private final List<CredentialSource> sources;
    
    public CredentialProvider(String cliPassword, String envVar, Path credFile, boolean useStdin) {
        this(createDefaultSources(cliPassword, envVar, credFile, useStdin));
    }
    
    // Package-private for testing
    CredentialProvider(List<CredentialSource> sources) {
        this.sources = List.copyOf(sources);
    }
    
    public String resolvePassword() {
        return sources.stream()
            .map(CredentialSource::resolve)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst()
            .orElseThrow(() -> new ValidationException(
                "No password provided. Use -p, --password-stdin, --password-file, --password-env, or interactive prompt."
            ));
    }
    
    private static List<CredentialSource> createDefaultSources(String cliPassword, String envVar, Path credFile, boolean useStdin) {
        return List.of(
            CredentialSources.cli(cliPassword),
            CredentialSources.envVar(envVar),
            CredentialSources.file(credFile),
            CredentialSources.stdin(useStdin),
            CredentialSources.console()
        );
    }
}
