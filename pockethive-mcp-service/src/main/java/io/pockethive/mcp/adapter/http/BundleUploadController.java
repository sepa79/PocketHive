package io.pockethive.mcp.adapter.http;

import io.pockethive.mcp.application.AmbiguousPublicationException;
import io.pockethive.mcp.application.BundleUploadCoordinator;
import io.pockethive.mcp.application.BundleUploadContract;
import io.pockethive.mcp.application.UploadOutcome;
import io.pockethive.mcp.application.UploadAuthenticationException;
import io.pockethive.mcp.application.UploadRejectedException;
import io.pockethive.mcp.application.PublicationStateSyncException;
import io.pockethive.mcp.security.AuthenticatedPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class BundleUploadController {
    private final BundleUploadCoordinator coordinator;
    private final AuthenticatedPrincipalResolver principals;
    private final Clock clock;

    public BundleUploadController(BundleUploadCoordinator coordinator, AuthenticatedPrincipalResolver principals,
                                  Clock clock) {
        this.coordinator = coordinator;
        this.principals = principals;
        this.clock = clock;
    }

    @PutMapping(value = BundleUploadContract.PATH_PATTERN, consumes = "application/zip",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadOutcome upload(@PathVariable("ticketId") String ticketId,
                                @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                                @RequestHeader(HttpHeaders.CONTENT_LENGTH) long contentLength,
                                @RequestHeader(value = BundleUploadContract.UPLOAD_CAPABILITY_HEADER,
                                    required = false) String uploadCapability,
                                HttpServletRequest request, Authentication authentication) throws IOException {
        boolean bearerAuthenticated = authentication instanceof BearerTokenAuthentication;
        boolean capabilityPresented = uploadCapability != null && !uploadCapability.isBlank();
        if (bearerAuthenticated && capabilityPresented) {
            throw new UploadRejectedException("UPLOAD_AUTHENTICATION_AMBIGUOUS");
        }
        if (bearerAuthenticated) {
            return coordinator.receive(ticketId, principals.resolve(authentication), contentType, contentLength,
                request.getInputStream(), clock.instant());
        }
        if (!capabilityPresented) {
            throw new UploadAuthenticationException("UPLOAD_AUTHENTICATION_REQUIRED");
        }
        return coordinator.receiveWithCapability(ticketId, uploadCapability, contentType, contentLength,
            request.getInputStream(), clock.instant());
    }

    @ExceptionHandler(UploadAuthenticationException.class)
    ResponseEntity<Map<String, String>> authentication(UploadAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, BundleUploadContract.UPLOAD_CAPABILITY_CHALLENGE)
            .body(Map.of("code", exception.getMessage()));
    }

    @ExceptionHandler(UploadRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(UploadRejectedException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", exception.getMessage()));
    }

    @ExceptionHandler(AmbiguousPublicationException.class)
    ResponseEntity<Map<String, String>> ambiguous(AmbiguousPublicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "code", "PUBLICATION_RESULT_AMBIGUOUS",
            "attemptId", exception.attemptId()));
    }

    @ExceptionHandler(PublicationStateSyncException.class)
    ResponseEntity<Map<String, String>> publicationStateSync(PublicationStateSyncException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "code", "PUBLICATION_SUCCEEDED_WORKFLOW_SYNC_FAILED",
            "attemptId", exception.attemptId()));
    }
}
