package io.pockethive.orchestrator.app;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record JournalPageResponse(
    List<Map<String, Object>> items,
    Cursor nextCursor,
    boolean hasMore) {

  public record Cursor(Instant ts, long id) {}
}

