package io.pockethive.mcp.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Performs one authoritative readiness observation; retry timing belongs to the client. */
@Service
public final class SwarmReadinessObserver {
    private final OwnerApiPort owner;
    private final ObjectMapper mapper;

    public SwarmReadinessObserver(OwnerApiPort owner, ObjectMapper mapper) {
        this.owner = owner;
        this.mapper = mapper;
    }

    public SwarmReadinessResult observe(String path, String swarmId) {
        Status status = status(owner.get(path));
        return new SwarmReadinessResult(status.ready(), swarmId, status.totals(), status.swarmStatus(), 1);
    }

    private Status status(Object response) {
        JsonNode context = mapper.valueToTree(response).path("envelope").path("data").path("context");
        JsonNode totals = context.path("totals");
        JsonNode swarmStatus = context.path("swarmStatus");
        JsonNode desired = totals.path("desired");
        JsonNode healthy = totals.path("healthy");
        if (!context.isObject() || !totals.isObject() || !swarmStatus.isTextual()
            || swarmStatus.textValue().isBlank() || !desired.isIntegralNumber() || !healthy.isIntegralNumber()) {
            throw new ToolExecutionException("SWARM_STATUS_INVALID",
                "Owner response must contain envelope.data.context.swarmStatus and integral totals.desired/healthy");
        }
        Map<String, Object> allTotals = mapper.convertValue(totals, new TypeReference<>() { });
        return new Status(swarmStatus.textValue(), desired.longValue(), healthy.longValue(), Map.copyOf(allTotals));
    }

    private record Status(String swarmStatus, long desired, long healthy, Map<String, Object> totals) {
        boolean ready() {
            return desired > 0 && healthy >= desired && "READY".equals(swarmStatus);
        }
    }
}
