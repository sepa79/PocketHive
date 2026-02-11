package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.ValidationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Validates output file paths to prevent path traversal and unauthorized file access.
 */
public class PathValidator {
    
    private static final List<String> UNIX_SYSTEM_PATHS = List.of("/etc", "/sys", "/proc", "/boot", "/dev", "/root");
    private static final List<String> WINDOWS_SYSTEM_PATHS = List.of("\\windows", "\\program files", "\\system32");
    
    public Path validateOutputPath(Path outputPath, boolean allowAbsolute) {
        if (outputPath == null) {
            throw new ValidationException("Output path cannot be null");
        }
        
        Path normalized = outputPath.normalize().toAbsolutePath();
        
        // Restrict to current directory unless explicitly allowed
        if (!allowAbsolute) {
            Path currentDir = Path.of(System.getProperty("user.dir"));
            if (!normalized.startsWith(currentDir)) {
                throw new SecurityException("Absolute paths require --allow-absolute-paths flag: " + outputPath);
            }
        }
        
        // Block system directories
        if (isSystemPath(normalized)) {
            throw new SecurityException("Cannot write to system directory: " + normalized);
        }
        
        // Validate parent directory exists or can be created
        Path parent = normalized.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create parent directory: " + parent, e);
            }
        }
        
        return normalized;
    }
    
    private boolean isSystemPath(Path path) {
        String pathStr = path.toString().toLowerCase(Locale.ROOT);
        
        // Unix/Linux system paths
        for (String systemPath : UNIX_SYSTEM_PATHS) {
            if (pathStr.startsWith(systemPath)) {
                return true;
            }
        }
        
        // Windows system paths (check drive root + system folder)
        if (pathStr.length() >= 3 && pathStr.charAt(1) == ':') {
            String withoutDrive = pathStr.substring(2);
            for (String systemPath : WINDOWS_SYSTEM_PATHS) {
                if (withoutDrive.startsWith(systemPath)) {
                    return true;
                }
            }
        }
        
        return false;
    }
}
