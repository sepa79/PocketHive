package io.pockethive.mcp.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import io.pockethive.mcp.application.AmbiguousPublicationException;
import io.pockethive.mcp.application.BundleUploadCoordinator;
import io.pockethive.mcp.application.BundleValidationReceiptView;
import io.pockethive.mcp.application.PublicationStateSyncException;
import io.pockethive.mcp.application.UploadRejectedException;
import io.pockethive.mcp.application.ValidationUploadOutcome;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.security.AuthenticatedPrincipalResolver;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

class BundleUploadControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void streamsTheAuthenticatedRequestBodyToOneExactTicketAtTheInjectedTime() throws Exception {
        BundleUploadCoordinator coordinator = mock(BundleUploadCoordinator.class);
        AuthenticatedPrincipalResolver principals = mock(AuthenticatedPrincipalResolver.class);
        Authentication authentication = mock(Authentication.class);
        PrincipalKey principal = new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[] {1, 2, 3});
        ValidationUploadOutcome expected = new ValidationUploadOutcome(mock(BundleValidationReceiptView.class));
        when(principals.resolve(authentication)).thenReturn(principal);
        when(coordinator.receive(eq("ticket-1"), eq(principal), eq("application/zip"), eq(3L),
            any(java.io.InputStream.class), eq(NOW)))
            .thenReturn(expected);
        BundleUploadController controller = new BundleUploadController(
            coordinator, principals, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(controller.upload("ticket-1", "application/zip", 3, request, authentication))
            .isSameAs(expected);
        verify(coordinator).receive(eq("ticket-1"), eq(principal), eq("application/zip"), eq(3L),
            any(java.io.InputStream.class), eq(NOW));
    }

    @Test
    void mapsUploadAmbiguityAndPostOwnerStateSyncToDistinctStableResponses() {
        BundleUploadController controller = new BundleUploadController(
            mock(BundleUploadCoordinator.class), mock(AuthenticatedPrincipalResolver.class), Clock.systemUTC());

        assertThat(controller.rejected(new UploadRejectedException("UPLOAD_DIGEST_MISMATCH")))
            .satisfies(response -> {
                assertThat(response.getStatusCode().value()).isEqualTo(400);
                assertThat(response.getBody()).containsEntry("code", "UPLOAD_DIGEST_MISMATCH");
            });
        assertThat(controller.ambiguous(new AmbiguousPublicationException("attempt-1", new Exception("lost"))))
            .satisfies(response -> {
                assertThat(response.getStatusCode().value()).isEqualTo(409);
                assertThat(response.getBody()).containsEntry(
                    "code", "PUBLICATION_RESULT_AMBIGUOUS: attempt-1");
            });
        assertThat(controller.publicationStateSync(
            new PublicationStateSyncException("attempt-2", new Exception("disk"))))
            .satisfies(response -> {
                assertThat(response.getStatusCode().value()).isEqualTo(409);
                assertThat(response.getBody())
                    .containsEntry("code", "PUBLICATION_SUCCEEDED_WORKFLOW_SYNC_FAILED")
                    .containsEntry("attemptId", "attempt-2");
            });
    }
}
