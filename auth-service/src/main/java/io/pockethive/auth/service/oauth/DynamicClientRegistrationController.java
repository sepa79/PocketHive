package io.pockethive.auth.service.oauth;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class DynamicClientRegistrationController {
    private final DynamicClientRegistrationService registrations;

    DynamicClientRegistrationController(DynamicClientRegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping(path = DynamicClientRegistrationService.REGISTRATION_PATH,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DynamicClientRegistrationResponse> register(
        @RequestBody DynamicClientRegistrationRequest request
    ) {
        return ResponseEntity.status(201).body(registrations.register(request));
    }

    @ExceptionHandler(DynamicClientRegistrationException.class)
    ResponseEntity<Map<String, String>> rejected(DynamicClientRegistrationException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
            "error", exception.error(),
            "error_description", exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformed(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "invalid_client_metadata",
            "error_description", "Registration metadata is not valid JSON"));
    }
}
