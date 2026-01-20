package io.pockethive.tools.sqltocsv.security;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Validates output file paths to prevent path traversal and unauthorized file access.
 */
public class PathValidator {
    
    public Path validateOutputPath(Path outputPath, boolean allowAbsolute) throws SecurityException {
        Path normalized = outputPath.normalize().toAbsolutePath();
        
        // Restrict to current directory unless explicitly allowed
        Path currentDir = Paths.get("").toAbsolutePath();
        if (!allowAbsolute && !normalized.startsWith(currentDir)) {
            throw new SecurityException("Absolute paths require --allow-absolute-paths flag: " + outputPath);
        }
        
        // Block system directories
        if (isSystemPath(normalized)) {
            throw new SecurityException("Cannot write to system directory: " + normalized);
        }
        
        return normalized;
    }
    
    private boolean isSystemPath(Path path) {
        String pathStr = path.toString().toLowerCase();
        
        // Unix/Linux system paths
        if (pathStr.startsWith("/etc") || pathStr.startsWith("/sys") || 
            pathStr.startsWith("/proc") || pathStr.startsWith("/boot") ||
            pathStr.startsWith("/dev") || pathStr.startsWith("/root")) {
            return true;
        }
        
        // Windows system paths
        if (pathStr.matches("^[a-z]:\\\\windows.*") || 
            pathStr.matches("^[a-z]:\\\\program files.*") ||
            pathStr.matches("^[a-z]:\\\\system.*")) {
            return true;
        }
        
        return false;
    }
}
