package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.ValidationException;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Factory for creating credential source implementations.
 * Follows Factory pattern and keeps implementations package-private for testability.
 */
final class CredentialSources {
    
    private static final Logger LOGGER = Logger.getLogger(CredentialSources.class.getName());
    
    private CredentialSources() {}
    
    static CredentialSource cli(String password) {
        return new CliPasswordSource(password);
    }
    
    static CredentialSource envVar(String envVar) {
        return new EnvVarSource(envVar);
    }
    
    static CredentialSource file(Path credFile) {
        return new FileSource(credFile);
    }
    
    static CredentialSource stdin(boolean enabled) {
        return new StdinSource(enabled);
    }
    
    static CredentialSource console() {
        return new ConsoleSource();
    }
    
    // Implementations
    
    static class CliPasswordSource implements CredentialSource {
        private final String password;
        
        CliPasswordSource(String password) {
            this.password = password;
        }
        
        @Override
        public Optional<String> resolve() {
            if (password != null && !password.isEmpty()) {
                LOGGER.warning("Password provided via CLI arguments is insecure. Use --password-stdin, --password-file, or environment variables instead.");
                return Optional.of(password);
            }
            return Optional.empty();
        }
        
        @Override
        public String name() {
            return "CLI";
        }
    }
    
    static class EnvVarSource implements CredentialSource {
        private final String envVar;
        
        EnvVarSource(String envVar) {
            this.envVar = envVar;
        }
        
        @Override
        public Optional<String> resolve() {
            if (envVar != null && !envVar.isBlank()) {
                String password = System.getenv(envVar);
                if (password != null && !password.isEmpty()) {
                    return Optional.of(password);
                }
            }
            return Optional.empty();
        }
        
        @Override
        public String name() {
            return "Environment Variable";
        }
    }
    
    static class FileSource implements CredentialSource {
        private final Path credFile;
        private final FilePermissionValidator validator = new FilePermissionValidator();
        
        FileSource(Path credFile) {
            this.credFile = credFile;
        }
        
        @Override
        public Optional<String> resolve() {
            if (credFile != null && Files.exists(credFile)) {
                validator.validate(credFile);
                return Optional.of(readFile());
            }
            return Optional.empty();
        }
        
        private String readFile() {
            try {
                String password = Files.readString(credFile, StandardCharsets.UTF_8).trim();
                if (password.isEmpty()) {
                    throw new ValidationException("Credential file is empty: " + credFile);
                }
                return password;
            } catch (ValidationException e) {
                throw e;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read credential file: " + credFile, e);
            }
        }
        
        @Override
        public String name() {
            return "File";
        }
    }
    
    static class StdinSource implements CredentialSource {
        private final boolean enabled;
        
        StdinSource(boolean enabled) {
            this.enabled = enabled;
        }
        
        @Override
        public Optional<String> resolve() {
            if (!enabled) {
                return Optional.empty();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String password = reader.readLine();
                if (password == null || password.trim().isEmpty()) {
                    throw new ValidationException("No password provided via stdin");
                }
                return Optional.of(password.trim());
            } catch (ValidationException e) {
                throw e;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read password from stdin", e);
            }
        }
        
        @Override
        public String name() {
            return "Stdin";
        }
    }
    
    static class ConsoleSource implements CredentialSource {
        
        @Override
        public Optional<String> resolve() {
            Console console = System.console();
            if (console != null) {
                char[] pwd = console.readPassword("Database password: ");
                if (pwd != null && pwd.length > 0) {
                    String password = new String(pwd);
                    Arrays.fill(pwd, ' ');
                    return Optional.of(password);
                }
            }
            return Optional.empty();
        }
        
        @Override
        public String name() {
            return "Console";
        }
    }
}
