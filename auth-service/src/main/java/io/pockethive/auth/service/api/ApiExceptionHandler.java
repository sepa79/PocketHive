package io.pockethive.auth.service.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null || ex.getReason().isBlank() ? ex.getStatusCode().toString() : ex.getReason();
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "Invalid request" : ex.getMessage();
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
    }
}
