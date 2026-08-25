package io.pockethive.mcp.adapter.mcp;

import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.application.UploadRejectedException;
import io.pockethive.mcp.domain.WorkflowRuleViolation;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class ToolFailureMapper {
    private static final Logger log = LoggerFactory.getLogger(ToolFailureMapper.class);

    Optional<KnownToolFailure> known(RuntimeException exception) {
        if (exception instanceof ToolExecutionException failure) {
            return Optional.of(new KnownToolFailure(failure.code(), safeMessage(failure)));
        }
        if (exception instanceof WorkflowRuleViolation failure) {
            return Optional.of(codeOnly(failure));
        }
        if (exception instanceof UploadRejectedException failure) {
            return Optional.of(codeOnly(failure));
        }
        return Optional.empty();
    }

    ToolProtocolException unexpected(String toolId, RuntimeException exception) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unexpected MCP tool failure tool={} correlationId={} exceptionType={}",
            toolId, correlationId, exception.getClass().getName());
        return new ToolProtocolException(correlationId, exception);
    }

    private static KnownToolFailure codeOnly(RuntimeException exception) {
        String code = safeCode(exception.getMessage());
        return new KnownToolFailure(code, code);
    }

    private static String safeMessage(ToolExecutionException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.code() : message;
    }

    private static String safeCode(String value) {
        return value == null || value.isBlank() ? "TOOL_OPERATION_REJECTED" : value;
    }
}
