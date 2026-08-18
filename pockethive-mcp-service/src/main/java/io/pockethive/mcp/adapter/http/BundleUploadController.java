package io.pockethive.mcp.adapter.http;

import io.pockethive.mcp.application.AmbiguousPublicationException;
import io.pockethive.mcp.application.BundleUploadCoordinator;
import io.pockethive.mcp.application.UploadOutcome;
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

    @PutMapping(value = "/mcp/uploads/{ticketId}", consumes = "application/zip",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadOutcome upload(@PathVariable("ticketId") String ticketId,
                                @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                                @RequestHeader(HttpHeaders.CONTENT_LENGTH) long contentLength,
                                HttpServletRequest request, Authentication authentication) throws IOException {
        return coordinator.receive(ticketId, principals.resolve(authentication), contentType, contentLength,
            request.getInputStream(), clock.instant());
    }

    @ExceptionHandler(UploadRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(UploadRejectedException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", exception.getMessage()));
    }

    @ExceptionHandler(AmbiguousPublicationException.class)
    ResponseEntity<Map<String, String>> ambiguous(AmbiguousPublicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", exception.getMessage()));
    }

    @ExceptionHandler(PublicationStateSyncException.class)
    ResponseEntity<Map<String, String>> publicationStateSync(PublicationStateSyncException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "code", "PUBLICATION_SUCCEEDED_WORKFLOW_SYNC_FAILED",
            "attemptId", exception.attemptId()));
    }
}
