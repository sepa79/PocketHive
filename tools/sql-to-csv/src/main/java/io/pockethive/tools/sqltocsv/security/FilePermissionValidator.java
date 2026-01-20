package io.pockethive.tools.sqltocsv.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

/**
 * Validates file permissions for credential files.
 * Follows Single Responsibility Principle.
 */
class FilePermissionValidator {
    
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    
    void validate(Path credFile) {
        try {
            if (OS_NAME.contains("win")) {
                validateWindows(credFile);
            } else {
                validateUnix(credFile);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot validate credential file permissions: " + credFile, e);
        }
    }
    
    private void validateWindows(Path credFile) throws IOException {
        if (!Files.isReadable(credFile)) {
            throw new SecurityException("Credential file is not readable: " + credFile);
        }
    }
    
    private void validateUnix(Path credFile) throws IOException {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(credFile);
        if (perms.size() != 2 || 
            !perms.contains(PosixFilePermission.OWNER_READ) || 
            !perms.contains(PosixFilePermission.OWNER_WRITE)) {
            throw new SecurityException("Credential file must have 0600 permissions (owner read/write only): " + credFile);
        }
    }
}
