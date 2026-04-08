package io.pockethive.tools.sqltocsv.security;

import java.util.Optional;

/**
 * Strategy interface for credential resolution from different sources.
 * Follows Strategy pattern and Open/Closed principle.
 */
interface CredentialSource {
    
    /**
     * Attempts to resolve a credential from this source.
     * @return Optional containing the credential if found, empty otherwise
     */
    Optional<String> resolve();
    
    /**
     * Returns the name of this credential source for logging.
     */
    String name();
}
