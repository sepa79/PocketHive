package io.pockethive.tools.sqltocsv.security;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Resolves database credentials from multiple sources with security best practices.
 * Priority: env var → credential file → stdin → interactive prompt
 */
public class CredentialProvider {
    
    public String resolvePassword(String cliPassword, String envVar, Path credFile, boolean useStdin) {
        // CLI password (insecure, show warning)
        if (cliPassword != null && !cliPassword.isEmpty()) {
            System.err.println("WARNING: Password in CLI args is insecure. Use --password-stdin, --password-file, or env vars.");
            return cliPassword;
        }
        
        // Environment variable
        String envPassword = System.getenv(envVar);
        if (envPassword != null && !envPassword.isEmpty()) {
            return envPassword;
        }
        
        // Credential file
        if (credFile != null && Files.exists(credFile)) {
            validateFilePermissions(credFile);
            return readCredentialFile(credFile);
        }
        
        // Stdin
        if (useStdin) {
            return readFromStdin();
        }
        
        // Interactive prompt
        Console console = System.console();
        if (console != null) {
            char[] pwd = console.readPassword("Database password: ");
            if (pwd != null && pwd.length > 0) {
                return new String(pwd);
            }
        }
        
        throw new IllegalStateException("No password provided. Use -p, --password-stdin, --password-file, --password-env, or interactive prompt.");
    }
    
    private void validateFilePermissions(Path credFile) {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                // Windows: basic check that file exists and is readable
                if (!Files.isReadable(credFile)) {
                    throw new SecurityException("Credential file is not readable: " + credFile);
                }
            } else {
                // Unix: enforce 0600 permissions
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(credFile);
                if (perms.size() != 2 || 
                    !perms.contains(PosixFilePermission.OWNER_READ) || 
                    !perms.contains(PosixFilePermission.OWNER_WRITE)) {
                    throw new SecurityException("Credential file must have 0600 permissions (owner read/write only): " + credFile);
                }
            }
        } catch (IOException e) {
            throw new SecurityException("Cannot validate credential file permissions: " + e.getMessage(), e);
        }
    }
    
    private String readCredentialFile(Path credFile) {
        try {
            String password = Files.readString(credFile).trim();
            if (password.isEmpty()) {
                throw new SecurityException("Credential file is empty: " + credFile);
            }
            return password;
        } catch (IOException e) {
            throw new SecurityException("Cannot read credential file: " + e.getMessage(), e);
        }
    }
    
    private String readFromStdin() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String password = reader.readLine();
            if (password == null || password.trim().isEmpty()) {
                throw new SecurityException("No password provided via stdin");
            }
            return password.trim();
        } catch (IOException e) {
            throw new SecurityException("Cannot read password from stdin: " + e.getMessage(), e);
        }
    }
}
