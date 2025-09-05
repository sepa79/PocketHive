package io.pockethive.observability;

import java.util.ArrayList;
import java.util.List;

public class ObservabilityContext {
    private String traceId;
    private List<Hop> hops = new ArrayList<>();
    private String swarmId;

    public ObservabilityContext() {
    }

    public ObservabilityContext(String traceId, List<Hop> hops) {
        this.traceId = traceId;
        this.hops = hops;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<Hop> getHops() {
        return hops;
    }

    public void setHops(List<Hop> hops) {
        this.hops = hops;
    }

    public String getSwarmId() {
        return swarmId;
    }

    public void setSwarmId(String swarmId) {
        this.swarmId = swarmId;
    }
}
