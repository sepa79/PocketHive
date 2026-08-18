package io.pockethive.mcp.application;

import java.util.List;
import java.util.Map;

public record ToolDescriptor(
    String id,
    String description,
    Map<String, Object> inputSchema,
    ToolOwner owner,
    String requiredScope,
    boolean readOnly,
    boolean destructive,
    boolean idempotent,
    List<String> skillIds
) {
}
