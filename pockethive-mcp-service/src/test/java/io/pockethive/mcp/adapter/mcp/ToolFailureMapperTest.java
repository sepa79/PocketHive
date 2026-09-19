package io.pockethive.mcp.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.application.UploadRejectedException;
import io.pockethive.mcp.domain.WorkflowRuleViolation;
import org.junit.jupiter.api.Test;

class ToolFailureMapperTest {
    private final ToolFailureMapper mapper = new ToolFailureMapper();

    @Test
    void mapsKnownFailuresToStableSafeToolFailures() {
        assertThat(mapper.known(new ToolExecutionException("OWNER_UNAVAILABLE", "Owner response was unavailable")))
            .contains(new KnownToolFailure("OWNER_UNAVAILABLE", "Owner response was unavailable"));
        assertThat(mapper.known(new WorkflowRuleViolation("WORKFLOW_NOT_READY")))
            .contains(new KnownToolFailure("WORKFLOW_NOT_READY", "WORKFLOW_NOT_READY"));
        assertThat(mapper.known(new UploadRejectedException("UPLOAD_TICKET_EXPIRED")))
            .contains(new KnownToolFailure("UPLOAD_TICKET_EXPIRED", "UPLOAD_TICKET_EXPIRED"));
        assertThat(mapper.known(new ToolExecutionException("EMPTY_MESSAGE", "")))
            .contains(new KnownToolFailure("EMPTY_MESSAGE", "EMPTY_MESSAGE"));
    }

    @Test
    void leavesUnexpectedFailuresForProtocolHandlingWithoutLeakingTheirMessage() {
        IllegalStateException unexpected = new IllegalStateException("internal-only-detail");

        assertThat(mapper.known(unexpected)).isEmpty();
        ToolProtocolException protocol = mapper.unexpected("scenario_list", unexpected);

        assertThat(protocol.getMessage())
            .startsWith("Unexpected tool failure; correlationId=")
            .doesNotContain("internal-only-detail");
        assertThat(protocol.getCause()).isSameAs(unexpected);
    }
}
